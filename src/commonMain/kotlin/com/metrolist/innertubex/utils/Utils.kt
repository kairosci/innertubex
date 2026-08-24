package com.metrolist.innertubex.utils

internal fun sanitizeCookieString(cookie: String): String {
    val normalized = cookie.filterNot { it == '\r' || it == '\n' || it == '\t' }
    require(normalized.none { it.isISOControl() }) { "Cookie must not contain control characters" }
    return normalized
        .trim()
        .removePrefixIgnoreCase("Cookie:")
        .trim()
}

internal fun parseCookieString(cookie: String): Map<String, String> =
    sanitizeCookieString(cookie).split(";").map { it.trim() }.filter { it.isNotEmpty() }.associate {
        val parts = it.split("=", limit = 2)
        val rawKey = parts.getOrNull(0)?.trim() ?: ""
        val key = rawKey.removePrefix("Cookie:").removePrefix("cookie:").trim()
        val value = parts.getOrNull(1)?.trim() ?: ""
        key to value
    }

private fun String.removePrefixIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

// Expect declaration for platform-specific SHA-1 implementation
internal expect fun sha1(input: String): String
