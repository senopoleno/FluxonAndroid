package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.github.p1neapplexpress.openflux.data.Tunnel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

object TunnelLinkParser {

    private const val SCHEME_OPENFLUX = "openflux"
    private const val SCHEME_FLUXON = "fluxon"
    private const val HOST_IMPORT = "import"
    private const val PARAM_DATA = "data"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Prepares tunnel for export: ensures encryptionKey property is populated from
     * local file if it was previously empty, so the receiver gets the key.
     */
    fun prepareForExport(context: Context?, tunnel: Tunnel): Tunnel {
        var key = tunnel.encryptionKey?.trim()
        if (key.isNullOrEmpty() && context != null) {
            val keyPath = argValue(tunnel.transportConnPayload, "--encryption-key-file")
            if (keyPath.isNotEmpty()) {
                key = runCatching { File(keyPath).readText().trim() }.getOrNull()
            }
            if (key.isNullOrEmpty()) {
                val defaultFile = File(context.filesDir, "key_${tunnel.id}.txt")
                if (defaultFile.exists()) {
                    key = runCatching { defaultFile.readText().trim() }.getOrNull()
                }
            }
        }
        return if (!key.isNullOrEmpty()) {
            tunnel.copy(encryptionKey = key)
        } else {
            tunnel
        }
    }

    /**
     * Exports tunnel to a shareable deep-link URL.
     */
    fun toLink(tunnel: Tunnel, context: Context? = null): String {
        val ready = prepareForExport(context, tunnel)
        val jsonStr = json.encodeToString(ready)
        val base64 = Base64.encodeToString(
            jsonStr.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$SCHEME_OPENFLUX://$HOST_IMPORT?$PARAM_DATA=$base64"
    }

    /**
     * Ensures that if the tunnel has an encryptionKey, the key file is created in
     * the local app filesDir and --encryption-key-file argument in transportConnPayload
     * points to this local file.
     */
    fun ensureLocalKeyFile(context: Context, tunnel: Tunnel): Tunnel {
        var key = tunnel.encryptionKey?.trim()
        val payload = tunnel.transportConnPayload.toMutableList()
        val keyIdx = payload.indexOf("--encryption-key-file")

        if (key.isNullOrEmpty()) {
            // Check if local file exists from payload
            if (keyIdx != -1 && keyIdx + 1 < payload.size) {
                val f = File(payload[keyIdx + 1])
                if (f.exists()) {
                    key = runCatching { f.readText().trim() }.getOrNull()
                }
            }
            if (key.isNullOrEmpty()) {
                val fallbackFile = File(context.filesDir, "key_${tunnel.id}.txt")
                if (fallbackFile.exists()) {
                    key = runCatching { fallbackFile.readText().trim() }.getOrNull()
                }
            }
        }

        if (key.isNullOrEmpty()) {
            return tunnel
        }

        // Write to local filesDir
        val localKeyFile = File(context.filesDir, "key_${tunnel.id}.txt")
        runCatching { localKeyFile.writeText(key) }

        if (keyIdx != -1 && keyIdx + 1 < payload.size) {
            payload[keyIdx + 1] = localKeyFile.absolutePath
        } else {
            payload.add("--encryption-key-file")
            payload.add(localKeyFile.absolutePath)
        }

        return tunnel.copy(
            encryptionKey = key,
            transportConnPayload = payload
        )
    }

    fun parse(input: String?, context: Context? = null): Tunnel? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()

        var tunnel: Tunnel? = null

        if (trimmed.startsWith("$SCHEME_OPENFLUX:", ignoreCase = true) ||
            trimmed.startsWith("$SCHEME_FLUXON:", ignoreCase = true) ||
            trimmed.contains("://$HOST_IMPORT", ignoreCase = true)
        ) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            if (uri != null) {
                tunnel = fromUri(uri, context)
                if (tunnel != null) return tunnel
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            tunnel = runCatching { json.decodeFromString<Tunnel>(trimmed) }.getOrNull()
        }

        if (tunnel == null) {
            decodeBase64(trimmed)?.let { jsonStr ->
                tunnel = runCatching { json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()
            }
        }

        return if (tunnel != null && context != null) {
            ensureLocalKeyFile(context, tunnel)
        } else {
            tunnel
        }
    }

    fun fromUri(uri: Uri, context: Context? = null): Tunnel? {
        val scheme = uri.scheme
        if (!scheme.equals(SCHEME_OPENFLUX, ignoreCase = true) &&
            !scheme.equals(SCHEME_FLUXON, ignoreCase = true)
        ) {
            return null
        }

        var tunnel: Tunnel? = null

        val dataParam = uri.getQueryParameter(PARAM_DATA)
            ?: uri.getQueryParameter("config")
        if (!dataParam.isNullOrBlank()) {
            decodeBase64(dataParam)?.let { jsonStr ->
                tunnel = runCatching { json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()
            }
        }

        if (tunnel == null) {
            for (segment in uri.pathSegments.reversed()) {
                decodeBase64(segment)?.let { jsonStr ->
                    tunnel = runCatching { json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()
                    if (tunnel != null) break
                }
            }
        }

        if (tunnel == null) {
            val host = uri.host
            if (!host.isNullOrBlank() &&
                !host.equals(HOST_IMPORT, ignoreCase = true) &&
                !host.equals("config", ignoreCase = true)
            ) {
                decodeBase64(host)?.let { jsonStr ->
                    tunnel = runCatching { json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()
                }
            }
        }

        return if (tunnel != null && context != null) {
            ensureLocalKeyFile(context, tunnel)
        } else {
            tunnel
        }
    }

    private fun decodeBase64(data: String): String? {
        val flagsList = intArrayOf(
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE,
            Base64.DEFAULT,
            Base64.NO_WRAP
        )
        for (flag in flagsList) {
            try {
                val bytes = Base64.decode(data, flag)
                if (bytes != null && bytes.isNotEmpty()) {
                    val str = String(bytes, StandardCharsets.UTF_8).trim()
                    if (str.startsWith("{") && str.endsWith("}")) {
                        return str
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun argValue(payload: List<String>, key: String): String {
        val i = payload.indexOf(key)
        return if (i != -1 && i + 1 < payload.size) payload[i + 1] else ""
    }
}

