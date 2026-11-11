package com.example.musicapp.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.example.musicapp.model.Playlist
import com.example.musicapp.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object MusicRepository {
    private const val PREFS_NAME = "aurora_music_prefs"
    private const val KEY_FAVORITES = "favorite_song_ids"
    private const val KEY_DELETED = "deleted_song_ids"
    private const val KEY_CUSTOM_ORDER = "custom_order_ids"
    private const val KEY_PLAYLISTS = "playlists_json"
    private const val PREFIX_LAST_PLAYED = "last_played_"
    private const val PREFIX_PLAY_COUNT = "play_count_"

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ================= 1. 扫描本地音乐与文件夹提取 =================
    fun scanAudioFiles(context: Context): List<Song> {
        val songList = mutableListOf<Song>()
        val minDurationMs = 60000L // 排除 60 秒以内的杂音/通知铃声
        val deletedIds = getDeletedSongIds(context)

        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED
            )

            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 " +
                    "AND ${MediaStore.Audio.Media.DURATION} >= ? " +
                    "AND ${MediaStore.Audio.Media.IS_RINGTONE} == 0 " +
                    "AND ${MediaStore.Audio.Media.IS_ALARM} == 0 " +
                    "AND ${MediaStore.Audio.Media.IS_NOTIFICATION} == 0"

            val prefs = getPrefs(context)

            context.contentResolver.query(
                collection, projection, selection, arrayOf(minDurationMs.toString()), "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    if (id in deletedIds) continue // 已删除/忽略的过滤掉

                    val path = cursor.getString(dataCol)?.lowercase() ?: ""
                    // 深度路径过滤：排除常见微信/QQ/录音机文件夹
                    val isExcluded = path.contains("record") || path.contains("recorder") ||
                            path.contains("whatsapp") || path.contains("telegram") ||
                            path.contains("call_rec") || path.contains("voice") ||
                            path.contains("sound_recorder") || path.contains("tencent")

                    if (isExcluded) continue

                    val rawPath = cursor.getString(dataCol) ?: ""
                    val duration = cursor.getLong(durCol)
                    val rawTitle = cursor.getString(titleCol) ?: cursor.getString(nameCol) ?: "未知歌曲"
                    val rawArtist = cursor.getString(artistCol) ?: "未知歌手"
                    val rawAlbum = if (albumCol != -1) cursor.getString(albumCol) ?: "未知专辑" else "未知专辑"
                    val rawSize = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    val fileSize = if (rawSize > 0L) rawSize else try { File(rawPath).length() } catch (e: Exception) { 0L }
                    val dateAdded = cursor.getLong(dateAddedCol)

                    // 提取所属文件夹名称
                    val folderName = try {
                        val file = File(rawPath)
                        file.parentFile?.name ?: "内部存储"
                    } catch (e: Exception) {
                        "默认目录"
                    }

                    // 检查是否有用户自定义的资料与封面
                    val customTitle = prefs.getString("custom_title_$id", null)
                    val customArtist = prefs.getString("custom_artist_$id", null)
                    val customAlbum = prefs.getString("custom_album_$id", null)
                    val customCover = prefs.getString("custom_cover_$id", null)?.takeIf { File(it).exists() }

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val calculatedKbps = if (duration > 0 && fileSize > 0) (fileSize * 8L / duration).toInt() else 0
                    songList.add(
                        Song(
                            id = id,
                            title = customTitle ?: rawTitle,
                            artist = customArtist ?: rawArtist,
                            uri = contentUri,
                            duration = duration,
                            dateAdded = dateAdded,
                            folderName = folderName,
                            path = rawPath,
                            album = customAlbum ?: rawAlbum,
                            size = fileSize,
                            customCoverPath = customCover,
                            kbps = calculatedKbps
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return songList
    }

    // 保存自定义歌曲资料 (Title, Artist, Album, Custom Cover)
    fun saveSongInfo(
        context: Context,
        songId: Long,
        title: String,
        artist: String,
        album: String,
        customCoverPath: String?
    ) {
        val prefs = getPrefs(context)
        prefs.edit().apply {
            putString("custom_title_$songId", title.trim())
            putString("custom_artist_$songId", artist.trim())
            putString("custom_album_$songId", album.trim())
            if (!customCoverPath.isNullOrBlank()) {
                putString("custom_cover_$songId", customCoverPath)
            } else {
                remove("custom_cover_$songId")
            }
            apply()
        }
    }

    // 保存自定义封面图片文件到应用内部存储
    fun saveCoverImage(context: Context, songId: Long, uri: android.net.Uri): String? {
        return try {
            val coverDir = File(context.filesDir, "covers").apply { mkdirs() }
            val destFile = File(coverDir, "cover_${songId}_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 移除自定义封面
    fun removeCoverImage(context: Context, songId: Long) {
        val prefs = getPrefs(context)
        val oldPath = prefs.getString("custom_cover_$songId", null)
        if (!oldPath.isNullOrEmpty()) {
            try {
                File(oldPath).delete()
            } catch (_: Exception) {}
        }
        prefs.edit().remove("custom_cover_$songId").apply()
    }

    // ================= 2. 收藏功能 (Favorites) =================
    fun getFavoriteIds(context: Context): Set<Long> {
        val raw = getPrefs(context).getString(KEY_FAVORITES, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    fun toggleFavorite(context: Context, songId: Long): Boolean {
        val current = getFavoriteIds(context).toMutableSet()
        val isNowFav = if (current.contains(songId)) {
            current.remove(songId)
            false
        } else {
            current.add(songId)
            true
        }
        getPrefs(context).edit().putString(KEY_FAVORITES, current.joinToString(",")).apply()
        return isNowFav
    }

    // ================= 3. 播放历史与次数 (Play Count & Timestamp) =================
    fun recordSongPlayed(context: Context, songId: Long) {
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val currentCount = prefs.getInt("$PREFIX_PLAY_COUNT$songId", 0)
        prefs.edit()
            .putLong("$PREFIX_LAST_PLAYED$songId", now)
            .putInt("$PREFIX_PLAY_COUNT$songId", currentCount + 1)
            .apply()
    }

    fun getPlayCount(context: Context, songId: Long): Int {
        return getPrefs(context).getInt("$PREFIX_PLAY_COUNT$songId", 0)
    }

    fun getAllPlayCounts(context: Context): Map<Long, Int> {
        val prefs = getPrefs(context)
        val result = mutableMapOf<Long, Int>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX_PLAY_COUNT) && value is Int) {
                val id = key.removePrefix(PREFIX_PLAY_COUNT).toLongOrNull()
                if (id != null) result[id] = value
            }
        }
        return result
    }

    fun getLastPlayedTime(context: Context, songId: Long): Long {
        return getPrefs(context).getLong("$PREFIX_LAST_PLAYED$songId", 0L)
    }

    // ================= 4. 删除 / 隐藏功能 (Delete Song) =================
    fun getDeletedSongIds(context: Context): Set<Long> {
        val raw = getPrefs(context).getString(KEY_DELETED, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    fun deleteSong(context: Context, songId: Long) {
        val set = getDeletedSongIds(context).toMutableSet()
        set.add(songId)
        getPrefs(context).edit().putString(KEY_DELETED, set.joinToString(",")).apply()
    }

    // ================= 5. 歌单管理 (Playlists) =================
    fun getPlaylists(context: Context): List<Playlist> {
        val jsonStr = getPrefs(context).getString(KEY_PLAYLISTS, "") ?: ""
        if (jsonStr.isEmpty()) return emptyList()
        val list = mutableListOf<Playlist>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                val songIdsArr = obj.getJSONArray("songIds")
                val songIds = mutableListOf<Long>()
                for (j in 0 until songIdsArr.length()) {
                    songIds.add(songIdsArr.getLong(j))
                }
                list.add(Playlist(id, name, createdAt, songIds))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun savePlaylists(context: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("createdAt", p.createdAt)
            val songsArr = JSONArray()
            p.songIds.forEach { songsArr.put(it) }
            obj.put("songIds", songsArr)
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    fun createPlaylist(context: Context, name: String): Playlist {
        val playlists = getPlaylists(context).toMutableList()
        val newPlaylist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis(),
            songIds = emptyList()
        )
        playlists.add(newPlaylist)
        savePlaylists(context, playlists)
        return newPlaylist
    }

    fun addSongToPlaylist(context: Context, playlistId: String, songId: Long): Boolean {
        val playlists = getPlaylists(context).toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index != -1) {
            val target = playlists[index]
            if (!target.songIds.contains(songId)) {
                playlists[index] = target.copy(songIds = target.songIds + songId)
                savePlaylists(context, playlists)
                return true
            }
        }
        return false
    }

    fun removeSongFromPlaylist(context: Context, playlistId: String, songId: Long) {
        val playlists = getPlaylists(context).toMutableList()
        val index = playlists.indexOfFirst { it.id == playlistId }
        if (index != -1) {
            val target = playlists[index]
            playlists[index] = target.copy(songIds = target.songIds - songId)
            savePlaylists(context, playlists)
        }
    }

    fun deletePlaylist(context: Context, playlistId: String) {
        val playlists = getPlaylists(context).filterNot { it.id == playlistId }
        savePlaylists(context, playlists)
    }

    // ================= 6. 自定义排序 =================
    fun getCustomOrder(context: Context): List<Long> {
        val raw = getPrefs(context).getString(KEY_CUSTOM_ORDER, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    fun saveCustomOrder(context: Context, order: List<Long>) {
        getPrefs(context).edit().putString(KEY_CUSTOM_ORDER, order.joinToString(",")).apply()
    }
}
