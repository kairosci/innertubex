package com.metrolist.innertubex

import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InnerTubeSessionTest {
    @Test
    fun sessionSnapshotStringDoesNotExposeCredentials() {
        val snapshot =
            InnerTube.SessionSnapshot(
                visitorData = "visitor-secret",
                dataSyncId = "sync-secret",
                authUser = "auth-secret",
                cookie = "SAPISID=cookie-secret",
                sapisid = "sapisid-secret",
            )

        val rendered = snapshot.toString()

        assertFalse(rendered.contains("visitor-secret"))
        assertFalse(rendered.contains("sync-secret"))
        assertFalse(rendered.contains("auth-secret"))
        assertFalse(rendered.contains("cookie-secret"))
        assertFalse(rendered.contains("sapisid-secret"))
    }

    @Test
    fun isolatedSessionsCopyAccountOnlyWhenRequested() {
        val innerTube = InnerTube(HttpClient(MockEngine { respondOk() }))
        innerTube.replaceSession(
            cookie = "SAPISID=secret",
            visitorData = "visitor",
            dataSyncId = "account",
            authUser = "3",
            useLoginForBrowse = true,
        )
        innerTube.regionOverrideActive = true

        val anonymous = innerTube.createIsolatedSession(includeAccount = false).sessionSnapshot()
        val authenticated = innerTube.createIsolatedSession(includeAccount = true).sessionSnapshot()

        assertNull(anonymous.cookie)
        assertEquals("visitor", anonymous.visitorData)
        assertNull(anonymous.dataSyncId)
        assertFalse(anonymous.useLoginForBrowse)
        assertEquals(innerTube.locale, anonymous.locale)
        assertTrue(anonymous.regionOverrideActive)
        assertEquals("SAPISID=secret", authenticated.cookie)
        assertEquals("visitor", authenticated.visitorData)
        assertEquals("account", authenticated.dataSyncId)
        assertEquals("3", authenticated.authUser)
        assertTrue(authenticated.useLoginForBrowse)
    }

    @Test
    fun concurrentMissingVisitorDataRequestsShareOneFetch() =
        runBlocking {
            val engine = MockEngine { respond(serviceWorkerResponse("visitor"), HttpStatusCode.OK) }
            val innerTube = InnerTube(HttpClient(engine))
            val requestSession = innerTube.sessionSnapshot()

            val visitors = List(8) { async { innerTube.fetchFreshVisitorData(requestSession) } }.awaitAll()

            assertTrue(visitors.all { it == "visitor" })
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun publicFreshVisitorDataRequestReplacesExistingValue() =
        runBlocking {
            val engine = MockEngine { respond(serviceWorkerResponse("new-visitor"), HttpStatusCode.OK) }
            val innerTube = InnerTube(HttpClient(engine)).also { it.visitorData = "old-visitor" }

            assertEquals("new-visitor", innerTube.fetchFreshVisitorData())
            assertEquals("new-visitor", innerTube.visitorData)
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun concurrentMissingVisitorDataRequestsShareFailedFetch() =
        runBlocking {
            val engine =
                MockEngine {
                    delay(50)
                    respond("no visitor data", HttpStatusCode.OK)
                }
            val innerTube = InnerTube(HttpClient(engine))
            val requestSession = innerTube.sessionSnapshot()

            val visitors = List(8) { async { innerTube.fetchFreshVisitorData(requestSession) } }.awaitAll()

            assertTrue(visitors.all { it == null })
            assertEquals(2, engine.requestHistory.size)
        }

    @Test
    fun serviceWorkerFailureFallsBackToHomepageVisitorData() =
        runBlocking {
            var serviceWorkerRequests = 0
            val engine =
                MockEngine { request ->
                    if (request.url.host == "www.youtube.com") {
                        serviceWorkerRequests++
                        respond("temporary failure", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond("""{"VISITOR_DATA":"homepage-visitor"}""", HttpStatusCode.OK)
                    }
                }
            val innerTube = InnerTube(HttpClient(engine), retryDelay = {})

            assertEquals("homepage-visitor", innerTube.fetchFreshVisitorData())
            assertEquals(3, serviceWorkerRequests)
            assertEquals(4, engine.requestHistory.size)
        }

    @Test
    fun cancellingFirstVisitorDataCallerDoesNotCancelSharedFetch() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    respond(serviceWorkerResponse("shared-visitor"), HttpStatusCode.OK)
                }
            val innerTube = InnerTube(HttpClient(engine))
            val requestSession = innerTube.sessionSnapshot()

            val firstCaller = async { innerTube.fetchFreshVisitorData(requestSession) }
            requestStarted.await()
            val survivingCaller = async { innerTube.fetchFreshVisitorData(requestSession) }
            firstCaller.cancelAndJoin()
            releaseResponse.complete(Unit)

            assertEquals("shared-visitor", survivingCaller.await())
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun cancellingVisitorDataWaiterDoesNotCancelSharedFetch() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    releaseResponse.await()
                    respond(serviceWorkerResponse("shared-visitor"), HttpStatusCode.OK)
                }
            val innerTube = InnerTube(HttpClient(engine))
            val requestSession = innerTube.sessionSnapshot()

            val survivingCaller = async { innerTube.fetchFreshVisitorData(requestSession) }
            requestStarted.await()
            val cancelledWaiter = async { innerTube.fetchFreshVisitorData(requestSession) }
            cancelledWaiter.cancelAndJoin()
            releaseResponse.complete(Unit)

            assertEquals("shared-visitor", survivingCaller.await())
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun sessionChangeCancelsPinnedPlaybackRegistration() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respond("", HttpStatusCode.OK)
                }
            val innerTube = InnerTube(HttpClient(engine))
            innerTube.cookie = "SAPISID=old-session"
            val requestSession = innerTube.sessionSnapshot()

            val registration =
                async {
                    innerTube.registerPlaybackWithSession(
                        YouTubeClient.WEB_REMIX,
                        "https://s.youtube.com/api/stats/playback",
                        "cpn",
                        null,
                        requestSession,
                    )
                }
            requestStarted.await()
            innerTube.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { registration.await() }
            Unit
        }

    @Test
    fun sessionChangeCancelsAccountMutation() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respondOk()
                }
            val innerTube = clientWithContentNegotiation(engine).also { it.cookie = "SAPISID=old-session" }

            val mutation = async { innerTube.likeVideo(YouTubeClient.WEB_REMIX, "video-id") }
            requestStarted.await()
            innerTube.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { mutation.await() }
            Unit
        }

    @Test
    fun sessionChangeCancelsFeedback() =
        runBlocking {
            val requestStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    respondOk()
                }
            val innerTube = clientWithContentNegotiation(engine).also { it.cookie = "SAPISID=old-session" }

            val mutation = async { innerTube.feedback(YouTubeClient.WEB_REMIX, listOf("feedback-token")) }
            requestStarted.await()
            innerTube.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { mutation.await() }
            Unit
        }

    @Test
    fun sessionChangeCancelsUploadFinalize() =
        runBlocking {
            val finalizeStarted = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.headers["X-Goog-Upload-Command"] == "start") {
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/upload/session"),
                        )
                    } else {
                        finalizeStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                        respondOk()
                    }
                }
            val innerTube = InnerTube(HttpClient(engine)).also { it.cookie = "SAPISID=old-session" }

            val upload = async { innerTube.uploadSong("track.mp3", byteArrayOf(1, 2, 3)) }
            finalizeStarted.await()
            innerTube.cookie = "SAPISID=new-session"

            assertFailsWith<CancellationException> { upload.await() }
            Unit
        }

    @Test
    fun playbackRegistrationRejectsUntrustedStatsHostBeforeSendingCredentials() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube = InnerTube(HttpClient(engine)).also { it.cookie = "SAPISID=secret" }

            assertFailsWith<IllegalArgumentException> {
                innerTube.registerPlaybackWithSession(
                    YouTubeClient.WEB_REMIX,
                    "https://example.com/api/stats/playback",
                    "cpn",
                    null,
                    innerTube.sessionSnapshot(),
                )
            }

            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun bulkSessionReplacementNeverPublishesMixedIdentity() {
        runBlocking {
            val innerTube = InnerTube(HttpClient(MockEngine { respondOk() }))
            val identities =
                listOf(
                    listOf("cookie-a", "visitor-a", "sync-a", "0"),
                    listOf("cookie-b", "visitor-b", "sync-b", "1"),
                )
            innerTube.replaceSession("cookie-a", "visitor-a", "sync-a", "0", useLoginForBrowse = true)

            val jobs =
                List(8) { worker ->
                    async(Dispatchers.Default) {
                        repeat(2_000) { iteration ->
                            val identity = identities[(worker + iteration) % identities.size]
                            innerTube.replaceSession(identity[0], identity[1], identity[2], identity[3], useLoginForBrowse = true)
                            val snapshot = innerTube.sessionSnapshot()
                            assertTrue(
                                listOf(
                                    snapshot.cookie,
                                    snapshot.visitorData,
                                    snapshot.dataSyncId,
                                    snapshot.authUser,
                                ) in identities,
                            )
                        }
                    }
                }
            jobs.awaitAll()
            assertEquals(innerTube.sessionSnapshot(), innerTube.sessionFlow.value)
        }
    }

    @Test
    fun mediaContentLengthRejectsUntrustedHostsWithoutSendingHeaders() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube = InnerTube(HttpClient(engine))

            assertNull(
                innerTube.mediaContentLength(
                    "https://example.com/videoplayback",
                    mapOf(HttpHeaders.Authorization to "Bearer secret"),
                ),
            )
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun mediaContentLengthRejectsUrlUserInfo() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube = InnerTube(HttpClient(engine))

            assertNull(innerTube.mediaContentLength("https://user@rr1.googlevideo.com/videoplayback", emptyMap()))
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun mediaContentLengthForwardsOnlyMediaHeaders() =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertEquals("test-agent", request.headers[HttpHeaders.UserAgent])
                    respond("abc", headers = headersOf(HttpHeaders.ContentLength, "3"))
                }
            val innerTube = InnerTube(HttpClient(engine))

            val length =
                innerTube.mediaContentLength(
                    "https://rr1.googlevideo.com/videoplayback",
                    mapOf(
                        HttpHeaders.Authorization to "Bearer secret",
                        HttpHeaders.UserAgent to "test-agent",
                    ),
                )

            assertEquals(3L, length)
        }

    @Test
    fun uploadRejectsUntrustedSessionUrlBeforeSendingCredentials() =
        runBlocking {
            val engine =
                MockEngine {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("X-Goog-Upload-URL", "https://example.com/upload/session"),
                    )
                }
            val innerTube = InnerTube(HttpClient(engine)).also { it.cookie = "SAPISID=secret" }

            assertFailsWith<IllegalArgumentException> {
                innerTube.uploadSong("track.mp3", byteArrayOf(1))
            }
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun uploadRejectsOversizedContentBeforeRequest() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube = InnerTube(HttpClient(engine))

            assertFailsWith<IllegalArgumentException> {
                innerTube.uploadSong("track.mp3", 300L * 1024 * 1024) { ByteReadChannel(byteArrayOf(1)) }
            }
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun uploadStreamsKnownLengthContent() =
        runBlocking {
            val content = byteArrayOf(1, 2, 3)
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    if (requests == 1) {
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf("X-Goog-Upload-URL", "https://upload.youtube.com/upload/session"),
                        )
                    } else {
                        val body = request.body as OutgoingContent.ReadChannelContent
                        assertEquals(content.size.toLong(), body.contentLength)
                        assertTrue(content.contentEquals(body.readFrom().toByteArray()))
                        respondOk()
                    }
                }
            val innerTube = InnerTube(HttpClient(engine))

            val response = innerTube.uploadSong("track.mp3", content.size.toLong()) { ByteReadChannel(content) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, requests)
        }

    @Test
    fun anonymousBrowsePolicySuppressesAccountCredentials() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = "SAPISID=account-secret",
                        visitorData = "visitor-data",
                        dataSyncId = "sync-secret",
                        authUser = "1",
                        useLoginForBrowse = false,
                    )
                }

            innerTube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_home")
            innerTube.next(YouTubeClient.WEB_REMIX, videoId = "video-id")
            innerTube.getQueue(YouTubeClient.WEB_REMIX, videoIds = listOf("video-id"), playlistId = null)

            assertEquals(3, engine.requestHistory.size)
            engine.requestHistory.forEach { request ->
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers["X-Goog-AuthUser"])
                val body = (request.body as io.ktor.http.content.TextContent).text
                assertFalse(body.contains("sync-secret"))
            }
        }

    @Test
    fun explicitBrowseLoginOverridesAnonymousDefault() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube =
                clientWithContentNegotiation(engine).also {
                    it.replaceSession(
                        cookie = "SAPISID=account-secret",
                        visitorData = "visitor-data",
                        dataSyncId = "sync-data",
                        authUser = "1",
                        useLoginForBrowse = false,
                    )
                }

            innerTube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_library_landing", setLogin = true)

            val request = engine.requestHistory.single()
            assertTrue(request.headers[HttpHeaders.Cookie]?.contains("SAPISID=account-secret") == true)
            assertEquals("1", request.headers["X-Goog-AuthUser"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("sync-data"))
        }

    @Test
    fun browseSendsVisitorDataWhenRegionOverrideInactive() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube = clientWithContentNegotiation(engine).also { it.visitorData = "visitor-data" }

            innerTube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_new_releases")

            val request = engine.requestHistory.single()
            assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":\"visitor-data\""))
        }

    @Test
    fun searchSendsVisitorData() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube = clientWithContentNegotiation(engine).also { it.visitorData = "visitor-data" }

            innerTube.search(YouTubeClient.WEB_REMIX, query = "test")

            val request = engine.requestHistory.single()
            assertEquals("visitor-data", request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":\"visitor-data\""))
        }

    @Test
    fun browseSuppressesVisitorDataWhenRegionOverrideActive() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube =
                clientWithContentNegotiation(engine).also {
                    it.visitorData = "visitor-data"
                    it.regionOverrideActive = true
                }

            innerTube.browse(YouTubeClient.WEB_REMIX, browseId = "FEmusic_new_releases")

            val request = engine.requestHistory.single()
            assertNull(request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":null"))
            assertFalse(body.contains("visitor-data"))
        }

    @Test
    fun searchSuppressesVisitorDataWhenRegionOverrideActive() =
        runBlocking {
            val engine = MockEngine { respondOk() }
            val innerTube =
                clientWithContentNegotiation(engine).also {
                    it.visitorData = "visitor-data"
                    it.regionOverrideActive = true
                }

            innerTube.search(YouTubeClient.WEB_REMIX, query = "test")

            val request = engine.requestHistory.single()
            assertNull(request.headers["X-Goog-Visitor-Id"])
            val body = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(body.contains("\"visitorData\":null"))
            assertFalse(body.contains("visitor-data"))
        }

    private fun clientWithContentNegotiation(engine: MockEngine): InnerTube {
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) { json() }
            }
        return InnerTube(httpClient)
    }

    private fun serviceWorkerResponse(visitorData: String): String {
        val visitorFields = MutableList<String?>(14) { null }.also { it[13] = visitorData }
        return visitorFields.joinToString(prefix = ")]}'\n\n[[\"yt.sw.adr\",null,[[[", postfix = "]]]]]") { value ->
            value?.let { "\"$it\"" } ?: "null"
        }
    }
}
