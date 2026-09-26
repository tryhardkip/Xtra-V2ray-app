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
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configBox = findViewById(R.id.configBox)
        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)

        configBox.setText(SAMPLE_CONF)

        toggle.setOnClickListener {
            if (FkVpnService.running) {
                stopVpn()
            } else {
                requestAndStart()
            }
        }
        refresh()
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
            val intent = Intent(this, FkVpnService::class.java)
                .setAction(FkVpnService.ACTION_START)
                .putExtra(FkVpnService.EXTRA_PROXY_CONF, configBox.text.toString())
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
            [Proxy]
            Direct = direct
            # Replace with a server you own/trust. Examples:
            # Trojan = trojan, example.com, 443, password=PW, sni=example.com
            # VMess  = vmess, example.com, 443, username=UUID, ws=true, ws-path=/ray, tls=true
            # SS     = ss, example.com, 8388, encrypt-method=chacha20-ietf-poly1305, password=PW

            [Rule]
            FINAL, Direct
        """.trimIndent()
    }
}
