package com.metrolist.innertubex.utils

fun sanitizeCookieString(cookie: String): String = cookie.filterNot { it.isISOControl() }.trim()

fun parseCookieString(cookie: String): Map<String, String> =
    sanitizeCookieString(cookie).split(";").map { it.trim() }.filter { it.isNotEmpty() }.associate {
        val parts = it.split("=", limit = 2)
        val rawKey = parts.getOrNull(0)?.trim() ?: ""
        val key = rawKey.removePrefix("Cookie:").removePrefix("cookie:").trim()
        val value = parts.getOrNull(1)?.trim() ?: ""
        key to value
    }

// Expect declaration for platform-specific SHA-1 implementation
expect fun sha1(input: String): String
