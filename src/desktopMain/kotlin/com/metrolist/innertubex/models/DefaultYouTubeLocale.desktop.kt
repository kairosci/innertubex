package com.metrolist.innertubex.models

import java.util.Locale

actual fun defaultYouTubeLocale(): YouTubeLocale = systemYouTubeLocale(Locale.getDefault())

internal fun systemYouTubeLocale(locale: Locale): YouTubeLocale =
    YouTubeLocale(
        gl = locale.country.uppercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: "US",
        hl = locale.toLanguageTag().takeIf { it.isNotBlank() } ?: "en-US",
    )
