package com.example.musicapp.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.musicapp.data.MusicRepository
import com.example.musicapp.model.Song
import com.example.musicapp.model.audioQualityBadge
import com.example.musicapp.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoDialog(
    song: Song,
    onDismiss: () -> Unit,
    onSongUpdated: (Song) -> Unit
) {
    val context = LocalContext.current

    var editTitle by remember { mutableStateOf(song.title) }
    var editArtist by remember { mutableStateOf(song.artist) }
    var editAlbum by remember { mutableStateOf(song.album) }
    var currentCoverPath by remember { mutableStateOf(song.customCoverPath) }

    var previewBitmap by remember(song.id, currentCoverPath) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(song.id, currentCoverPath) {
        withContext(Dispatchers.IO) {
            val dummySong = song.copy(customCoverPath = currentCoverPath)
            val bm = CoverHelper.loadBitmap(context, dummySong, sampleSize = 1)
            withContext(Dispatchers.Main) {
                previewBitmap = bm
            }
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val savedPath = MusicRepository.saveCoverImage(context, song.id, uri)
            if (savedPath != null) {
                currentCoverPath = savedPath
                CoverHelper.clearCacheForSong(song.id)
                Toast.makeText(context, "封面图片已选中 🖼️", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(22.dp))
                .background(SurfaceElevated)
                .border(BorderStroke(1.2.dp, CardBorderGlow), RoundedCornerShape(22.dp))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 顶部标题行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "歌曲详情与资料",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 封面预览与自定义封面操作区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF142E20), Color(0xFF1D3B2B))))
                            .border(BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.5f)), RoundedCornerShape(16.dp))
                            .clickable { pickImageLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap!!.asImageBitmap(),
                                contentDescription = "封面预览",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = EmeraldLight,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("默认封面", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (currentCoverPath != null) "已设置自定义封面 ✨" else "当前使用默认/内置封面",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (currentCoverPath != null) EmeraldPrimary else TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击可选择手机相册中的精美图片替换为专属封面",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 更换封面按钮
                            Surface(
                                onClick = { pickImageLauncher.launch("image/*") },
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF153324),
                                border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("自选封面", fontSize = 11.sp, color = EmeraldPrimary, fontWeight = FontWeight.Medium)
                                }
                            }

                            // 恢复默认按钮
                            if (currentCoverPath != null) {
                                Surface(
                                    onClick = {
                                        MusicRepository.removeCoverImage(context, song.id)
                                        currentCoverPath = null
                                        CoverHelper.clearCacheForSong(song.id)
                                        Toast.makeText(context, "已恢复默认封面", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF2E1C1C),
                                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("恢复默认", fontSize = 11.sp, color = Color(0xFFFF5252))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 可编辑资料输入区
                Text("基本资料修改", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldLight)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = editTitle,
                    onValueChange = { editTitle = it },
                    label = { Text("歌曲名称") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = Color(0xFF243B2E),
                        focusedLabelColor = EmeraldPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = EmeraldPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = editArtist,
                    onValueChange = { editArtist = it },
                    label = { Text("歌手名称") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = Color(0xFF243B2E),
                        focusedLabelColor = EmeraldPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = EmeraldPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = editAlbum,
                    onValueChange = { editAlbum = it },
                    label = { Text("专辑名称") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = Color(0xFF243B2E),
                        focusedLabelColor = EmeraldPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = EmeraldPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 技术参数与存储路径卡片 (只读)
                Text("物理与技术参数", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldLight)
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF0B1711),
                    border = BorderStroke(1.dp, Color(0xFF1B3024)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoRow(label = "播放时长", value = formatDuration(song.duration))
                        InfoRow(label = "文件大小", value = formatFileSize(song.size))
                        InfoRow(label = "所在目录", value = song.folderName)
                        InfoRow(label = "音频格式", value = song.path.substringAfterLast('.', "未知").uppercase())
                        InfoRow(label = "音频音质", value = song.audioQualityBadge + if (song.kbps > 0) " (${song.kbps} kbps)" else "")
                        InfoRow(label = "存储路径", value = song.path.ifEmpty { song.uri.toString() }, isPath = true)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = TextSecondary, fontSize = 13.5.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val finalTitle = editTitle.ifBlank { song.title }
                            val finalArtist = editArtist.ifBlank { song.artist }
                            val finalAlbum = editAlbum.ifBlank { song.album }

                            MusicRepository.saveSongInfo(
                                context = context,
                                songId = song.id,
                                title = finalTitle,
                                artist = finalArtist,
                                album = finalAlbum,
                                customCoverPath = currentCoverPath
                            )

                            val updatedSong = song.copy(
                                title = finalTitle,
                                artist = finalArtist,
                                album = finalAlbum,
                                customCoverPath = currentCoverPath
                            )
                            onSongUpdated(updatedSong)
                            Toast.makeText(context, "歌曲资料已更新 ✨", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("保存修改", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, isPath: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            color = TextSecondary,
            modifier = Modifier.width(68.dp)
        )
        Text(
            text = value,
            fontSize = 11.5.sp,
            color = if (isPath) TextSecondary.copy(alpha = 0.8f) else TextPrimary,
            maxLines = if (isPath) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = durationMs / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "计算中..."
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) "%.2f MB".format(mb) else "%.1f KB".format(kb)
}
