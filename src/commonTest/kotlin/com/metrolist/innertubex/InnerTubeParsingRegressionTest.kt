package com.metrolist.innertubex

import com.metrolist.innertubex.models.response.PlayerResponse
import com.metrolist.innertubex.utils.YouTubeUrlParser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InnerTubeParsingRegressionTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun musicYoutubePlaylistUrlsAreNotAlwaysAlbums() {
        val parsed = YouTubeUrlParser.parse("https://music.youtube.com/playlist?list=PLabc123")

        assertTrue(parsed is YouTubeUrlParser.ParsedUrl.Playlist)
        assertEquals("PLabc123", parsed.id)
    }

    @Test
    fun musicYoutubeAlbumUrlsUseAlbumIds() {
        val playlistAlbum = YouTubeUrlParser.parse("https://music.youtube.com/playlist?list=OLAK5uy_test")
        val browseAlbum = YouTubeUrlParser.parse("https://music.youtube.com/browse/MPREtest")

        assertTrue(playlistAlbum is YouTubeUrlParser.ParsedUrl.Album)
        assertEquals("OLAK5uy_test", playlistAlbum.id)
        assertTrue(browseAlbum is YouTubeUrlParser.ParsedUrl.Album)
        assertEquals("MPREtest", browseAlbum.id)
    }

    @Test
    fun partialPlayerFormatsDoNotDropWholeResponse() {
        val response =
            json.decodeFromString<PlayerResponse>(
                """
                {
                  "playabilityStatus": { "status": "OK" },
                  "streamingData": {
                    "adaptiveFormats": [
                      { "url": "https://example.test/videoplayback" }
                    ]
                  }
                }
                """.trimIndent(),
            )

        val format = assertNotNull(response.streamingData?.adaptiveFormats?.singleOrNull())
        assertEquals(-1, format.itag)
        assertEquals("", format.mimeType)
        assertEquals(0, format.bitrate)
        assertEquals("https://example.test/videoplayback", format.url)
    }
}
