package com.metrolist.innertubex.cipher

interface PlayerConfigRepository {
    val enabled: Boolean
    val sourceUrl: String
    val defaultSourceUrl: String

    var cachedJson: String
    var cachedAtMs: Long
    var cachedSourceUrl: String
    var cachedEtag: String
}
