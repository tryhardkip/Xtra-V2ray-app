package com.example.fkvpn

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Converts share links (vless://, vmess://, trojan://, ss://) into a leaf
 * JSON config fragment containing the "outbounds" array.
 *
 * We emit JSON rather than leaf's .conf format because .conf cannot express
 * VLESS over a WebSocket/TLS transport chain (its conf builder only wires those
 * transports for VMess/Trojan). JSON lets us build the transport chain
 * explicitly: [tls?, ws?, <protocol>].
 *
 * The proxy outbound is always emitted FIRST with tag "proxy". leaf uses the
 * first outbound as the default handler, so all captured traffic flows through
 * it without needing a routing rule (leaf rejects rules with no matcher).
 *
 * Multiplexing (amux) is intentionally never emitted, so mux stays disabled.
 */
object LinkParser {

    private val SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://")

    fun looksLikeLink(text: String): Boolean {
        val t = text.trim()
        return SCHEMES.any { t.startsWith(it, ignoreCase = true) }
    }

    /** Returns the first share link found in [text], or null. */
    fun firstLink(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { looksLikeLink(it) }

    /**
     * Parses a single share link into a pretty-printed leaf JSON config
     * (an object with an "outbounds" array). Throws [IllegalArgumentException]
     * with a human-readable message on unsupported/invalid input.
     */
    fun toLeafJson(link: String): String {
        val trimmed = link.trim()
        val outbounds = when {
            trimmed.startsWith("vless://", true) -> parseVless(trimmed)
            trimmed.startsWith("vmess://", true) -> parseVmess(trimmed)
            trimmed.startsWith("trojan://", true) -> parseTrojan(trimmed)
            trimmed.startsWith("ss://", true) -> parseShadowsocks(trimmed)
            else -> throw IllegalArgumentException("Unsupported link type")
        }
        val root = JSONObject()
        root.put("outbounds", outbounds)
        return root.toString(2)
    }

    // ---- protocol parsers -------------------------------------------------

    private fun parseVless(link: String): JSONArray {
        val uri = Uri.parse(link)
        val uuid = uri.userInfo ?: err("VLESS link missing UUID")
        val host = uri.host ?: err("VLESS link missing host")
        val port = if (uri.port > 0) uri.port else err("VLESS link missing port")

        val q = queryMap(link)
        val security = (q["security"] ?: "none").lowercase()
        val net = (q["type"] ?: "tcp").lowercase()

        val core = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("uuid", uuid)
        return chainOutbounds("vless", core, security, net, q)
    }

    private fun parseTrojan(link: String): JSONArray {
        val uri = Uri.parse(link)
        val password = uri.userInfo ?: err("Trojan link missing password")
        val host = uri.host ?: err("Trojan link missing host")
        val port = if (uri.port > 0) uri.port else err("Trojan link missing port")

        val q = queryMap(link)
        // Trojan defaults to TLS unless explicitly disabled.
        val security = (q["security"] ?: "tls").lowercase()
        val net = (q["type"] ?: "tcp").lowercase()

        val core = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("password", password)
        return chainOutbounds("trojan", core, security, net, q)
    }

    private fun parseVmess(link: String): JSONArray {
        val b64 = link.substring("vmess://".length).trim()
        val json = try {
            JSONObject(String(decodeB64(b64), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            err("VMess link is not valid base64 JSON")
        }
        val host = json.optString("add").ifBlank { err("VMess link missing address") }
        val port = json.optString("port").toIntOrNull() ?: err("VMess link missing port")
        val uuid = json.optString("id").ifBlank { err("VMess link missing id") }
        val net = json.optString("net", "tcp").lowercase()
        val tls = json.optString("tls", "").lowercase()
        val security = if (tls == "tls" || tls == "reality") tls else "none"

        // Re-key into the generic query map the transport builder expects.
        val q = HashMap<String, String>()
        json.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
        json.optString("host").takeIf { it.isNotBlank() }?.let { q["host"] = it }
        json.optString("sni").takeIf { it.isNotBlank() }?.let { q["sni"] = it }

        val core = JSONObject()
            .put("address", host)
            .put("port", port)
            .put("uuid", uuid)
            // leaf needs a concrete cipher; VMess AEAD works with these.
            .put("security", json.optString("scy", "chacha20-ietf-poly1305").ifBlank { "chacha20-ietf-poly1305" })
        return chainOutbounds("vmess", core, security, net, q)
    }

    private fun parseShadowsocks(link: String): JSONArray {
        // ss://base64(method:password)@host:port#tag
        // or ss://base64(method:password@host:port)#tag
        val body = link.substring("ss://".length).substringBefore("#").trim()
        val method: String
        val password: String
        val host: String
        val port: Int
        if (body.contains("@")) {
            val userInfoRaw = body.substringBefore("@")
            val userInfo = try {
                String(decodeB64(userInfoRaw), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                URLDecoder.decode(userInfoRaw, "UTF-8")
            }
            method = userInfo.substringBefore(":")
            password = userInfo.substringAfter(":")
            val hostPort = body.substringAfter("@").substringBefore("?")
            host = hostPort.substringBeforeLast(":")
            port = hostPort.substringAfterLast(":").toIntOrNull() ?: err("SS link missing port")
        } else {
            val decoded = String(decodeB64(body), StandardCharsets.UTF_8)
            val userInfo = decoded.substringBefore("@")
            method = userInfo.substringBefore(":")
            password = userInfo.substringAfter(":")
            val hostPort = decoded.substringAfter("@")
            host = hostPort.substringBeforeLast(":")
            port = hostPort.substringAfterLast(":").toIntOrNull() ?: err("SS link missing port")
        }

        val core = JSONObject()
            .put("protocol", "shadowsocks")
            .put("tag", "proxy")
            .put(
                "settings",
                JSONObject()
                    .put("address", host)
                    .put("port", port)
                    .put("method", method)
                    .put("password", password)
            )
        return JSONArray()
            .put(core)
            .put(directOutbound())
    }

    // ---- transport chain builder -----------------------------------------

    /**
     * Builds the outbound array for a protocol whose core settings are given in
     * [coreSettings], wrapping it in tls/reality and/or ws transports as needed.
     */
    private fun chainOutbounds(
        protocol: String,
        coreSettings: JSONObject,
        security: String,
        net: String,
        q: Map<String, String>
    ): JSONArray {
        val actors = ArrayList<String>()
        val components = ArrayList<JSONObject>()

        val sni = q["sni"] ?: q["host"] ?: coreSettings.optString("address")

        when (security) {
            "tls" -> {
                actors.add("proxy_tls")
                components.add(
                    outbound("tls", "proxy_tls", JSONObject().put("serverName", sni))
                )
            }
            "reality" -> {
                actors.add("proxy_reality")
                components.add(
                    outbound(
                        "reality", "proxy_reality",
                        JSONObject()
                            .put("serverName", sni)
                            .put("publicKey", q["pbk"] ?: "")
                            .put("shortId", q["sid"] ?: "")
                    )
                )
                // NOTE: requires the Rust core built with the outbound-reality
                // feature. The default build does not include it.
            }
            "none", "" -> { /* plaintext transport */ }
            else -> throw IllegalArgumentException("Unsupported security: $security")
        }

        when (net) {
            "ws", "websocket" -> {
                actors.add("proxy_ws")
                val headers = JSONObject()
                (q["host"] ?: sni).takeIf { it.isNotBlank() }?.let { headers.put("Host", it) }
                val wsSettings = JSONObject().put("path", q["path"] ?: "/")
                if (headers.length() > 0) wsSettings.put("headers", headers)
                components.add(outbound("ws", "proxy_ws", wsSettings))
            }
            "tcp", "" -> { /* raw tcp */ }
            else -> throw IllegalArgumentException(
                "Unsupported transport '$net' (only tcp and ws are supported)"
            )
        }

        val result = JSONArray()
        if (actors.isEmpty()) {
            // No transport: the core protocol itself is the proxy outbound.
            result.put(outbound(protocol, "proxy", coreSettings))
        } else {
            actors.add("proxy_core")
            components.add(outbound(protocol, "proxy_core", coreSettings))
            // Chain must be first so it becomes leaf's default handler.
            result.put(
                outbound("chain", "proxy", JSONObject().put("actors", JSONArray(actors)))
            )
            components.forEach { result.put(it) }
        }
        result.put(directOutbound())
        return result
    }

    private fun outbound(protocol: String, tag: String, settings: JSONObject): JSONObject =
        JSONObject()
            .put("protocol", protocol)
            .put("tag", tag)
            .put("settings", settings)

    private fun directOutbound(): JSONObject =
        JSONObject().put("protocol", "direct").put("tag", "direct")

    // ---- helpers ----------------------------------------------------------

    private fun queryMap(link: String): Map<String, String> {
        val raw = link.substringAfter('?', "").substringBefore('#')
        if (raw.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isNotBlank()) {
                map[k] = try {
                    URLDecoder.decode(v, "UTF-8")
                } catch (e: Exception) {
                    v
                }
            }
        }
        return map
    }

    private fun decodeB64(s: String): ByteArray {
        // Accept both standard and URL-safe, with or without padding.
        val cleaned = s.replace('-', '+').replace('_', '/').trim()
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return Base64.getDecoder().decode(padded)
    }

    private fun err(msg: String): Nothing = throw IllegalArgumentException(msg)
}
