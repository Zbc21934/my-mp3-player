package com.example.musicapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.musicapp.data.MusicRepository
import com.example.musicapp.model.*
import com.example.musicapp.ui.*
import com.example.musicapp.ui.theme.*
import com.google.common.util.concurrent.MoreExecutors
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var mediaController by mutableStateOf<MediaController?>(null)
    private var hasPermissionState by mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.READ_MEDIA_AUDIO] == true ||
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        hasPermissionState = audioGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissionsToRequest = mutableListOf<String>()
        val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, audioPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(audioPermission)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        hasPermissionState = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }

        // 初始化 MediaController
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
        }, MoreExecutors.directExecutor())

        setContent {
            MusicAppTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ObsidianBg
                ) {
                    MusicPlayerScreen(
                        context = this,
                        hasPermission = hasPermissionState,
                        mediaController = mediaController
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(context: Context, hasPermission: Boolean, mediaController: MediaController?) {
    val prefs = remember { context.getSharedPreferences("music_app_prefs", Context.MODE_PRIVATE) }
    val listState = rememberLazyListState()

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var allSongs by remember { mutableStateOf(emptyList<Song>()) }
    var isLoading by remember { mutableStateOf(false) }

    // 主分类 Tab：全部、我喜欢、歌单
    var selectedTab by remember { mutableStateOf(LibraryTab.ALL) }
    var activePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var activeFolder by remember { mutableStateOf<String?>(null) }

    // 数据集合
    var favoriteIds by remember { mutableStateOf(MusicRepository.getFavoriteIds(context)) }
    var playlists by remember { mutableStateOf(MusicRepository.getPlaylists(context)) }

    // 多选模式
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf(setOf<Long>()) }

    // 设置与文件夹弹窗
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var songToAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var songForInfoDialog by remember { mutableStateOf<Song?>(null) }

    // 睡眠模式状态
    var sleepTimerSeconds by remember { mutableIntStateOf(0) }
    var isSleepTimerAfterSong by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }

    // 排序相关状态
    val savedSortName = prefs.getString("sort_type", SortType.DATE_ADDED.name) ?: SortType.DATE_ADDED.name
    var currentSortType by remember {
        mutableStateOf(
            try {
                if (savedSortName == "LAST_PLAYED") SortType.PLAY_COUNT
                else SortType.valueOf(savedSortName)
            } catch (e: Exception) {
                SortType.DATE_ADDED
            }
        )
    }
    var isAscending by remember {
        mutableStateOf(prefs.getBoolean("sort_ascending", false))
    }

    var playCounts by remember {
        mutableStateOf(MusicRepository.getAllPlayCounts(context))
    }

    var customOrderList by remember {
        mutableStateOf(MusicRepository.getCustomOrder(context))
    }
    var dragSongList by remember { mutableStateOf<List<Song>>(emptyList()) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    var draggingSongId by remember { mutableStateOf<Long?>(null) }
    var draggingOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val itemHeightPx = remember(density) { with(density) { 72.dp.toPx() } }
    val haptic = LocalHapticFeedback.current

    fun saveSortConfig(sortType: SortType, ascending: Boolean) {
        currentSortType = sortType
        isAscending = ascending
        prefs.edit()
            .putString("sort_type", sortType.name)
            .putBoolean("sort_ascending", ascending)
            .apply()
    }

    fun refreshSongs() {
        if (hasPermission) {
            allSongs = MusicRepository.scanAudioFiles(context)
            favoriteIds = MusicRepository.getFavoriteIds(context)
            playlists = MusicRepository.getPlaylists(context)
            playCounts = MusicRepository.getAllPlayCounts(context)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isLoading = true
            refreshSongs()
            isLoading = false
        }
    }

    // 播放状态监听与音乐卡片状态
    var currentPlayingUri by remember { mutableStateOf<Uri?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var showMusicCard by remember { mutableStateOf(false) }
    var isShuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_ALL) }

    DisposableEffect(mediaController) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }
            override fun onRepeatModeChanged(newRepeatMode: Int) {
                repeatMode = newRepeatMode
            }
            override fun onShuffleModeEnabledChanged(shuffle: Boolean) {
                isShuffleEnabled = shuffle
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val uri = mediaItem?.localConfiguration?.uri
                val songId = mediaItem?.mediaId?.toLongOrNull()
                val currentSong = if (songId != null) {
                    allSongs.find { it.id == songId }
                } else {
                    allSongs.find { it.uri.toString() == uri?.toString() }
                }
                currentPlayingUri = currentSong?.uri ?: uri
                currentPositionMs = 0L
                mediaController?.let {
                    totalDurationMs = it.duration.coerceAtLeast(0L)
                }
                currentSong?.let { song ->
                    MusicRepository.recordSongPlayed(context, song.id)
                    playCounts = playCounts + (song.id to (playCounts[song.id] ?: 0) + 1)
                }

                // 睡眠模式：播完当前歌曲后自动暂停
                if (isSleepTimerAfterSong) {
                    mediaController?.pause()
                    isSleepTimerAfterSong = false
                    Toast.makeText(context, "🌙 当前歌曲播放完毕，睡眠模式已自动暂停", Toast.LENGTH_LONG).show()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                mediaController?.let { controller ->
                    totalDurationMs = controller.duration.coerceAtLeast(0L)
                    // 睡眠模式播完拦截
                    if (isSleepTimerAfterSong && playbackState == Player.STATE_ENDED) {
                        controller.pause()
                        isSleepTimerAfterSong = false
                        Toast.makeText(context, "🌙 当前歌曲播放完毕，睡眠模式已自动暂停", Toast.LENGTH_LONG).show()
                        return
                    }
                    // 播完保底机制：如果播放到达末尾，自动无缝跳转下一首或循环回到开头
                    if (playbackState == Player.STATE_ENDED) {
                        if (controller.hasNextMediaItem()) {
                            controller.seekToNextMediaItem()
                            controller.play()
                        } else if (controller.mediaItemCount > 0) {
                            controller.seekToDefaultPosition(0)
                            controller.play()
                        }
                    }
                }
            }
        }

        mediaController?.let { controller ->
            controller.addListener(listener)
            isPlaying = controller.isPlaying
            currentPlayingUri = controller.currentMediaItem?.localConfiguration?.uri
            totalDurationMs = controller.duration.coerceAtLeast(0L)
            controller.repeatMode = Player.REPEAT_MODE_ALL
            repeatMode = controller.repeatMode
            isShuffleEnabled = controller.shuffleModeEnabled
        }

        onDispose {
            mediaController?.removeListener(listener)
        }
    }

    LaunchedEffect(isPlaying, mediaController) {
        while (isPlaying && mediaController != null) {
            currentPositionMs = mediaController.currentPosition.coerceAtLeast(0L)
            totalDurationMs = mediaController.duration.coerceAtLeast(0L)
            kotlinx.coroutines.delay(500)
        }
    }

    val currentPlayingSong = remember(currentPlayingUri, allSongs) {
        allSongs.find { it.uri.toString() == currentPlayingUri?.toString() }
    }

    // 基础过滤
    val tabFilteredSongs = remember(allSongs, selectedTab, favoriteIds, activePlaylist, activeFolder) {
        when {
            activeFolder != null -> allSongs.filter { it.folderName == activeFolder }
            selectedTab == LibraryTab.ALL -> allSongs
            selectedTab == LibraryTab.FAVORITE -> allSongs.filter { it.id in favoriteIds }
            selectedTab == LibraryTab.PLAYLIST && activePlaylist != null -> {
                val pIds = activePlaylist!!.songIds
                allSongs.filter { it.id in pIds }
            }
            else -> allSongs
        }
    }

    // 排序逻辑 (新增按播放次数大小排序)
    val sortedSongs = remember(tabFilteredSongs, currentSortType, isAscending, customOrderList, playCounts) {
        when (currentSortType) {
            SortType.DATE_ADDED -> {
                val list = tabFilteredSongs.sortedBy { it.dateAdded }
                if (!isAscending) list.reversed() else list
            }
            SortType.PLAY_COUNT -> {
                // 按照播放次数大小排序：默认降序 (播放次数最多排在最前)
                val list = tabFilteredSongs.sortedWith { a, b ->
                    val countA = playCounts[a.id] ?: 0
                    val countB = playCounts[b.id] ?: 0
                    if (countA != countB) {
                        countA.compareTo(countB)
                    } else {
                        a.dateAdded.compareTo(b.dateAdded)
                    }
                }
                if (!isAscending) list.reversed() else list
            }
            SortType.TITLE -> {
                val list = tabFilteredSongs.sortedBy { it.title.lowercase(Locale.getDefault()) }
                if (!isAscending) list.reversed() else list
            }
            SortType.ARTIST -> {
                val list = tabFilteredSongs.sortedBy { it.artist.lowercase(Locale.getDefault()) }
                if (!isAscending) list.reversed() else list
            }
            SortType.CUSTOM -> {
                val orderMap = customOrderList.mapIndexed { index, id -> id to index }.toMap()
                tabFilteredSongs.sortedWith { a, b ->
                    val posA = orderMap[a.id] ?: Int.MAX_VALUE
                    val posB = orderMap[b.id] ?: Int.MAX_VALUE
                    if (posA != posB) posA.compareTo(posB) else b.dateAdded.compareTo(a.dateAdded)
                }
            }
        }
    }

    val displaySongs = sortedSongs.filter {
        it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
    }

    // 排序/搜索切换自动跳顶
    LaunchedEffect(currentSortType, isAscending, selectedTab, activePlaylist, activeFolder, searchQuery) {
        if (displaySongs.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // 播歌辅助：加载当前歌曲及完整播放列表队列，实现系统原生无缝连播与锁屏切歌
    fun playSong(song: Song, playlist: List<Song> = displaySongs) {
        mediaController?.let { controller ->
            val currentIndex = playlist.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            val mediaItems = playlist.map {
                val metadata = MediaMetadata.Builder()
                    .setTitle(it.title)
                    .setArtist(it.artist)
                    .setDisplayTitle(it.title)
                    .build()
                MediaItem.Builder()
                    .setMediaId(it.id.toString())
                    .setUri(it.uri)
                    .setMediaMetadata(metadata)
                    .build()
            }
            controller.setMediaItems(mediaItems, currentIndex, 0L)
            controller.repeatMode = Player.REPEAT_MODE_ALL
            controller.prepare()
            controller.play()
            MusicRepository.recordSongPlayed(context, song.id)
        }
    }

    fun playNextInQueue(song: Song) {
        mediaController?.let { controller ->
            val nextIndex = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setDisplayTitle(song.title)
                .build()
            controller.addMediaItem(
                nextIndex,
                MediaItem.Builder()
                    .setMediaId(song.id.toString())
                    .setUri(song.uri)
                    .setMediaMetadata(metadata)
                    .build()
            )
            Toast.makeText(context, "已设为下一首播放 ⏭️", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteSong(song: Song) {
        MusicRepository.deleteSong(context, song.id)
        allSongs = allSongs.filterNot { it.id == song.id }
        Toast.makeText(context, "已从音乐库移除 🗑️", Toast.LENGTH_SHORT).show()
    }

    fun toggleFavorite(song: Song) {
        val isFav = MusicRepository.toggleFavorite(context, song.id)
        favoriteIds = MusicRepository.getFavoriteIds(context)
        Toast.makeText(context, if (isFav) "已收藏 ❤️" else "已取消收藏", Toast.LENGTH_SHORT).show()
    }

    fun shareSong(song: Song) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, song.uri)
                putExtra(Intent.EXTRA_SUBJECT, song.title)
                putExtra(Intent.EXTRA_TEXT, "分享音乐：《${song.title}》- ${song.artist}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享《${song.title}》"))
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareSelectedSongs() {
        val selectedSongs = displaySongs.filter { it.id in selectedSongIds }
        if (selectedSongs.isEmpty()) {
            Toast.makeText(context, "请先勾选需要分享的歌曲", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedSongs.size == 1) {
            shareSong(selectedSongs.first())
            return
        }

        try {
            val uris = ArrayList<Uri>(selectedSongs.map { it.uri })
            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "分享 ${selectedSongs.size} 首音乐")
                putExtra(Intent.EXTRA_TEXT, "分享选中的 ${selectedSongs.size} 首音乐")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享选中的 ${selectedSongs.size} 首歌曲"))
        } catch (e: Exception) {
            Toast.makeText(context, "批量分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun playShuffle() {
        if (displaySongs.isNotEmpty()) {
            val shuffled = displaySongs.shuffled()
            val firstSong = shuffled.first()
            playSong(firstSong, shuffled)
            Toast.makeText(context, "🔀 随机播放：${firstSong.title}", Toast.LENGTH_SHORT).show()
        }
    }

    fun playSongAtIndex(index: Int) {
        if (displaySongs.isNotEmpty()) {
            val safeIndex = (index + displaySongs.size) % displaySongs.size
            playSong(displaySongs[safeIndex], displaySongs)
        }
    }

    fun playNext() {
        mediaController?.let { controller ->
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
                controller.play()
            } else if (controller.mediaItemCount > 0) {
                controller.seekToDefaultPosition(0)
                controller.play()
            } else {
                val currentIndex = displaySongs.indexOfFirst { it.uri.toString() == currentPlayingUri?.toString() }
                if (currentIndex != -1) playSongAtIndex(currentIndex + 1) else if (displaySongs.isNotEmpty()) playSongAtIndex(0)
            }
        }
    }

    fun playPrevious() {
        mediaController?.let { controller ->
            if (controller.hasPreviousMediaItem()) {
                controller.seekToPreviousMediaItem()
                controller.play()
            } else if (controller.mediaItemCount > 0) {
                controller.seekToDefaultPosition((controller.mediaItemCount - 1).coerceAtLeast(0))
                controller.play()
            } else {
                val currentIndex = displaySongs.indexOfFirst { it.uri.toString() == currentPlayingUri?.toString() }
                if (currentIndex != -1) playSongAtIndex(currentIndex - 1) else if (displaySongs.isNotEmpty()) playSongAtIndex(displaySongs.size - 1)
            }
        }
    }

    // 睡眠定时器倒计时协程
    LaunchedEffect(sleepTimerSeconds, isPlaying) {
        if (sleepTimerSeconds > 0 && isPlaying) {
            while (sleepTimerSeconds > 0 && isPlaying) {
                kotlinx.coroutines.delay(1000)
                sleepTimerSeconds--
            }
            if (sleepTimerSeconds == 0 && !isSleepTimerAfterSong) {
                mediaController?.pause()
                Toast.makeText(context, "🌙 睡眠定时时间已到，已自动为您暂停音乐", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 音乐卡片展开时按手机返回键收起音乐卡片
    BackHandler(enabled = showMusicCard) {
        showMusicCard = false
    }

    // 手机导航返回键处理：在全选/多选模式下按返回键直接取消全选退出多选模式
    BackHandler(enabled = isMultiSelectMode) {
        isMultiSelectMode = false
        selectedSongIds = emptySet()
    }

    // 搜索状态下按返回键关闭搜索
    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        searchQuery = ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0E2218), Color(0xFF0A0F0D), Color(0xFF070B09))
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CleanMinimalHeader(
                    songCount = displaySongs.size,
                    isSearchActive = isSearchActive,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onOpenSearch = { isSearchActive = true },
                    onCloseSearch = {
                        isSearchActive = false
                        searchQuery = ""
                    },
                    onOpenSettings = { showSettingsSheet = true },
                    currentSortType = currentSortType,
                    isAscending = isAscending,
                    isMultiSelectMode = isMultiSelectMode,
                    selectedCount = selectedSongIds.size,
                    onSortSelected = { newSort ->
                        if (currentSortType == newSort) {
                            if (newSort != SortType.CUSTOM) saveSortConfig(newSort, !isAscending)
                        } else {
                            val defaultAsc = when (newSort) {
                                SortType.DATE_ADDED, SortType.PLAY_COUNT -> false
                                else -> true
                            }
                            saveSortConfig(newSort, defaultAsc)
                        }
                    },
                    onShuffleClick = { playShuffle() },
                    onToggleMultiSelect = {
                        isMultiSelectMode = !isMultiSelectMode
                        if (!isMultiSelectMode) selectedSongIds = emptySet()
                    },
                    selectedTab = selectedTab,
                    activeFolder = activeFolder,
                    onTabSelected = { tab ->
                        selectedTab = tab
                        activePlaylist = null
                        activeFolder = null
                    },
                    sleepTimerSeconds = sleepTimerSeconds,
                    isSleepTimerAfterSong = isSleepTimerAfterSong,
                    onOpenSleepTimer = { showSleepTimerSheet = true }
                )
            },
            bottomBar = {
                if (isMultiSelectMode) {
                    MultiSelectBottomBar(
                        selectedCount = selectedSongIds.size,
                        totalCount = displaySongs.size,
                        onSelectAll = {
                            selectedSongIds = if (selectedSongIds.size == displaySongs.size) emptySet() else displaySongs.map { it.id }.toSet()
                        },
                        onPlaySelected = {
                            val selected = displaySongs.filter { it.id in selectedSongIds }
                            if (selected.isNotEmpty()) {
                                mediaController?.let { controller ->
                                    val mediaItems = selected.map {
                                        val metadata = MediaMetadata.Builder()
                                            .setTitle(it.title)
                                            .setArtist(it.artist)
                                            .setDisplayTitle(it.title)
                                            .build()
                                        MediaItem.Builder()
                                            .setMediaId(it.id.toString())
                                            .setUri(it.uri)
                                            .setMediaMetadata(metadata)
                                            .build()
                                    }
                                    controller.setMediaItems(mediaItems, 0, 0L)
                                    controller.repeatMode = Player.REPEAT_MODE_ALL
                                    controller.prepare()
                                    controller.play()
                                }
                                isMultiSelectMode = false
                                selectedSongIds = emptySet()
                            }
                        },
                        onPinSelected = {
                            val newOrder = displaySongs.filter { it.id in selectedSongIds }.map { it.id } +
                                    displaySongs.filterNot { it.id in selectedSongIds }.map { it.id }
                            MusicRepository.saveCustomOrder(context, newOrder)
                            customOrderList = newOrder
                            saveSortConfig(SortType.CUSTOM, true)
                            isMultiSelectMode = false
                            selectedSongIds = emptySet()
                            Toast.makeText(context, "批量置顶成功 ✨", Toast.LENGTH_SHORT).show()
                        },
                        onShareSelected = {
                            shareSelectedSongs()
                        },
                        onDeleteSelected = {
                            if (selectedSongIds.isNotEmpty()) {
                                showBatchDeleteDialog = true
                            }
                        },
                        onCancel = {
                            isMultiSelectMode = false
                            selectedSongIds = emptySet()
                        }
                    )
                } else {
                    AnimatedVisibility(
                        visible = currentPlayingSong != null,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn()
                    ) {
                        currentPlayingSong?.let { song ->
                            val progressFraction = if (totalDurationMs > 0) {
                                (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                            } else 0f

                            FloatingLuxuryPlayerDock(
                                song = song,
                                isPlaying = isPlaying,
                                progress = progressFraction,
                                isFavorite = song.id in favoriteIds,
                                sleepTimerSeconds = sleepTimerSeconds,
                                isSleepTimerAfterSong = isSleepTimerAfterSong,
                                onOpenMusicCard = { showMusicCard = true },
                                onToggleFavorite = { toggleFavorite(song) },
                                onPlayPauseClick = {
                                    if (isPlaying) mediaController?.pause() else mediaController?.play()
                                },
                                onNextClick = { playNext() },
                                onPreviousClick = { playPrevious() },
                                onSleepTimerClick = { showSleepTimerSheet = true }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = EmeraldPrimary, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                    }
                } else if (selectedTab == LibraryTab.PLAYLIST && activePlaylist == null && activeFolder == null) {
                    // 歌单列表视图
                    PlaylistOverviewView(
                        playlists = playlists,
                        onCreatePlaylist = { showCreatePlaylistDialog = true },
                        onPlaylistClick = { playlist -> activePlaylist = playlist },
                        onDeletePlaylist = { p ->
                            MusicRepository.deletePlaylist(context, p.id)
                            playlists = MusicRepository.getPlaylists(context)
                        }
                    )
                } else {
                    // 文件夹或歌单返回指示条
                    if (activeFolder != null || activePlaylist != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF13281E))
                                .clickable {
                                    activeFolder = null
                                    activePlaylist = null
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = EmeraldPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "返回 / " + (activeFolder ?: activePlaylist?.name ?: ""),
                                color = EmeraldPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (displaySongs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (selectedTab == LibraryTab.FAVORITE) "还没有收藏歌曲，点击红心收藏吧 ❤️" else "暂无匹配音乐",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            val songsToDisplay = if (draggingSongId != null && dragSongList.isNotEmpty()) dragSongList else displaySongs

                            LazyColumn(
                                state = listState,
                                userScrollEnabled = draggingSongId == null,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 100.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(songsToDisplay, key = { _, song -> song.id }) { index, song ->
                                    val isThisSongPlaying = currentPlayingSong?.id == song.id
                                    val isSelected = song.id in selectedSongIds
                                    val isFav = song.id in favoriteIds
                                    val songPlayCount = playCounts[song.id] ?: 0

                                    LuxurySongCard(
                                        modifier = if (draggingSongId == song.id) Modifier else Modifier.animateItem(),
                                        song = song,
                                        index = index,
                                        totalCount = songsToDisplay.size,
                                        isCurrent = isThisSongPlaying,
                                        isPlayingNow = isThisSongPlaying && isPlaying,
                                        isFavorite = isFav,
                                        isCustomSort = currentSortType == SortType.CUSTOM && !isMultiSelectMode,
                                        isMultiSelectMode = isMultiSelectMode,
                                        isSelected = isSelected,
                                        isDragging = draggingSongId == song.id,
                                        dragOffsetY = if (draggingSongId == song.id) draggingOffsetY else 0f,
                                        onDragStart = {
                                            if (currentSortType != SortType.CUSTOM) {
                                                val initialList = displaySongs.map { it.id }
                                                customOrderList = initialList
                                                saveSortConfig(SortType.CUSTOM, true)
                                            }
                                            val currentList = displaySongs.toList()
                                            dragSongList = currentList
                                            dragCurrentIndex = currentList.indexOfFirst { it.id == song.id }
                                            draggingSongId = song.id
                                            draggingOffsetY = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { dragAmountY ->
                                            draggingOffsetY += dragAmountY
                                            val step = itemHeightPx
                                            val threshold = step * 0.5f

                                            if (draggingOffsetY > threshold && dragCurrentIndex < dragSongList.size - 1 && dragCurrentIndex >= 0) {
                                                val nextIndex = dragCurrentIndex + 1
                                                val mutable = dragSongList.toMutableList()
                                                val item = mutable.removeAt(dragCurrentIndex)
                                                mutable.add(nextIndex, item)
                                                dragSongList = mutable
                                                dragCurrentIndex = nextIndex
                                                draggingOffsetY -= step
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else if (draggingOffsetY < -threshold && dragCurrentIndex > 0) {
                                                val prevIndex = dragCurrentIndex - 1
                                                val mutable = dragSongList.toMutableList()
                                                val item = mutable.removeAt(dragCurrentIndex)
                                                mutable.add(prevIndex, item)
                                                dragSongList = mutable
                                                dragCurrentIndex = prevIndex
                                                draggingOffsetY += step
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = {
                                            if (draggingSongId != null) {
                                                val finalIds = dragSongList.map { it.id }
                                                customOrderList = finalIds
                                                MusicRepository.saveCustomOrder(context, finalIds)
                                                draggingSongId = null
                                                dragCurrentIndex = -1
                                                draggingOffsetY = 0f
                                            }
                                        },
                                        playCount = songPlayCount,
                                        showPlayCount = currentSortType == SortType.PLAY_COUNT,
                                        onClick = {
                                            if (isMultiSelectMode) {
                                                selectedSongIds = if (isSelected) selectedSongIds - song.id else selectedSongIds + song.id
                                            } else {
                                                playSong(song)
                                                showMusicCard = true
                                            }
                                        },
                                        onToggleFavorite = { toggleFavorite(song) },
                                        onPlayNext = { playNextInQueue(song) },
                                        onAddToPlaylist = { songToAddToPlaylist = song },
                                        onDelete = { deleteSong(song) },
                                        onPinToTop = {
                                            val newOrder = listOf(song.id) + displaySongs.filterNot { it.id == song.id }.map { it.id }
                                            MusicRepository.saveCustomOrder(context, newOrder)
                                            customOrderList = newOrder
                                            saveSortConfig(SortType.CUSTOM, true)
                                            Toast.makeText(context, "已置顶《${song.title}》✨", Toast.LENGTH_SHORT).show()
                                        },
                                        onShare = { shareSong(song) },
                                        onShowSongInfo = { songForInfoDialog = song }
                                    )
                                }
                            }

                            // 往下滑动/上滑时的快速导航直线条 (Fast Scroll Indicator)
                            if (displaySongs.size > 4) {
                                val totalItems = (displaySongs.size - 1).coerceAtLeast(1)
                                val firstVisible = listState.firstVisibleItemIndex
                                val offsetFraction = (firstVisible.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)
                                val isScrolling = listState.isScrollInProgress

                                val scrollbarAlpha by animateFloatAsState(
                                    targetValue = if (isScrolling) 1f else 0.25f,
                                    animationSpec = tween(durationMillis = if (isScrolling) 150 else 500),
                                    label = "scrollIndicatorAlpha"
                                )

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 1.dp, top = 8.dp, bottom = 100.dp)
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .alpha(scrollbarAlpha)
                                ) {
                                    // 背景细直导轨
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(1.5.dp)
                                            .align(Alignment.Center)
                                            .background(Color(0xFF162D21), RoundedCornerShape(1.dp))
                                    )

                                    // 导航滑块直线
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight(0.16f)
                                            .width(3.dp)
                                            .align(BiasAlignment(0f, (offsetFraction * 2f) - 1f))
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(EmeraldGlow, EmeraldPrimary)
                                                )
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 设置面板 (内含：按文件夹浏览、重新扫描、清空黑名单)
        if (showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                containerColor = SurfaceElevated
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("设置与工具", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(16.dp))

                    // 1. 文件夹功能放进设置里
                    Surface(
                        onClick = {
                            showSettingsSheet = false
                            showFolderSheet = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceCard,
                        border = BorderStroke(1.dp, Color(0xFF1E2D25)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("按文件夹浏览", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("查看各存储目录下的音乐", color = TextSecondary, fontSize = 11.sp)
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. 重新扫描本地歌曲
                    Surface(
                        onClick = {
                            refreshSongs()
                            showSettingsSheet = false
                            Toast.makeText(context, "曲库重新扫描完成 🔄", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceCard,
                        border = BorderStroke(1.dp, Color(0xFF1E2D25)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("重新扫描本地歌曲", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("检测新下载或传输的音频文件", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. 睡眠模式 / 睡眠定时器
                    Surface(
                        onClick = {
                            showSettingsSheet = false
                            showSleepTimerSheet = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (sleepTimerSeconds > 0 || isSleepTimerAfterSong) Color(0xFF132A1E) else SurfaceCard,
                        border = BorderStroke(1.dp, if (sleepTimerSeconds > 0 || isSleepTimerAfterSong) EmeraldPrimary else Color(0xFF1E2D25)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("睡眠模式 / 定时器", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                val sleepDesc = when {
                                    isSleepTimerAfterSong -> "已开启 · 播完当前歌曲后停止"
                                    sleepTimerSeconds > 0 -> {
                                        val m = sleepTimerSeconds / 60
                                        val s = sleepTimerSeconds % 60
                                        "已开启 · 剩余 %02d:%02d 自动停止".format(m, s)
                                    }
                                    else -> "伴随音乐安心入睡，定时自动暂停播放"
                                }
                                Text(
                                    text = sleepDesc,
                                    color = if (sleepTimerSeconds > 0 || isSleepTimerAfterSong) EmeraldPrimary else TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }

        // 睡眠定时器配置面板
        if (showSleepTimerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSleepTimerSheet = false },
                containerColor = SurfaceElevated
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("睡眠定时器", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                    Text(
                        text = if (sleepTimerSeconds > 0 || isSleepTimerAfterSong) "当前定时进行中，可选择其他时长或关闭" else "选择定时时长，时间到达后自动暂停播放",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val timerOptions = listOf(
                        5 to "5 分钟",
                        10 to "10 分钟",
                        15 to "15 分钟",
                        30 to "30 分钟",
                        45 to "45 分钟",
                        60 to "60 分钟",
                        90 to "90 分钟"
                    )

                    timerOptions.forEach { (mins, label) ->
                        Surface(
                            onClick = {
                                sleepTimerSeconds = mins * 60
                                isSleepTimerAfterSong = false
                                showSleepTimerSheet = false
                                Toast.makeText(context, "🌙 已设置 $label 后自动暂停播放", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = SurfaceCard,
                            border = BorderStroke(1.dp, Color(0xFF1E2D25)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, color = TextPrimary, fontSize = 14.sp)
                                if (sleepTimerSeconds in (mins * 60 - 60)..(mins * 60) && !isSleepTimerAfterSong) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // 播完当前歌曲后停止
                    Surface(
                        onClick = {
                            isSleepTimerAfterSong = true
                            sleepTimerSeconds = 0
                            showSleepTimerSheet = false
                            Toast.makeText(context, "🌙 已开启：当前歌曲播完后自动暂停", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceCard,
                        border = BorderStroke(1.dp, Color(0xFF1E2D25)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("播完当前歌曲后停止", color = TextPrimary, fontSize = 14.sp)
                            if (isSleepTimerAfterSong) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // 关闭定时器
                    if (sleepTimerSeconds > 0 || isSleepTimerAfterSong) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            onClick = {
                                sleepTimerSeconds = 0
                                isSleepTimerAfterSong = false
                                showSleepTimerSheet = false
                                Toast.makeText(context, "已关闭睡眠定时器", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF2E1717),
                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text("关闭睡眠定时器", color = Color(0xFFFF5252), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // 文件夹选择面板
        if (showFolderSheet) {
            val folders = remember(allSongs) { allSongs.groupBy { it.folderName } }
            ModalBottomSheet(
                onDismissRequest = { showFolderSheet = false },
                containerColor = SurfaceElevated
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text("音乐文件夹", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folders.keys.toList()) { folderName ->
                            val count = folders[folderName]?.size ?: 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceCard)
                                    .border(1.dp, Color(0xFF1B2C23), RoundedCornerShape(12.dp))
                                    .clickable {
                                        activeFolder = folderName
                                        showFolderSheet = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folderName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("$count 首曲目", color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        // 批量删除确认弹窗
        if (showBatchDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showBatchDeleteDialog = false },
                containerColor = SurfaceElevated,
                title = { Text("从音乐库删除", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    Text("确定要从音乐库中移除选中的 ${selectedSongIds.size} 首歌曲吗？", color = TextSecondary)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val toDelete = displaySongs.filter { it.id in selectedSongIds }
                            toDelete.forEach { MusicRepository.deleteSong(context, it.id) }
                            allSongs = allSongs.filterNot { it.id in selectedSongIds }
                            showBatchDeleteDialog = false
                            isMultiSelectMode = false
                            selectedSongIds = emptySet()
                            Toast.makeText(context, "已从音乐库移除 ${toDelete.size} 首歌曲 🗑️", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("删除", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDeleteDialog = false }) {
                        Text("取消", color = TextSecondary)
                    }
                }
            )
        }

        // 新建歌单对话框
        if (showCreatePlaylistDialog) {
            var playlistNameInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showCreatePlaylistDialog = false },
                containerColor = SurfaceElevated,
                title = { Text("新建歌单", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = playlistNameInput,
                        onValueChange = { playlistNameInput = it },
                        placeholder = { Text("歌单名称...", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldPrimary,
                            unfocusedBorderColor = CardBorderGlow,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (playlistNameInput.isNotBlank()) {
                                MusicRepository.createPlaylist(context, playlistNameInput.trim())
                                playlists = MusicRepository.getPlaylists(context)
                                showCreatePlaylistDialog = false
                                Toast.makeText(context, "歌单创建成功 🎉", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("创建", color = EmeraldPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCreatePlaylistDialog = false }) {
                        Text("取消", color = TextSecondary)
                    }
                }
            )
        }

        // 添加到歌单对话框
        if (songToAddToPlaylist != null) {
            val song = songToAddToPlaylist!!
            AlertDialog(
                onDismissRequest = { songToAddToPlaylist = null },
                containerColor = SurfaceElevated,
                title = { Text("添加到歌单", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = {
                    if (playlists.isEmpty()) {
                        Text("暂无自建歌单，请先在【歌单】中新建", color = TextSecondary)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(playlists) { p ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(SurfaceCard)
                                        .clickable {
                                            val added = MusicRepository.addSongToPlaylist(context, p.id, song.id)
                                            playlists = MusicRepository.getPlaylists(context)
                                            songToAddToPlaylist = null
                                            Toast.makeText(context, if (added) "已加入《${p.name}》📑" else "歌曲已在歌单中", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = EmeraldLight)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(text = p.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { songToAddToPlaylist = null }) {
                        Text("关闭", color = TextSecondary)
                    }
                }
            )
        }

        // 歌曲详情与自选封面对话框
        songForInfoDialog?.let { currentInfoSong ->
            SongInfoDialog(
                song = currentInfoSong,
                onDismiss = { songForInfoDialog = null },
                onSongUpdated = { updated ->
                    allSongs = allSongs.map { if (it.id == updated.id) updated else it }
                    songForInfoDialog = null
                }
            )
        }

        // 沉浸式音乐卡片 (Music Card)
        if (showMusicCard && currentPlayingSong != null) {
            MusicCardSheet(
                song = currentPlayingSong,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                isFavorite = currentPlayingSong.id in favoriteIds,
                isShuffleEnabled = isShuffleEnabled,
                repeatMode = repeatMode,
                sleepTimerSeconds = sleepTimerSeconds,
                isSleepTimerAfterSong = isSleepTimerAfterSong,
                onDismiss = { showMusicCard = false },
                onPlayPause = {
                    if (isPlaying) mediaController?.pause() else mediaController?.play()
                },
                onSeekTo = { targetMs ->
                    mediaController?.seekTo(targetMs)
                    currentPositionMs = targetMs
                },
                onNext = { playNext() },
                onPrevious = { playPrevious() },
                onToggleFavorite = { toggleFavorite(currentPlayingSong) },
                onCyclePlaybackMode = {
                    mediaController?.let { controller ->
                        val currentShuffle = controller.shuffleModeEnabled
                        val currentRepeat = controller.repeatMode
                        when {
                            // 1. 当前是列表循环 -> 切换为 单曲循环
                            !currentShuffle && currentRepeat == Player.REPEAT_MODE_ALL -> {
                                controller.shuffleModeEnabled = false
                                controller.repeatMode = Player.REPEAT_MODE_ONE
                                isShuffleEnabled = false
                                repeatMode = Player.REPEAT_MODE_ONE
                                Toast.makeText(context, "单曲循环 🔂", Toast.LENGTH_SHORT).show()
                            }
                            // 2. 当前是单曲循环 -> 切换为 随机播放
                            !currentShuffle && currentRepeat == Player.REPEAT_MODE_ONE -> {
                                controller.repeatMode = Player.REPEAT_MODE_ALL
                                controller.shuffleModeEnabled = true
                                isShuffleEnabled = true
                                repeatMode = Player.REPEAT_MODE_ALL
                                Toast.makeText(context, "随机播放 🔀", Toast.LENGTH_SHORT).show()
                            }
                            // 3. 当前是随机播放 -> 切换为 顺序播放
                            currentShuffle -> {
                                controller.shuffleModeEnabled = false
                                controller.repeatMode = Player.REPEAT_MODE_OFF
                                isShuffleEnabled = false
                                repeatMode = Player.REPEAT_MODE_OFF
                                Toast.makeText(context, "顺序播放 ➡️", Toast.LENGTH_SHORT).show()
                            }
                            // 4. 当前是顺序播放 (或其它) -> 切换回 列表循环
                            else -> {
                                controller.shuffleModeEnabled = false
                                controller.repeatMode = Player.REPEAT_MODE_ALL
                                isShuffleEnabled = false
                                repeatMode = Player.REPEAT_MODE_ALL
                                Toast.makeText(context, "列表循环 🔁", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onOpenSleepTimer = { showSleepTimerSheet = true },
                onShowSongInfo = { songForInfoDialog = currentPlayingSong },
                onShare = { shareSong(currentPlayingSong) }
            )
        }
    }
}

// 极其整洁的顶栏：
// 1. 默认展示：左侧 AURORA MUSIC，右侧 放大镜 🔍 + 设置 ⚙️
// 2. 搜索展开：返回键 ← + 搜索输入框 + 清空 ✕
// 3. 紧凑单行：[🔀 随机播放] + [🏷️ 纯图标排序] + [☑️ 纯图标多选]
@Composable
fun CleanMinimalHeader(
    songCount: Int,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    currentSortType: SortType,
    isAscending: Boolean,
    isMultiSelectMode: Boolean,
    selectedCount: Int,
    onSortSelected: (SortType) -> Unit,
    onShuffleClick: () -> Unit,
    onToggleMultiSelect: () -> Unit,
    selectedTab: LibraryTab,
    activeFolder: String?,
    onTabSelected: (LibraryTab) -> Unit,
    sleepTimerSeconds: Int = 0,
    isSleepTimerAfterSong: Boolean = false,
    onOpenSleepTimer: () -> Unit = {}
) {
    var isMoreMenuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // 第一行：沉浸式标题栏 OR 展开的搜索栏
        if (isSearchActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceCard)
                    .border(1.dp, EmeraldPrimary, RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回键
                IconButton(onClick = onCloseSearch, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                // 使用 BasicTextField 彻底解决字体下半部分被截断的问题
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(EmeraldPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "搜索曲目、音乐人...",
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 13.5.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清空",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Zbc", fontWeight = FontWeight.Black, fontSize = 21.sp, letterSpacing = 1.sp, color = TextPrimary)
                        Text("21934", fontWeight = FontWeight.Black, fontSize = 21.sp, letterSpacing = 1.sp, color = EmeraldPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isMultiSelectMode) "已勾选 $selectedCount / $songCount 首" else "$songCount 首曲目",
                            fontSize = 11.sp,
                            color = if (isMultiSelectMode) EmeraldPrimary else TextGreenMuted
                        )

                        // 🌙 睡眠定时剩余时间小胶囊：放在曲目数量旁边，一眼即知，绝不挡着右侧搜索与设置！
                        if (sleepTimerSeconds > 0 || isSleepTimerAfterSong) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                onClick = onOpenSleepTimer,
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF132B1E),
                                border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("🌙", fontSize = 9.sp)
                                    Spacer(modifier = Modifier.width(3.dp))
                                    val timerText = if (isSleepTimerAfterSong) {
                                        "播完停"
                                    } else {
                                        val m = sleepTimerSeconds / 60
                                        val s = sleepTimerSeconds % 60
                                        "%02d:%02d".format(m, s)
                                    }
                                    Text(
                                        text = timerText,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // 右侧：纯粹的 搜索 🔍 与 设置 ⚙️ (恢复原样，不再被挤占碰撞)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 放大镜搜索按钮 (图标放大到 22dp，外框 42dp)
                    Surface(
                        onClick = onOpenSearch,
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceCard,
                        border = BorderStroke(1.dp, CardBorderGlow)
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "搜索",
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // 设置按钮 (图标放大到 22dp，外框 42dp)
                    Surface(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(12.dp),
                        color = SurfaceCard,
                        border = BorderStroke(1.dp, CardBorderGlow)
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "设置",
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 第二行：Apple Music 风格「极简无框呼吸 Tab」（全部 | 我喜欢 | 歌单）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(LibraryTab.ALL, LibraryTab.FAVORITE, LibraryTab.PLAYLIST).forEach { tab ->
                val isSelected = selectedTab == tab && activeFolder == null
                val animatedTextColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else TextSecondary.copy(alpha = 0.55f),
                    label = "TabTextColor"
                )
                val animatedIndicatorWidth by animateDpAsState(
                    targetValue = if (isSelected) 18.dp else 0.dp,
                    label = "TabIndicatorWidth"
                )

                Column(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onTabSelected(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = tab.title,
                        fontSize = if (isSelected) 16.sp else 15.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = animatedTextColor,
                        letterSpacing = 0.3.sp
                    )

                    Spacer(modifier = Modifier.height(5.dp))

                    // 精致翡翠绿流动指示条 (高度恒定保持垂直稳定，宽度平滑展开)
                    Box(
                        modifier = Modifier
                            .width(animatedIndicatorWidth)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(EmeraldPrimary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 第三行：整洁单行 [🔀 随机播放] + [最右侧三条横线菜单：包含排序与多选]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 随机播放 (精简胶囊)
            Surface(
                onClick = onShuffleClick,
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF142B20),
                border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.5f))
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "随机播放", tint = EmeraldPrimary, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("随机播放", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = EmeraldPrimary)
                }
            }

            // 最右侧三条横线图标按钮：点开展示排序方式 (Sort by) 与 多选模式 (Multiple Select)
            Box {
                Surface(
                    onClick = { isMoreMenuExpanded = true },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isMultiSelectMode) Color(0xFF1B3B2B) else SurfaceCard,
                    border = BorderStroke(1.dp, if (isMultiSelectMode) EmeraldPrimary else CardBorderGlow)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "菜单与排序",
                            tint = if (isMultiSelectMode) EmeraldPrimary else EmeraldLight,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = isMoreMenuExpanded,
                    onDismissRequest = { isMoreMenuExpanded = false },
                    modifier = Modifier
                        .background(SurfaceElevated)
                        .border(1.dp, CardBorderGlow, RoundedCornerShape(14.dp))
                        .width(215.dp)
                ) {
                    // 1. 多选模式 (Multiple Select)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (isMultiSelectMode) "退出多选" else "多选模式 (Multiple Select)",
                                color = if (isMultiSelectMode) EmeraldPrimary else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (isMultiSelectMode) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isMultiSelectMode) Icons.Rounded.CheckCircle else Icons.Rounded.Checklist,
                                contentDescription = null,
                                tint = if (isMultiSelectMode) EmeraldPrimary else EmeraldLight,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            onToggleMultiSelect()
                            isMoreMenuExpanded = false
                        }
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 12.dp)
                            .height(0.8.dp)
                            .background(CardBorderGlow.copy(alpha = 0.6f))
                    )

                    // 2. 排序方式标题 (Sort by)
                    Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                        Text(
                            text = "排序方式 (Sort by)",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 排序选项列表
                    SortType.values().forEach { sort ->
                        val isCurrent = currentSortType == sort
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sort.icon, fontSize = 13.sp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = sort.displayName,
                                        color = if (isCurrent) EmeraldPrimary else TextPrimary,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            trailingIcon = {
                                if (isCurrent) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (sort != SortType.CUSTOM) {
                                            Icon(
                                                imageVector = if (isAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                                contentDescription = if (isAscending) "升序" else "降序",
                                                tint = EmeraldPrimary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                        }
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = EmeraldPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onSortSelected(sort)
                                isMoreMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// 歌单主视图
@Composable
fun PlaylistOverviewView(
    playlists: List<Playlist>,
    onCreatePlaylist: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            onClick = onCreatePlaylist,
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF142B20),
            border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("新建专属歌单", color = EmeraldPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无歌单，点击上方新建第一支歌单吧 🎶", color = TextSecondary, fontSize = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists) { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceCard)
                            .border(1.dp, Color(0xFF192620), RoundedCornerShape(12.dp))
                            .clickable { onPlaylistClick(p) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(EmeraldDeep, EmeraldPrimary))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("${p.songIds.size} 首歌曲", color = TextSecondary, fontSize = 11.sp)
                        }
                        IconButton(onClick = { onDeletePlaylist(p) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除", tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// 单曲卡片 (呼吸瘦身版：去除冗长路径，留白清爽)
@Composable
fun LuxurySongCard(
    modifier: Modifier = Modifier,
    song: Song,
    index: Int,
    totalCount: Int,
    isCurrent: Boolean,
    isPlayingNow: Boolean,
    isFavorite: Boolean,
    isCustomSort: Boolean,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    isDragging: Boolean = false,
    dragOffsetY: Float = 0f,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    playCount: Int = 0,
    showPlayCount: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
    onPinToTop: () -> Unit,
    onShare: () -> Unit,
    onShowSongInfo: () -> Unit
) {
    var showMoreMenu by remember { mutableStateOf(false) }

    val cardBackground by animateColorAsState(
        targetValue = when {
            isDragging -> Color(0xFF1E382B)
            isSelected -> Color(0xFF173826)
            isCurrent -> Color(0xFF13281E)
            else -> SurfaceCard
        },
        animationSpec = tween(durationMillis = 300),
        label = "cardBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (isDragging) 10f else 1f)
            .graphicsLayer {
                translationY = dragOffsetY
                if (isDragging) {
                    scaleX = 1.025f
                    scaleY = 1.025f
                    shadowElevation = 18f
                }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(cardBackground)
            .border(
                when {
                    isDragging -> BorderStroke(1.5.dp, EmeraldPrimary)
                    isSelected -> BorderStroke(1.2.dp, EmeraldPrimary)
                    isCurrent -> BorderStroke(1.dp, Brush.horizontalGradient(listOf(EmeraldPrimary, EmeraldGlow.copy(alpha = 0.4f))))
                    else -> BorderStroke(1.dp, Color(0xFF18231D))
                },
                RoundedCornerShape(14.dp)
            )
            .pointerInput(isCustomSort, song.id) {
                if (isCustomSort) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Icon(
                imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) EmeraldPrimary else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        // 封面 (支持内置嵌入封面与用户自选封面)
        SongCoverView(
            song = song,
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(10.dp),
            isPlayingNow = isPlayingNow
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 标题与歌手 (呼吸感瘦身：只保留歌名 + 歌手，纯净不杂乱)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (isCurrent) EmeraldPrimary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = song.artist,
                fontSize = 11.5.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 播放次数：右侧整齐对齐，低调微小字号素雅显示
        if (showPlayCount || playCount > 0) {
            Text(
                text = "${playCount}次",
                fontSize = 10.5.sp,
                color = TextSecondary.copy(alpha = 0.55f),
                modifier = Modifier.padding(end = 6.dp)
            )
        }

        // 自定义排序时的拖拽手柄 (专设手柄区域：按住即拖动，无冲突零延迟)
        if (isCustomSort) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(song.id) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = "拖动排序",
                    tint = if (isDragging) EmeraldPrimary else TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 歌曲右侧只留一个精致的 ⋮ 更多按钮
        Box {
            IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "更多",
                    tint = TextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
                modifier = Modifier
                    .background(SurfaceElevated)
                    .border(1.dp, CardBorderGlow, RoundedCornerShape(12.dp))
            ) {
                // 1. ❤️ 加入/取消我喜欢
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isFavorite) "取消我喜欢" else "加入我喜欢",
                            color = if (isFavorite) Color(0xFFFF5252) else TextPrimary,
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorite) Color(0xFFFF5252) else EmeraldLight
                        )
                    },
                    onClick = {
                        onToggleFavorite()
                        showMoreMenu = false
                    }
                )

                // 2. ⏭️ 下一首播放
                DropdownMenuItem(
                    text = { Text("下一首播放", color = TextPrimary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null, tint = EmeraldLight) },
                    onClick = {
                        onPlayNext()
                        showMoreMenu = false
                    }
                )

                // 3. 📑 添加到歌单
                DropdownMenuItem(
                    text = { Text("添加到歌单", color = TextPrimary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = EmeraldLight) },
                    onClick = {
                        onAddToPlaylist()
                        showMoreMenu = false
                    }
                )

                // 4. 🔝 置顶曲目
                DropdownMenuItem(
                    text = { Text("置顶曲目", color = TextPrimary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.VerticalAlignTop, contentDescription = null, tint = EmeraldLight) },
                    onClick = {
                        onPinToTop()
                        showMoreMenu = false
                    }
                )

                // 5. 📤 分享歌曲
                DropdownMenuItem(
                    text = { Text("分享歌曲", color = TextPrimary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null, tint = EmeraldLight) },
                    onClick = {
                        onShare()
                        showMoreMenu = false
                    }
                )

                // 6. ℹ️ 歌曲详情与自选封面
                DropdownMenuItem(
                    text = { Text("歌曲详情", color = TextPrimary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null, tint = EmeraldLight) },
                    onClick = {
                        onShowSongInfo()
                        showMoreMenu = false
                    }
                )

                // 7. 🗑️ 从音乐库删除
                DropdownMenuItem(
                    text = { Text("从音乐库删除", color = Color(0xFFFF6B6B), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color(0xFFFF6B6B)) },
                    onClick = {
                        onDelete()
                        showMoreMenu = false
                    }
                )
            }
        }
    }
}

// 底部悬浮多选操作栏 (包含全选、分享、删除、置顶、播放)
@Composable
fun MultiSelectBottomBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onPlaySelected: () -> Unit,
    onPinSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .shadow(20.dp, RoundedCornerShape(20.dp), spotColor = EmeraldPrimary.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceElevated)
            .border(BorderStroke(1.dp, CardBorderGlow), RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(if (selectedCount == totalCount) "取消全选" else "全选", color = EmeraldLight, fontSize = 11.5.sp)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // 分享按钮
                Surface(
                    onClick = onShareSelected,
                    enabled = selectedCount > 0,
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedCount > 0) Color(0xFF142B20) else Color.Transparent,
                    border = BorderStroke(1.dp, if (selectedCount > 0) EmeraldPrimary.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.25f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Share, contentDescription = null, tint = if (selectedCount > 0) EmeraldPrimary else Color.Gray, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("分享", fontSize = 11.sp, color = if (selectedCount > 0) EmeraldPrimary else Color.Gray)
                    }
                }

                // 删除按钮
                Surface(
                    onClick = onDeleteSelected,
                    enabled = selectedCount > 0,
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedCount > 0) Color(0xFF331616) else Color.Transparent,
                    border = BorderStroke(1.dp, if (selectedCount > 0) Color(0xFFFF5252).copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.25f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = if (selectedCount > 0) Color(0xFFFF5252) else Color.Gray, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("删除", fontSize = 11.sp, color = if (selectedCount > 0) Color(0xFFFF5252) else Color.Gray)
                    }
                }

                // 批量置顶
                Surface(
                    onClick = onPinSelected,
                    enabled = selectedCount > 0,
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedCount > 0) Color(0xFF142B20) else Color.Transparent,
                    border = BorderStroke(1.dp, if (selectedCount > 0) EmeraldPrimary.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.25f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VerticalAlignTop, contentDescription = null, tint = if (selectedCount > 0) EmeraldPrimary else Color.Gray, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("置顶", fontSize = 11.sp, color = if (selectedCount > 0) EmeraldPrimary else Color.Gray)
                    }
                }

                // 播放
                Surface(
                    onClick = onPlaySelected,
                    enabled = selectedCount > 0,
                    shape = RoundedCornerShape(10.dp),
                    color = if (selectedCount > 0) EmeraldPrimary else Color.DarkGray
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("播放($selectedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        }
    }
}

// 动态跳动波形
@Composable
fun LiveEqualizerVisualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val bar1 by infiniteTransition.animateFloat(0.2f, 0.9f, infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar1")
    val bar2 by infiniteTransition.animateFloat(0.85f, 0.3f, infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar2")
    val bar3 by infiniteTransition.animateFloat(0.35f, 1.0f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar3")

    Row(modifier = Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.Bottom) {
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(bar1).clip(RoundedCornerShape(2.dp)).background(Color.Black))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(bar2).clip(RoundedCornerShape(2.dp)).background(Color.Black))
        Box(modifier = Modifier.width(3.dp).fillMaxHeight(bar3).clip(RoundedCornerShape(2.dp)).background(Color.Black))
    }
}

// 浮动式黑翠播放 Dock 底栏
@Composable
fun FloatingLuxuryPlayerDock(
    song: Song,
    isPlaying: Boolean,
    progress: Float,
    isFavorite: Boolean,
    sleepTimerSeconds: Int = 0,
    isSleepTimerAfterSong: Boolean = false,
    onOpenMusicCard: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSleepTimerClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .shadow(20.dp, RoundedCornerShape(20.dp), spotColor = EmeraldPrimary.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceElevated)
            .border(BorderStroke(1.dp, CardBorderGlow), RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().height(2.5.dp).background(Color(0xFF1B2E24))) {
                Box(
                    modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(Brush.horizontalGradient(listOf(EmeraldGlow, EmeraldPrimary)))
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 点击封面或歌名区域展开沉浸式音乐卡片 (Music Card)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onOpenMusicCard
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SongCoverView(
                        song = song,
                        modifier = Modifier.size(38.dp),
                        shape = RoundedCornerShape(8.dp),
                        isPlayingNow = false,
                        placeholderIcon = Icons.Rounded.Album
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sleepTimerInfo = when {
                            isSleepTimerAfterSong -> " · 🌙 播完停止"
                            sleepTimerSeconds > 0 -> {
                                val m = sleepTimerSeconds / 60
                                val s = sleepTimerSeconds % 60
                                " · 🌙 %02d:%02d".format(m, s)
                            }
                            else -> ""
                        }
                        Text(
                            text = song.artist + sleepTimerInfo,
                            color = if (sleepTimerInfo.isNotEmpty()) EmeraldPrimary else TextSecondary,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFavorite) Color(0xFFFF5252) else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(onClick = onPreviousClick, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", tint = TextPrimary, modifier = Modifier.size(20.dp))
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(EmeraldPrimary, EmeraldGlow)))
                        .clickable(onClick = onPlayPauseClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(onClick = onNextClick, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
