package com.example.musicapp.model

import android.net.Uri

// 歌曲数据模型 (包含文件夹、专辑、大小、比特率及自定义封面)
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val duration: Long,
    val dateAdded: Long = 0L,         // 添加时间戳 (秒)
    val folderName: String = "本地音乐", // 所属文件夹名称
    val path: String = "",            // 文件系统绝对路径
    val album: String = "未知专辑",     // 所属专辑
    val size: Long = 0L,              // 文件大小 (字节)
    val customCoverPath: String? = null, // 自定义封面图片本地绝对路径
    val kbps: Int = 0                 // 音频比特率 (kbps)
)

// 音质检测与徽章展示
val Song.isLossless: Boolean
    get() {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in listOf("flac", "wav", "ape", "alac", "aiff")
    }

val Song.audioQualityBadge: String
    get() {
        if (isLossless) return "SQ 无损"
        val actualKbps = if (kbps > 0) kbps else if (duration > 0 && size > 0) (size * 8L / duration).toInt() else 0
        return when {
            actualKbps >= 300 -> "HQ 320K"
            actualKbps >= 235 -> "HQ 256K"
            actualKbps >= 170 -> "HQ 192K"
            actualKbps >= 110 -> "128K"
            actualKbps > 0 -> "${actualKbps}K"
            else -> "MP3"
        }
    }

// 歌单模型
data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songIds: List<Long> = emptyList()
)

// 排序选项
enum class SortType(val displayName: String, val icon: String) {
    DATE_ADDED("添加时间", "📅"),
    PLAY_COUNT("播放次数", "🔥"),
    TITLE("字母 A-Z", "🔤"),
    ARTIST("歌手名称", "🎤"),
    CUSTOM("自定义排序", "✨")
}

// 库分类标签
enum class LibraryTab(val title: String, val icon: String) {
    ALL("全部", "🎵"),
    FAVORITE("我喜欢", "❤️"),
    PLAYLIST("歌单", "📑"),
    FOLDER("文件夹", "📁")
}
