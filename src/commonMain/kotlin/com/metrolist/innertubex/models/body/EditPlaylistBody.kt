package com.metrolist.innertubex.models.body

import com.metrolist.innertubex.models.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class EditPlaylistBody(
    val context: Context,
    val playlistId: String,
    val actions: List<JsonObject>,
)

internal object Action {
    fun AddVideoAction(addedVideoId: String): JsonObject =
        buildJsonObject {
            put("action", "ACTION_ADD_VIDEO")
            put("addedVideoId", addedVideoId)
        }

    fun MoveVideoAction(
        setVideoId: String,
        movedSetVideoIdSuccessor: String?,
    ): JsonObject =
        buildJsonObject {
            put("action", "ACTION_MOVE_VIDEO_BEFORE")
            put("setVideoId", setVideoId)
            put("movedSetVideoIdSuccessor", movedSetVideoIdSuccessor)
        }

    fun RemoveVideoAction(
        setVideoId: String,
        removedVideoId: String,
    ): JsonObject =
        buildJsonObject {
            put("action", "ACTION_REMOVE_VIDEO")
            put("setVideoId", setVideoId)
            put("removedVideoId", removedVideoId)
        }

    fun RenamePlaylistAction(playlistName: String): JsonObject =
        buildJsonObject {
            put("action", "ACTION_SET_PLAYLIST_NAME")
            put("playlistName", playlistName)
        }

    fun SetPlaylistDescriptionAction(playlistDescription: String): JsonObject =
        buildJsonObject {
            put("action", "ACTION_SET_PLAYLIST_DESCRIPTION")
            put("playlistDescription", playlistDescription)
        }
}
