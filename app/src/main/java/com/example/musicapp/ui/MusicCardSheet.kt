package com.example.musicapp.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.example.musicapp.model.Song
import com.example.musicapp.model.audioQualityBadge
import com.example.musicapp.model.isLossless
import com.example.musicapp.ui.theme.*

/**
 * 沉浸式高端音乐卡片 (Music Card / Full Player Sheet)
 * 极致打磨：
 * 1. 顶部完全移除「正在播放」与「music」文件夹文字，顶栏极简通透。
 * 2. 进度条改造为 2dp 极细微波纤细轨道，告别笨重粗进度条。
 * 3. 三点菜单内仅保留纯文本（保留文字，删除所有图片图标）。
 * 4. 底部加长预留导航栏间距（fillMaxHeight 0.96f + navigationBarsPadding + 42dp）。
 * 5. 音乐时长保持 9.5sp 纤细精致排版。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicCardSheet(
    song: Song,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    isFavorite: Boolean,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    sleepTimerSeconds: Int,
    isSleepTimerAfterSong: Boolean,
    onDismiss: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onShowSongInfo: () -> Unit,
    onShare: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF09110D),
        scrimColor = Color.Black.copy(alpha = 0.75f),
        dragHandle = null,
        modifier = Modifier.fillMaxHeight(0.96f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 10.dp, bottom = 42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. 顶部控制栏 (左侧下拉收起按键、右侧三点菜单，去除多余的“正在播放”与“music”)
            var showMoreMenu by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "收起卡片",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Box {
                    IconButton(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "更多选项",
                            tint = TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // 纯文本三点菜单 (保留文字，去除图片图标)
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        modifier = Modifier
                            .background(SurfaceElevated)
                            .border(BorderStroke(1.dp, CardBorderGlow), RoundedCornerShape(12.dp))
                    ) {
                        // 1. 睡眠定时
                        val sleepTimerLabel = when {
                            isSleepTimerAfterSong -> "睡眠定时 (🌙 播完停止)"
                            sleepTimerSeconds > 0 -> {
                                val m = sleepTimerSeconds / 60
                                val s = sleepTimerSeconds % 60
                                "睡眠定时 (🌙 %02d:%02d)".format(m, s)
                            }
                            else -> "睡眠定时"
                        }
                        DropdownMenuItem(
                            text = { Text(sleepTimerLabel, color = TextPrimary, fontSize = 13.5.sp) },
                            onClick = {
                                showMoreMenu = false
                                onOpenSleepTimer()
                            }
                        )

                        // 2. 分享歌曲
                        DropdownMenuItem(
                            text = { Text("分享歌曲", color = TextPrimary, fontSize = 13.5.sp) },
                            onClick = {
                                showMoreMenu = false
                                onShare()
                            }
                        )

                        // 3. 歌曲详情与封面修改
                        DropdownMenuItem(
                            text = { Text("歌曲详情与封面", color = TextPrimary, fontSize = 13.5.sp) },
                            onClick = {
                                showMoreMenu = false
                                onShowSongInfo()
                            }
                        )
                    }
                }
            }

            // 2. 超大艺术封面卡片 (Big Album Artwork with Ambient Glow)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f)
                    .shadow(32.dp, RoundedCornerShape(26.dp), spotColor = EmeraldPrimary.copy(alpha = 0.28f))
                    .border(BorderStroke(1.2.dp, CardBorderGlow), RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                SongCoverView(
                    song = song,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(26.dp),
                    isPlayingNow = false,
                    placeholderIcon = Icons.Rounded.Album
                )
            }

            // 3. 歌曲资料与专属高解析音质徽章
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        // 歌名：过长自动走马灯平滑滚动播放效果
                        Text(
                            text = song.title,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    initialDelayMillis = 1500,
                                    repeatDelayMillis = 1500
                                )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${song.artist} · ${song.album}",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 名字和爱心拉开足够的舒展空间，不再紧挨
                    Spacer(modifier = Modifier.width(18.dp))

                    // 极简现代红心收藏按钮
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "收藏歌曲",
                            tint = if (isFavorite) Color(0xFFFF4757) else TextSecondary.copy(alpha = 0.65f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 专属高解析音质徽章栏 (纯净高雅)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val quality = song.audioQualityBadge
                    val lossless = song.isLossless
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (lossless) Color(0xFF2C2106) else Color(0xFF0F2B1D),
                        border = BorderStroke(
                            0.8.dp,
                            if (lossless) Color(0xFFFFC107).copy(alpha = 0.85f) else EmeraldPrimary.copy(alpha = 0.75f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (lossless) "✨ " else "🎧 ",
                                fontSize = 9.sp
                            )
                            Text(
                                text = quality,
                                color = if (lossless) Color(0xFFFFD54F) else EmeraldPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 音频文件格式 (MP3, FLAC, WAV 等)
                    val ext = song.path.substringAfterLast('.', "MP3").uppercase()
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF131D18),
                        border = BorderStroke(0.6.dp, Color(0xFF23382D))
                    ) {
                        Text(
                            text = ext,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }
                }
            }

            // 4. 极致纤细微线进度条 (2dp 极细音轨) 与精美时间排版
            Column(modifier = Modifier.fillMaxWidth()) {
                var sliderDragFraction by remember { mutableStateOf<Float?>(null) }
                val currentFraction = if (totalDurationMs > 0) {
                    (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                val effectiveFraction = sliderDragFraction ?: currentFraction

                SleekHairlineSeekBar(
                    fraction = effectiveFraction,
                    onSeekFractionChange = { sliderDragFraction = it },
                    onSeekFinished = {
                        sliderDragFraction?.let { frac ->
                            val targetMs = (frac * totalDurationMs).toLong()
                            onSeekTo(targetMs)
                            sliderDragFraction = null
                        }
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayCurrentMs = if (sliderDragFraction != null) {
                        (sliderDragFraction!! * totalDurationMs).toLong()
                    } else currentPositionMs
                    Text(
                        text = formatTime(displayCurrentMs),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextSecondary.copy(alpha = 0.5f),
                        letterSpacing = 0.3.sp
                    )
                    Text(
                        text = formatTime(totalDurationMs),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextSecondary.copy(alpha = 0.5f),
                        letterSpacing = 0.3.sp
                    )
                }
            }

            // 5. 核心播放控制排 (混合模式切换、上一首、主播放/暂停、下一首、快捷详情)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 1. 左侧：播放模式切换键 (列表循环 🔁 / 单曲循环 🔂 / 随机播放 🔀 / 顺序播放 ➡️)
                val (modeIcon, modeTint, modeDesc) = when {
                    isShuffleEnabled -> Triple(Icons.Rounded.Shuffle, EmeraldPrimary, "随机播放")
                    repeatMode == Player.REPEAT_MODE_ONE -> Triple(Icons.Rounded.RepeatOne, EmeraldPrimary, "单曲循环")
                    repeatMode == Player.REPEAT_MODE_ALL -> Triple(Icons.Rounded.Repeat, EmeraldPrimary, "列表循环")
                    else -> Triple(Icons.AutoMirrored.Rounded.PlaylistPlay, TextSecondary.copy(alpha = 0.55f), "顺序播放")
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    IconButton(
                        onClick = onCyclePlaybackMode,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = modeIcon,
                            contentDescription = modeDesc,
                            tint = modeTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // 2. 中央三大核心播放控制键 (严格屏幕正中对称)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = onPrevious,
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "上一首",
                            tint = TextPrimary,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(EmeraldPrimary, EmeraldGlow)))
                            .shadow(18.dp, CircleShape, spotColor = EmeraldPrimary.copy(alpha = 0.6f))
                            .clickable(onClick = onPlayPause),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(
                        onClick = onNext,
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "下一首",
                            tint = TextPrimary,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                // 3. 右侧占位留白 (完全移除右下角按钮，保持中央主播放区绝对居中)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * 极致纤细微线进度条 (2dp 极细轨道，杜绝厚重笨拙感)
 */
@Composable
private fun SleekHairlineSeekBar(
    fraction: Float,
    onSeekFractionChange: (Float) -> Unit,
    onSeekFinished: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeekFractionChange(frac)
                    onSeekFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeekFractionChange(frac)
                    },
                    onDragEnd = { onSeekFinished() },
                    onDragCancel = { onSeekFinished() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSeekFractionChange(frac)
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val totalWidth = maxWidth
        val safeFraction = fraction.coerceIn(0f, 1f)

        // 1. 底轨 (4.dp 舒适黄金比例现代音轨)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1B2E24))
        )

        // 2. 播放高亮进度条 (4.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth(safeFraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(EmeraldGlow, EmeraldPrimary)))
        )

        // 3. 灵动指示滑块圆点 (11.dp，带精致微光阴影)
        Box(
            modifier = Modifier
                .offset(x = ((totalWidth - 11.dp) * safeFraction))
                .size(11.dp)
                .clip(CircleShape)
                .background(EmeraldPrimary)
                .shadow(4.dp, CircleShape, spotColor = EmeraldPrimary)
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
