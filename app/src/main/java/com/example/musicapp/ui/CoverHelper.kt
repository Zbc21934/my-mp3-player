package com.example.musicapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.musicapp.R
import com.example.musicapp.LiveEqualizerVisualizer
import com.example.musicapp.model.Song
import com.example.musicapp.ui.theme.EmeraldLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CoverHelper {
    private val memoryCache = LruCache<String, Bitmap>(60)

    fun loadBitmap(context: Context, song: Song, sampleSize: Int = 2): Bitmap? {
        val cacheKey = song.customCoverPath ?: "embedded_${song.id}"
        memoryCache.get(cacheKey)?.let { return it }

        // 1. 优先读取用户自定义封面文件
        if (!song.customCoverPath.isNullOrEmpty()) {
            val file = File(song.customCoverPath)
            if (file.exists()) {
                try {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    val bm = BitmapFactory.decodeFile(file.absolutePath, options)
                    if (bm != null) {
                        memoryCache.put(cacheKey, bm)
                        return bm
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. 尝试从音频文件读取内置嵌入式封面 (MP3/FLAC/M4A ID3 tag)
        if (song.path.isNotEmpty()) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(song.path)
                val art = retriever.embeddedPicture
                retriever.release()
                if (art != null && art.isNotEmpty()) {
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    val bm = BitmapFactory.decodeByteArray(art, 0, art.size, options)
                    if (bm != null) {
                        memoryCache.put(cacheKey, bm)
                        return bm
                    }
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun clearCacheForSong(songId: Long) {
        memoryCache.remove("embedded_${songId}")
    }
}

@Composable
fun SongCoverView(
    song: Song,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    isPlayingNow: Boolean = false,
    placeholderIcon: ImageVector = Icons.Rounded.MusicNote
) {
    val context = LocalContext.current
    var bitmap by remember(song.id, song.customCoverPath) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(song.id, song.customCoverPath) {
        withContext(Dispatchers.IO) {
            val bm = CoverHelper.loadBitmap(context, song)
            withContext(Dispatchers.Main) {
                bitmap = bm
            }
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF142E20), Color(0xFF1D3B2B)))),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.default_cover_square),
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
