package com.metrolist.innertubex.extraction

import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class YtConfigParserTest {
    @Test
    fun extractsConfigFieldsAndRejectsUnsafePlayerUrls() {
        val parser = parser()
        assertEquals(
            "https://www.youtube.com/s/player/abc123/player_ias.vflset/en_US/base.js",
            parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"\\/s\\/player\\/abc123\\/player_ias.vflset\\/en_US\\/base.js\"}"),
        )
        assertEquals("abc123", parser.extractPlayerId("https:\\/\\/www.youtube.com\\/s\\/player\\/abc123\\/www-widgetapi.js"))
        assertEquals(20668, parser.extractSignatureTimestamp("signatureTimestamp:20668"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://evil.test/s/player/x/base.js\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://www.youtube.com/watch?v=x\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://user:pass@www.youtube.com/s/player/x/base.js\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://www.youtube.com.evil.test/s/player/x/base.js\"}"))
        assertNull(parser.extractPlayerUrl("{\"PLAYER_JS_URL\":\"https://www.youtube.com:8443/s/player/x/base.js\"}"))
    }

    @Test
    fun extractsEmbeddedContextFields() {
        val parser = parser()
        val html = "{\"INNERTUBE_CLIENT_VERSION\":\"2.20260807.00.00\",\"encryptedHostFlags\":\"flags\"}"
        assertEquals("2.20260807.00.00", parser.extractClientVersion(html))
        assertEquals("flags", parser.extractEncryptedHostFlags(html))
    }

    @Test
    fun embeddedRequestUsesValidatedUrlAndReferer() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("https://www.youtube.com/embed/video?html5=1", request.url.toString())
                        assertEquals("https://www.reddit.com/", request.headers["Referer"])
                        respond(
                            "{\"PLAYER_JS_URL\":\"/s/player/embed/base.js\",\"STS\":20668,\"visitorData\":\"visitor\",\"INNERTUBE_CLIENT_VERSION\":\"2.0\",\"encryptedHostFlags\":\"flags\"}",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    },
                )
            val config = YtConfigParserImpl(client, InnerTube(client)).fetchEmbeddedConfig("video")
            assertEquals("flags", config.encryptedHostFlags)
            assertEquals(20668, config.signatureTimestamp)
            client.close()
        }

    @Test
    fun iframeFallbackPreservesCancellation() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath == "/iframe_api") delay(1_000)
                        respond("{}", HttpStatusCode.OK)
                    },
                )
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(
                    25,
                ) { YtConfigParserImpl(client, InnerTube(client)).fetchConfig("video") }
            }
            client.close()
        }

    @Test
    fun oversizedWatchPageIsRejectedBeforePlayerParsing() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            "x".repeat(4 * 1024 * 1024 + 1),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "text/html"),
                        )
                    },
                )

            assertFailsWith<IllegalStateException> {
                YtConfigParserImpl(client, InnerTube(client)).fetchConfig("video")
            }
            client.close()
        }

    private fun parser() = YtConfigParserImpl(HttpClient(MockEngine { respond("{}") }), InnerTube(HttpClient(MockEngine { respond("{}") })))
}
