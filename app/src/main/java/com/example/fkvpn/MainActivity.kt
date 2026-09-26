package com.example.fkvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal UI: a config text box and a start/stop button.
 *
 * The config box holds the leaf [Proxy]/[Rule] section only — the service
 * prepends [General] with the live tun-fd at connect time.
 */
class MainActivity : Activity() {

    private lateinit var configBox: EditText
    private lateinit var toggle: Button
    private lateinit var importBtn: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configBox = findViewById(R.id.configBox)
        toggle = findViewById(R.id.toggle)
        importBtn = findViewById(R.id.importBtn)
        status = findViewById(R.id.status)

        configBox.setText(SAMPLE_CONF)

        importBtn.setOnClickListener { importLink() }

        toggle.setOnClickListener {
            if (FkVpnService.running) {
                stopVpn()
            } else {
                requestAndStart()
            }
        }
        refresh()
    }

    /**
     * If the config box holds a share link (vless://, vmess://, trojan://,
     * ss://), convert it to a leaf JSON config in place so the user can review
     * it before connecting.
     */
    private fun importLink() {
        val text = configBox.text.toString()
        val link = LinkParser.firstLink(text)
        if (link == null) {
            Toast.makeText(this, "No vless:// / vmess:// / trojan:// / ss:// link found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            configBox.setText(LinkParser.toLeafJson(link))
            Toast.makeText(this, "Imported. Review, then Connect.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun requestAndStart() {
        // VpnService.prepare shows the system consent dialog if needed.
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN)
        } else {
            onActivityResult(REQ_VPN, RESULT_OK, null)
        }
    }

    @Deprecated("startActivityForResult is fine for a single VPN consent flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            // Auto-convert a pasted share link right before connecting.
            var conf = configBox.text.toString()
            LinkParser.firstLink(conf)?.let { link ->
                try {
                    conf = LinkParser.toLeafJson(link)
                    configBox.setText(conf)
                } catch (e: Exception) {
                    Toast.makeText(this, "Link parse failed: ${e.message}", Toast.LENGTH_LONG).show()
                    return
                }
            }
            val intent = Intent(this, FkVpnService::class.java)
                .setAction(FkVpnService.ACTION_START)
                .putExtra(FkVpnService.EXTRA_PROXY_CONF, conf)
            startForegroundService(intent)
            status.postDelayed({ refresh() }, 500)
        } else if (requestCode == REQ_VPN) {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, FkVpnService::class.java)
            .setAction(FkVpnService.ACTION_STOP)
        startService(intent)
        status.postDelayed({ refresh() }, 500)
    }

    private fun refresh() {
        val on = FkVpnService.running
        status.text = if (on) "Status: connected" else "Status: disconnected"
        toggle.text = if (on) "Disconnect" else "Connect"
    }

    companion object {
        private const val REQ_VPN = 1001

        private val SAMPLE_CONF = """
            # Paste a vless:// / vmess:// / trojan:// / ss:// link here and tap
            # "Import link" (or just Connect — links are auto-converted).
            #
            # Or write a leaf .conf block directly:
            [Proxy]
            Direct = direct

            [Rule]
            FINAL, Direct
        """.trimIndent()
    }
}
