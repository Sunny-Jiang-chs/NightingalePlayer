package com.myp.sleepplayer.playback

import android.content.SharedPreferences
import android.net.Uri
import com.myp.sleepplayer.media.MediaEntry
import com.myp.sleepplayer.media.MediaType
import com.myp.sleepplayer.media.SubtitleFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

internal data class SavedPlaylist(
    val id: String,
    val name: String,
    val entries: List<MediaEntry>,
    val mode: PlaybackMode
)

internal class PlaybackQueueStore(private val preferences: SharedPreferences) {
    private var namedPlaylistsLoaded = false
    private var namedPlaylistsRaw: String? = null
    private var namedPlaylistsCache: List<SavedPlaylist> = emptyList()
    private val entryCaches = mutableMapOf<String, EntryCache>()

    private class EntryCache(
        val raw: String?,
        var decoded: List<MediaEntry>?,
        val savedEntries: List<MediaEntry>? = null
    )

    fun mode(): PlaybackMode = PlaybackMode.fromRepeatMode(preferences.getInt(KEY_MODE, 0))

    fun saveMode(mode: PlaybackMode) {
        preferences.edit().putInt(KEY_MODE, mode.repeatMode).apply()
    }

    fun source(): PlaybackSource = PlaybackSource.restore(
        storedString(KEY_SOURCE), directoryPlayback() != null, playlist() != null
    )

    fun saveSource(source: PlaybackSource) {
        preferences.edit().putString(KEY_SOURCE, source.name).apply()
    }

    fun directoryMode(): DirectoryRepeatMode =
        DirectoryRepeatMode.fromRepeatMode(preferences.getInt(KEY_DIRECTORY_MODE, 0))

    fun saveDirectoryMode(mode: DirectoryRepeatMode) {
        preferences.edit().putInt(KEY_DIRECTORY_MODE, mode.repeatMode).apply()
    }

    fun activeRepeatMode(): Int =
        if (source() == PlaybackSource.PLAYLIST) mode().repeatMode else directoryMode().repeatMode

    fun saveActiveRepeatMode(repeatMode: Int) {
        if (source() == PlaybackSource.PLAYLIST) saveMode(PlaybackMode.fromRepeatMode(repeatMode))
        else saveDirectoryMode(DirectoryRepeatMode.fromRepeatMode(repeatMode))
    }

    fun playlist(): List<MediaEntry>? = readEntries(KEY_PLAYLIST).takeIf { it.isNotEmpty() }

    fun savePlaylist(entries: List<MediaEntry>?) {
        saveEntries(KEY_PLAYLIST, entries, initializePlaylist = true)
    }

    fun directoryPlayback(): List<MediaEntry>? =
        readEntries(KEY_DIRECTORY_PLAYBACK).takeIf { it.isNotEmpty() }

    fun saveDirectoryPlayback(entries: List<MediaEntry>?) {
        saveEntries(KEY_DIRECTORY_PLAYBACK, entries)
    }

    private fun entryCache(key: String): EntryCache {
        val raw = storedString(key)
        return entryCaches[key]?.takeIf { it.raw == raw }
            ?: EntryCache(raw, if (raw == null) emptyList() else null).also { entryCaches[key] = it }
    }

    private fun readEntries(key: String): List<MediaEntry> = decodedEntries(entryCache(key))

    private fun decodedEntries(cache: EntryCache): List<MediaEntry> = cache.decoded ?: try {
        cache.raw?.let(::JSONArray)?.let(::decodeEntries).orEmpty()
    } catch (_: JSONException) {
        emptyList()
    }.also { cache.decoded = it }

    private fun saveEntries(key: String, entries: List<MediaEntry>?, initializePlaylist: Boolean = false) {
        val items = validEntries(entries.orEmpty())
        val previous = entryCache(key)
        val changed = (previous.savedEntries ?: decodedEntries(previous)) != items ||
            (items.isEmpty() && preferences.contains(key))
        val needsInitialization = initializePlaylist && !preferences.contains(KEY_PLAYLIST_INITIALIZED)
        if (!changed && !needsInitialization) return
        val raw = if (!changed) previous.raw
        else items.takeIf { it.isNotEmpty() }?.let { encodeEntries(it).toString() }
        val editor = preferences.edit()
        if (needsInitialization) editor.putBoolean(KEY_PLAYLIST_INITIALIZED, true)
        if (changed) {
            if (raw == null) editor.remove(key) else editor.putString(key, raw)
        }
        editor.apply()
        // Keep the supplied snapshot for cheap repeated saves; decode stored normalization only on read.
        entryCaches[key] = EntryCache(raw, if (changed) null else previous.decoded, items)
    }

