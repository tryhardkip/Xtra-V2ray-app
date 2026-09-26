package com.example.fkvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * The VPN service. This is the piece that can only live in the Android SDK
 * layer: it asks Android for a TUN interface, hands the resulting file
 * descriptor to the Rust engine via a `tun-fd` config entry, and runs the
 * engine on a background thread.
 */
class FkVpnService : VpnService() {

    companion object {
        private const val TAG = "FkVpnService"
        private const val RT_ID = 0
        private const val NOTIF_CHANNEL = "fkvpn"
        private const val NOTIF_ID = 1

        const val ACTION_START = "com.example.fkvpn.START"
        const val ACTION_STOP = "com.example.fkvpn.STOP"
        /** Extra: the proxy outbound config block (leaf .conf [Proxy]/[Rule] text). */
        const val EXTRA_PROXY_CONF = "proxy_conf"

        @Volatile
        var running: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var engineThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            else -> {
                val proxyConf = intent?.getStringExtra(EXTRA_PROXY_CONF).orEmpty()
                startVpn(proxyConf)
            }
        }
        return START_STICKY
    }

    private fun startVpn(proxyConf: String) {
        if (running) {
            Log.w(TAG, "already running")
            return
        }

        // 1) Build the TUN interface. VpnService installs the routing rules for us.
        val builder = Builder()
            .setSession("FkVpn")
            .setMtu(1500)
            .addAddress("10.10.0.2", 24)     // tunnel-local address
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)          // capture all IPv4 traffic
            // Never route the app's own traffic through itself.
            .also { b ->
                try {
                    b.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "addDisallowedApplication failed: ${e.message}")
                }
            }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }
        if (pfd == null) {
            Log.e(TAG, "VpnService.establish() returned null; is the VPN permission granted?")
            stopSelf()
            return
        }
        vpnInterface = pfd
        val tunFd = pfd.fd

        // 2) Write a leaf config that consumes this fd.
        val configFile = writeConfig(tunFd, proxyConf)

        // 3) Foreground notification (required for a long-running VPN service).
        startForeground(NOTIF_ID, buildNotification())

        // 4) Run the engine on a background thread (runLeaf blocks).
        running = true
        engineThread = thread(name = "fkcore-engine") {
            Log.i(TAG, "engine starting, tunFd=$tunFd")
            val rc = FkNative.runLeaf(configFile.absolutePath, RT_ID)
            Log.i(TAG, "engine exited rc=$rc")
            running = false
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "stopping")
        try {
            FkNative.shutdown(RT_ID)
        } catch (e: Throwable) {
            Log.w(TAG, "shutdown threw: ${e.message}")
        }
        engineThread?.join(3000)
        engineThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    /**
     * Writes the leaf config, injecting the live tun fd.
     *
     * Two formats are supported and auto-detected:
     *  - JSON (starts with `{`): a leaf JSON config with an "outbounds" array,
     *    as produced by [LinkParser] from a share link. We add the tun inbound,
     *    dns and log here. Written as `.json` so leaf uses its JSON parser.
     *  - .conf (anything else): the classic [Proxy]/[Rule] block. We prepend
     *    [General] with the tun-fd. Written as `.conf`.
     */
    private fun writeConfig(tunFd: Int, proxyConf: String): File {
        val body = proxyConf.trim()
        return if (body.startsWith("{")) {
            writeJsonConfig(tunFd, body)
        } else {
            writeConfConfig(tunFd, body)
        }
    }

    private fun writeJsonConfig(tunFd: Int, body: String): File {
        val root = JSONObject(body)

        root.put("log", JSONObject().put("level", "info"))
        root.put(
            "dns",
            JSONObject().put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
        )
        // The tun inbound consumes the fd from VpnService.establish().
        val tunInbound = JSONObject()
            .put("protocol", "tun")
            .put("tag", "tun_in")
            .put("settings", JSONObject().put("fd", tunFd).put("mtu", 1500))
        root.put("inbounds", JSONArray().put(tunInbound))

        val f = File(filesDir, "fkvpn.json")
        f.writeText(root.toString())
        return f
    }

    private fun writeConfConfig(tunFd: Int, proxyConf: String): File {
        val defaultProxy = """
            [Proxy]
            Direct = direct

            [Rule]
            FINAL, Direct
        """.trimIndent()

        val body = proxyConf.ifBlank { defaultProxy }

        val conf = buildString {
            appendLine("[General]")
            appendLine("loglevel = info")
            appendLine("dns-server = 1.1.1.1, 8.8.8.8")
            appendLine("tun-fd = $tunFd")
            appendLine()
            append(body)
            appendLine()
        }

        val f = File(filesDir, "fkvpn.conf")
        f.writeText(conf)
        return f
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "FkVpn", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FkVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("FkVpn")
            .setContentText("Proxy tunnel active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
            .setOngoing(true)
            .build()
    }
}
