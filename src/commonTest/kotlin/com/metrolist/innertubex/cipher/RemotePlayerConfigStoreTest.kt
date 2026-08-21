package com.metrolist.innertubex.cipher

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemotePlayerConfigStoreTest {
    @Test
    fun rejectsUntrustedRemoteConfigSourceBeforeRequest() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val repository =
                object : PlayerConfigRepository {
                    override val enabled = true
                    override val defaultSourceUrl = "https://example.com/player_configs.json"
                    override val sourceUrl = defaultSourceUrl
                    override var cachedJson = ""
                    override var cachedAtMs = 0L
                    override var cachedSourceUrl = ""
                    override var cachedEtag = ""
                }
            val store = RemotePlayerConfigStore(HttpClient(engine), repository)

            assertNull(store.getSignatureTimestamp("https://www.youtube.com/s/player/12345678/player.js"))
            assertTrue(engine.requestHistory.isEmpty())
        }
}
