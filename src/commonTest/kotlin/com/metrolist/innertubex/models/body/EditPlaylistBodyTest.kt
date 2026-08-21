package com.metrolist.innertubex.models.body

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class EditPlaylistBodyTest {
    @Test
    fun setPlaylistDescriptionActionUsesExpectedMutationPayload() {
        val action = Action.SetPlaylistDescriptionAction("A description")

        assertEquals("ACTION_SET_PLAYLIST_DESCRIPTION", action["action"]?.jsonPrimitive?.content)
        assertEquals("A description", action["playlistDescription"]?.jsonPrimitive?.content)
    }
}
