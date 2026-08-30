package com.metrolist.innertubex.utils

/** Normalizes a Cookie-header value without exposing or parsing its sensitive contents. */
public fun sanitizeCookieString(cookie: String): String {
    val normalized = cookie.filterNot { it == '\r' || it == '\n' || it == '\t' }
    require(normalized.none { it.isISOControl() }) { "Cookie must not contain control characters" }
    return normalized
        .trim()
        .removePrefixIgnoreCase("Cookie:")
        .trim()
}

/** Parses a normalized Cookie-header value. The returned names and values remain sensitive. */
public fun parseCookieString(cookie: String): Map<String, String> =
    sanitizeCookieString(cookie).split(";").map { it.trim() }.filter { it.isNotEmpty() }.associate {
        val parts = it.split("=", limit = 2)
        val rawKey = parts.getOrNull(0)?.trim() ?: ""
        val key = rawKey.removePrefix("Cookie:").removePrefix("cookie:").trim()
        val value = parts.getOrNull(1)?.trim() ?: ""
        key to value
    }

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

/** Decodes a percent-encoded query component without JVM java.net dependencies. */
internal fun decodeQueryComponent(encoded: String): String {
    val bytes = ArrayList<Byte>(encoded.length)
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        when (c) {
            '+' -> {
                bytes.add(' '.code.toByte())
                i++
            }

            '%' -> {
                if (i + 2 < encoded.length) {
                    val hex = encoded.substring(i + 1, i + 3)
                    val byteVal = hex.toIntOrNull(16)
                    if (byteVal != null) {
                        bytes.add(byteVal.toByte())
                        i += 3
                        continue
                    }
                }
                bytes.add('%'.code.toByte())
                i++
            }

            else -> {
                bytes.add(c.code.toByte())
                i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

// Expect declaration for platform-specific SHA-1 implementation

/** SHA-1 compatibility hash. Do not use for passwords, signatures, or security decisions. */
public expect fun sha1(input: String): String