    fun savedPlaylists(): List<SavedPlaylist> {
        val raw = storedString(KEY_NAMED_PLAYLISTS)
        if (namedPlaylistsLoaded && namedPlaylistsRaw == raw) return namedPlaylistsCache
        val array = try {
            raw?.let(::JSONArray)
        } catch (_: JSONException) {
            null
        }
        val ids = mutableSetOf<String>()
        var migratedIds = false
        val saved = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array?.optJSONObject(index) ?: return@mapNotNull null
            val name = item.nonBlankString("name")?.trim() ?: return@mapNotNull null
            val storedId = item.nonBlankString("id")
            val id = storedId?.trim()?.takeIf { ids.add(it) } ?: newPlaylistId(ids).also {
                ids.add(it)
                migratedIds = true
            }
            if (id != storedId) migratedIds = true
            val entries = item.optJSONArray("entries")?.let(::decodeEntries).orEmpty()
            val mode = PlaybackMode.fromRepeatMode(item.optInt("repeatMode", 0))
            SavedPlaylist(id, name, entries, mode)
        }
        if (migratedIds) persistSavedPlaylists(saved)
        else cacheSavedPlaylists(raw, saved)
        return namedPlaylistsCache
    }

    fun hasSavedPlaylists(): Boolean = savedPlaylists().isNotEmpty()

    fun playlist(id: String): SavedPlaylist? = savedPlaylists().firstOrNull { it.id == id }

    fun createPlaylist(name: String, entries: List<MediaEntry>, mode: PlaybackMode): SavedPlaylist? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        val saved = savedPlaylists()
        val playlist = SavedPlaylist(
            newPlaylistId(saved.mapTo(mutableSetOf()) { it.id }),
            trimmedName,
            validEntries(entries),
            mode
        )
        persistSavedPlaylists(saved + playlist)
        return playlist
    }

    fun updatePlaylist(id: String, entries: List<MediaEntry>, mode: PlaybackMode): Boolean {
        val saved = savedPlaylists().toMutableList()
        val index = saved.indexOfFirst { it.id == id }
        if (index < 0) return false
        val updated = saved[index].copy(entries = validEntries(entries), mode = mode)
        if (saved[index] != updated) {
            saved[index] = updated
            persistSavedPlaylists(saved)
        }
        return true
    }

    fun renamePlaylist(id: String, name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return false
        val saved = savedPlaylists().toMutableList()
        val index = saved.indexOfFirst { it.id == id }
        if (index < 0) return false
        if (saved[index].name != trimmedName) {
            saved[index] = saved[index].copy(name = trimmedName)
            persistSavedPlaylists(saved)
        }
        return true
    }

    fun deletePlaylist(id: String): Boolean {
        val saved = savedPlaylists()
        val remaining = saved.filterNot { it.id == id }
        if (remaining.size == saved.size) return false
        persistSavedPlaylists(remaining, clearActiveId = storedString(KEY_ACTIVE_PLAYLIST_ID) == id)
        return true
    }

    fun activePlaylistId(): String? {
        val id = storedString(KEY_ACTIVE_PLAYLIST_ID) ?: return null
        if (playlist(id) != null) return id
        preferences.edit().remove(KEY_ACTIVE_PLAYLIST_ID).apply()
        return null
    }

    fun setActivePlaylistId(id: String?) {
        val validId = id?.takeIf { playlist(it) != null }
        if (storedString(KEY_ACTIVE_PLAYLIST_ID) == validId) return
        val editor = preferences.edit()
        if (validId == null) editor.remove(KEY_ACTIVE_PLAYLIST_ID)
        else editor.putString(KEY_ACTIVE_PLAYLIST_ID, validId)
        editor.apply()
    }

    // Kept for callers of the original name-based API. New management uses stable IDs.
    fun saveNamedPlaylist(name: String, entries: List<MediaEntry>, mode: PlaybackMode): Boolean {
        val trimmedName = name.trim()
        val items = validEntries(entries)
        if (trimmedName.isEmpty() || items.isEmpty()) return false
        val existing = savedPlaylists().firstOrNull { it.name == trimmedName }
        return if (existing == null) createPlaylist(trimmedName, items, mode) != null
        else updatePlaylist(existing.id, items, mode)
    }

    private fun persistSavedPlaylists(saved: List<SavedPlaylist>, clearActiveId: Boolean = false) {
        val raw = JSONArray().apply {
            saved.forEach { playlist ->
                put(JSONObject().apply {
                    put("id", playlist.id)
                    put("name", playlist.name)
                    put("entries", encodeEntries(playlist.entries))
                    put("repeatMode", playlist.mode.repeatMode)
                })
            }
        }.toString()
        val editor = preferences.edit().putString(KEY_NAMED_PLAYLISTS, raw)
        if (clearActiveId) editor.remove(KEY_ACTIVE_PLAYLIST_ID)
        editor.apply()
        cacheSavedPlaylists(raw, saved.toList())
    }

    private fun cacheSavedPlaylists(raw: String?, saved: List<SavedPlaylist>) {
        namedPlaylistsLoaded = true
        namedPlaylistsRaw = raw
        namedPlaylistsCache = saved
    }

    private fun newPlaylistId(existingIds: Set<String>): String {
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (id in existingIds)
        return id
    }

    private fun storedString(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    private fun decodeEntries(array: JSONArray): List<MediaEntry> {
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.nonBlankString("name") ?: return@mapNotNull null
            val uri = item.storedUri() ?: return@mapNotNull null
            val type = MediaType.values().firstOrNull { it.name == item.opt("type") }
                ?: return@mapNotNull null
            val subtitles = item.optJSONArray("subtitles")?.let { subtitleArray ->
                (0 until subtitleArray.length()).mapNotNull subtitle@{ subtitleIndex ->
                    val subtitle = subtitleArray.optJSONObject(subtitleIndex) ?: return@subtitle null
                    val subtitleName = subtitle.nonBlankString("name") ?: return@subtitle null
                    val subtitleUri = subtitle.storedUri() ?: return@subtitle null
                    val times = subtitle.optJSONArray("cueStartTimesMs")?.let { cues ->
                        (0 until cues.length()).mapNotNull { cueIndex ->
                            when (val value = cues.opt(cueIndex)) {
                                is Long -> value.takeIf { it >= 0 }
                                is Int -> value.toLong().takeIf { it >= 0 }
                                else -> null
                            }
                        }.distinct().sorted()
                    }.orEmpty()
                    SubtitleFile(subtitleName, subtitleUri, times)
                }.distinctBy { it.uri.toString() }
            }.orEmpty()
            MediaEntry(name, uri, type, subtitles)
        }.distinctBy { it.uri.toString() }
    }

    private fun validEntries(entries: List<MediaEntry>): List<MediaEntry> =
        entries.filter { it.name.isNotBlank() && it.uri.isAbsolute }
            .distinctBy { it.uri.toString() }

    private fun encodeEntries(entries: List<MediaEntry>): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("name", entry.name)
                put("uri", entry.uri.toString())
                put("type", entry.type.name)
                put("subtitles", JSONArray().apply {
                    entry.subtitles.filter { it.name.isNotBlank() && it.uri.isAbsolute }
                        .distinctBy { it.uri.toString() }.forEach { subtitle ->
                            put(JSONObject().apply {
                                put("name", subtitle.name)
                                put("uri", subtitle.uri.toString())
                                put("cueStartTimesMs", JSONArray(
                                    subtitle.cueStartTimesMs.filter { it >= 0 }.distinct().sorted()
                                ))
                            })
                        }
                })
            })
        }
    }

    fun migrateLegacySelection(source: String, available: List<MediaEntry>): List<MediaEntry>? {
        if (preferences.contains(KEY_PLAYLIST_INITIALIZED) || preferences.contains(KEY_PLAYLIST)) {
            return playlist()
        }
        val byUri = available.associateBy { it.uri.toString() }
        val legacy = readArray("$KEY_SELECTION$source")
        val entries = legacy?.let { array ->
            (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.let(byUri::get) }
        }
        if (!entries.isNullOrEmpty()) savePlaylist(entries)
        return playlist()
    }

    private fun readArray(key: String): JSONArray? = try {
        preferences.getString(key, null)?.let(::JSONArray)
    } catch (_: JSONException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    private fun JSONObject.nonBlankString(key: String): String? =
        (opt(key) as? String)?.takeIf { it.isNotBlank() }

    private fun JSONObject.storedUri(): Uri? =
        nonBlankString("uri")?.let(Uri::parse)?.takeIf { it.isAbsolute }

    private companion object {
        const val KEY_MODE = "repeat_mode"
        const val KEY_SELECTION = "selection:"
        const val KEY_PLAYLIST = "playlist_entries"
        const val KEY_PLAYLIST_INITIALIZED = "playlist_initialized"
        const val KEY_NAMED_PLAYLISTS = "named_playlists"
        const val KEY_ACTIVE_PLAYLIST_ID = "active_playlist_id"
        const val KEY_DIRECTORY_PLAYBACK = "directory_playback"
        const val KEY_SOURCE = "playback_source"
        const val KEY_DIRECTORY_MODE = "directory_repeat_mode"
    }
}
