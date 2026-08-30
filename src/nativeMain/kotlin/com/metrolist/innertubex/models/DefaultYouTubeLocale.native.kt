package com.metrolist.innertubex.models

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

actual fun defaultYouTubeLocale(): YouTubeLocale {
    val current = NSLocale.currentLocale
    val country = current.countryCode?.uppercase() ?: "US"
    val language = current.languageCode
    val languageTag = if (country.isNotBlank()) "$language-$country" else language
    return YouTubeLocale(
        gl = country,
        hl = languageTag,
    )
}
