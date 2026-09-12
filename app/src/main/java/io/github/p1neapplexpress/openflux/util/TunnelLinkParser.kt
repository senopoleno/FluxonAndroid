package io.github.p1neapplexpress.openflux.util

import android.net.Uri
import android.util.Base64
import io.github.p1neapplexpress.openflux.data.Tunnel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

object TunnelLinkParser {

    private const val SCHEME = "openflux"
    private const val HOST_IMPORT = "import"
    private const val PARAM_DATA = "data"

    fun toLink(tunnel: Tunnel): String {
        val json = Json.encodeToString(tunnel)
        val base64 = Base64.encodeToString(
            json.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$SCHEME://$HOST_IMPORT?$PARAM_DATA=$base64"
    }

    fun parse(input: String?): Tunnel? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()

        if (trimmed.startsWith("$SCHEME:", ignoreCase = true) || trimmed.contains("://$HOST_IMPORT", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            if (uri != null) {
                fromUri(uri)?.let { return it }
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching { Json.decodeFromString<Tunnel>(trimmed) }.getOrNull()?.let { return it }
        }

        decodeBase64(trimmed)?.let { jsonStr ->
            runCatching { Json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()?.let { return it }
        }

        return null
    }

    fun fromUri(uri: Uri): Tunnel? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null

        val dataParam = uri.getQueryParameter(PARAM_DATA)
            ?: uri.getQueryParameter("config")
        if (!dataParam.isNullOrBlank()) {
            decodeBase64(dataParam)?.let { jsonStr ->
                runCatching { Json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()?.let { return it }
            }
        }

        for (segment in uri.pathSegments.reversed()) {
            decodeBase64(segment)?.let { jsonStr ->
                runCatching { Json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()?.let { return it }
            }
        }

        val host = uri.host
        if (!host.isNullOrBlank() && !host.equals(HOST_IMPORT, ignoreCase = true) && !host.equals("config", ignoreCase = true)) {
            decodeBase64(host)?.let { jsonStr ->
                runCatching { Json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()?.let { return it }
            }
        }

        return null
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
}
