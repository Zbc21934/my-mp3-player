package com.example.musicapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 核心标记：记录是否是因为外部音频打断而被暂停
    @Volatile
    private var wasPausedByExternalAudio = false

    // 标记是否是我们内部逻辑触发的暂停（避免内部调用 player.pause() 时触发用户手动暂停清理标记）
    @Volatile
    private var isInternalPause = false

    // 耳机/蓝牙拔出广播 (Audio Becoming Noisy)
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                mediaSession?.player?.let { p ->
                    if (p.isPlaying) {
                        wasPausedByExternalAudio = false
                        p.pause()
                        Toast.makeText(this@PlaybackService, "🎧 蓝牙/耳机已断开，已自动暂停播放", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 恢复音量的 Runnable (用于通知提示音 ducking 后快速回升至 100%)
    private val restoreVolumeRunnable = Runnable {
        mediaSession?.player?.volume = 1.0f
    }

    // 周期性探针：每秒轮询一次外部音频/通话是否已停止，若已停止且未恢复，则主动唤醒播放
    private val periodicResumeCheck = object : Runnable {
        override fun run() {
            if (!wasPausedByExternalAudio) return
            val player = mediaSession?.player
            if (player == null || player.isPlaying) {
                wasPausedByExternalAudio = false
                return
            }

            val inCall = audioManager.mode != AudioManager.MODE_NORMAL
            val isOtherAudioActive = audioManager.isMusicActive

            // 外部音频已彻底停止且无通话
            if (!inCall && !isOtherAudioActive) {
                val granted = requestAudioFocus()
                if (granted) {
                    wasPausedByExternalAudio = false
                    player.play()
                    return
                }
            }

            // 若外部声音仍在继续，1秒后再次探测
            mainHandler.postDelayed(this, 1000)
        }
    }

    // 系统音频焦点监听器
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = mediaSession?.player ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 1. 普通信息/通知提示音（微信、WhatsApp、短信、系统通知）
                // 绝不暂停音乐！仅轻微压低音量（Ducking）到 50%，1.2秒后自动恢复，顺畅不中断
                player.volume = 0.5f
                mainHandler.removeCallbacks(restoreVolumeRunnable)
                mainHandler.postDelayed(restoreVolumeRunnable, 1200)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 2. 短暂强打断（电话呼入响铃/接通、微信语音通话/视频通话、微信语音消息播放）
                if (player.isPlaying) {
                    wasPausedByExternalAudio = true
                    isInternalPause = true
                    player.pause()
                    isInternalPause = false
                    mainHandler.removeCallbacks(periodicResumeCheck)
                    mainHandler.postDelayed(periodicResumeCheck, 1000)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 3. 外部独立音视频播放（刷抖音、B站、YouTube、其他音频播放器）
                if (player.isPlaying) {
                    wasPausedByExternalAudio = true
                    isInternalPause = true
                    player.pause()
                    isInternalPause = false
                    mainHandler.removeCallbacks(periodicResumeCheck)
                    mainHandler.postDelayed(periodicResumeCheck, 1000)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 4. 外部声音结束，系统归还音频焦点 -> 立即恢复 100% 音量与自动继续播放！
                mainHandler.removeCallbacks(restoreVolumeRunnable)
                player.volume = 1.0f

                if (wasPausedByExternalAudio) {
                    wasPausedByExternalAudio = false
                    mainHandler.removeCallbacks(periodicResumeCheck)
                    player.play()
                }
            }
        }
    }

    // 辅助兜底机制 (针对外部播放完音频后不主动释放焦点的应用)
    private val resumeCheckRunnable = Runnable {
        val player = mediaSession?.player ?: return@Runnable
        if (wasPausedByExternalAudio && !player.isPlaying) {
            val inCall = audioManager.mode != AudioManager.MODE_NORMAL
            if (!inCall && !audioManager.isMusicActive) {
                val granted = requestAudioFocus()
                if (granted) {
                    wasPausedByExternalAudio = false
                    player.play()
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.requestAudioFocus(it) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } ?: false
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 初始化 Android 8.0+ 音频焦点请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val androidAudioAttributes = AndroidAudioAttributes.Builder()
                .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(androidAudioAttributes)
                .setAcceptsDelayedFocusGain(true) // 允许系统在外部音频结束时自动补发 GAIN
                .setWillPauseWhenDucked(false)    // 关键：通知提示音时不要暂停音乐，而是平滑压低音量！
                .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                .build()
        }

        // 配置 ExoPlayer
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, false) // 由我们精确调度系统焦点
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
            }

        // 监听播放状态：如果用户在界面或通知栏主动点击暂停，则清理打断标记，不发生无端恢复
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    requestAudioFocus()
                    wasPausedByExternalAudio = false
                    mainHandler.removeCallbacks(periodicResumeCheck)
                } else if (!isInternalPause) {
                    // 用户主动手动在界面/通知栏点击暂停
                    wasPausedByExternalAudio = false
                    mainHandler.removeCallbacks(periodicResumeCheck)
                    mainHandler.removeCallbacks(resumeCheckRunnable)
                }
            }
        })

        // 注册系统级 AudioPlaybackCallback，实时侦测系统音频停止
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            playbackCallback = object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
                    super.onPlaybackConfigChanged(configs)
                    if (wasPausedByExternalAudio) {
                        val inCall = audioManager.mode != AudioManager.MODE_NORMAL
                        val isPlayingOther = audioManager.isMusicActive || (configs?.isNotEmpty() == true)
                        if (!inCall && !isPlayingOther) {
                            mainHandler.removeCallbacks(resumeCheckRunnable)
                            mainHandler.postDelayed(resumeCheckRunnable, 500)
                        }
                    }
                }
            }
            audioManager.registerAudioPlaybackCallback(playbackCallback!!, mainHandler)
        }

        // 创建通知栏直达 PendingIntent
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        // 创建媒体通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "playback_channel",
                "音乐播放控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示正在播放的音乐控制与通知"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId("playback_channel")
            .build()
        setMediaNotificationProvider(notificationProvider)

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            try {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback!!)
            } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            try {
                audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
            } catch (_: Exception) {}
        }
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}