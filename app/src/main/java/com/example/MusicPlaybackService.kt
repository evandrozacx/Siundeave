package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicPlaybackService : Service() {

  companion object {
    const val CHANNEL_ID = "soundwave_media_playback"
    const val NOTIFICATION_ID = 1001

    const val ACTION_START_OR_UPDATE = "com.example.soundwave.START_OR_UPDATE"
    const val ACTION_STOP_SERVICE = "com.example.soundwave.STOP_SERVICE"

    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_ARTIST = "extra_artist"
    const val EXTRA_IS_PLAYING = "extra_is_playing"
    const val EXTRA_COVER_URL = "extra_cover_url"
    const val EXTRA_DURATION_MS = "extra_duration_ms"
    const val EXTRA_POSITION_MS = "extra_position_ms"

    val failedEmbeddedArtSet = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    var isServiceRunning = false
      private set

    fun startOrUpdate(
        context: Context,
        title: String,
        artist: String,
        isPlaying: Boolean,
        coverUrl: String? = null,
        durationMs: Long = 0L,
        positionMs: Long = 0L
    ) {
      val intent = Intent(context, MusicPlaybackService::class.java).apply {
        action = ACTION_START_OR_UPDATE
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_ARTIST, artist)
        putExtra(EXTRA_IS_PLAYING, isPlaying)
        putExtra(EXTRA_COVER_URL, coverUrl)
        putExtra(EXTRA_DURATION_MS, durationMs)
        putExtra(EXTRA_POSITION_MS, positionMs)
      }
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        Log.w("MusicPlaybackService", "Error starting playback service: ${e.message}")
      }
    }

    fun stop(context: Context) {
      val intent = Intent(context, MusicPlaybackService::class.java).apply {
        action = ACTION_STOP_SERVICE
      }
      try {
        context.startService(intent)
      } catch (e: Exception) {
        Log.w("MusicPlaybackService", "Error stopping playback service: ${e.message}")
      }
    }
  }

  private var mediaSession: MediaSession? = null
  private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
  private var currentCoverBitmap: Bitmap? = null
  private var lastCoverUrl: String? = null
  private var wakeLock: android.os.PowerManager.WakeLock? = null
  private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

  private var currentTitle = "SoundWave"
  private var currentArtist = "Tocando agora"
  private var isPlaying = true
  private var currentDurationMs = 0L
  private var currentPositionMs = 0L

  override fun onCreate() {
    super.onCreate()
    isServiceRunning = true
    createNotificationChannel()
    setupMediaSession()
    initLocks()
  }

  private fun initLocks() {
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
      wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SoundWave::BackgroundPlaybackWakeLock")?.apply {
        setReferenceCounted(false)
      }
      val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
      @Suppress("DEPRECATION")
      val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
      } else {
        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
      }
      wifiLock = wm?.createWifiLock(mode, "SoundWave::BackgroundWifiLock")?.apply {
        setReferenceCounted(false)
      }
    } catch (e: Exception) {
      Log.w("MusicPlaybackService", "Failed to init locks: ${e.message}")
    }
  }

  private fun updateLocks(playing: Boolean) {
    try {
      if (playing) {
        if (wakeLock?.isHeld == false) {
          wakeLock?.acquire(4 * 60 * 60 * 1000L) // up to 4h continuous playback
        }
        if (wifiLock?.isHeld == false) {
          wifiLock?.acquire()
        }
      } else {
        if (wakeLock?.isHeld == true) {
          wakeLock?.release()
        }
        if (wifiLock?.isHeld == true) {
          wifiLock?.release()
        }
      }
    } catch (_: Exception) {}
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_SERVICE -> {
        updateLocks(false)
        stopPlaybackNotification()
        return START_NOT_STICKY
      }
      ACTION_START_OR_UPDATE -> {
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "SoundWave"
        currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: "Música"
        isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
        val coverUrl = intent.getStringExtra(EXTRA_COVER_URL)
        currentDurationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        currentPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
        updateLocks(isPlaying)

        if (coverUrl.isNullOrEmpty()) {
          lastCoverUrl = null
          currentCoverBitmap = null
          updateMediaSessionState()
          showNotification()
        } else if (coverUrl != lastCoverUrl) {
          lastCoverUrl = coverUrl
          val cached = CoverLoader.getCached(coverUrl)
          if (cached != null) {
            currentCoverBitmap = cached
            updateMediaSessionState()
            showNotification()
          } else {
            currentCoverBitmap = null
            updateMediaSessionState()
            showNotification()

            val targetUrl = coverUrl
            serviceScope.launch {
              val bmp = CoverLoader.loadBitmap(this@MusicPlaybackService, targetUrl)
              withContext(Dispatchers.Main) {
                if (lastCoverUrl == targetUrl && bmp != null) {
                  currentCoverBitmap = bmp
                  updateMediaSessionState()
                  showNotification()
                }
              }
            }
          }
        } else {
          updateMediaSessionState()
          showNotification()
        }
      }
      else -> {
        showNotification()
      }
    }
    return START_STICKY
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
          NotificationChannel(
              CHANNEL_ID,
              "Reprodução de Música",
              NotificationManager.IMPORTANCE_LOW
          ).apply {
            description = "Controles de reprodução do SoundWave na barra superior"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
          }
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  private fun setupMediaSession() {
    mediaSession = MediaSession(this, "SoundWaveMediaSession").apply {
      setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
      setCallback(object : MediaSession.Callback() {
        override fun onPlay() {
          MainActivity.instance?.handleMediaAction("ACTION_PLAY_PAUSE")
        }
        override fun onPause() {
          MainActivity.instance?.handleMediaAction("ACTION_PLAY_PAUSE")
        }
        override fun onSkipToNext() {
          MainActivity.instance?.handleMediaAction("ACTION_NEXT")
        }
        override fun onSkipToPrevious() {
          MainActivity.instance?.handleMediaAction("ACTION_PREV")
        }
        override fun onStop() {
          MainActivity.instance?.handleMediaAction("ACTION_STOP")
          stopPlaybackNotification()
        }
        override fun onSeekTo(pos: Long) {
          currentPositionMs = pos
          updateMediaSessionState()
          val seconds = pos / 1000.0f
          MainActivity.instance?.seekFromMediaSession(seconds)
        }
      })
      isActive = true
    }
  }

  private fun updateMediaSessionState() {
    val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
    val speed = if (isPlaying) 1.0f else 0.0f
    val playbackState =
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO
            )
            .setState(state, currentPositionMs, speed, android.os.SystemClock.elapsedRealtime())
            .build()
    mediaSession?.setPlaybackState(playbackState)

    val metadataBuilder =
        MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "SoundWave")

    if (currentDurationMs > 0) {
      metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, currentDurationMs)
    }

    if (currentCoverBitmap != null) {
      metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentCoverBitmap)
      metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, currentCoverBitmap)
      metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, currentCoverBitmap)
    }

    mediaSession?.setMetadata(metadataBuilder.build())
  }

  private fun showNotification() {
    val openAppIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    val prevIntent = Intent(this, MediaControlReceiver::class.java).apply {
      action = MediaControlReceiver.ACTION_PREV
    }
    val prevPendingIntent =
        PendingIntent.getBroadcast(
            this,
            1,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    val playPauseIntent = Intent(this, MediaControlReceiver::class.java).apply {
      action = MediaControlReceiver.ACTION_PLAY_PAUSE
    }
    val playPausePendingIntent =
        PendingIntent.getBroadcast(
            this,
            2,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    val nextIntent = Intent(this, MediaControlReceiver::class.java).apply {
      action = MediaControlReceiver.ACTION_NEXT
    }
    val nextPendingIntent =
        PendingIntent.getBroadcast(
            this,
            3,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    val stopIntent = Intent(this, MediaControlReceiver::class.java).apply {
      action = MediaControlReceiver.ACTION_STOP
    }
    val stopPendingIntent =
        PendingIntent.getBroadcast(
            this,
            4,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    val playPauseIcon = if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
    val playPauseTitle = if (isPlaying) "Pausar" else "Reproduzir"

    val prevAction =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_prev),
            "Anterior",
            prevPendingIntent
        ).build()

    val playPauseAction =
        Notification.Action.Builder(
            Icon.createWithResource(this, playPauseIcon),
            playPauseTitle,
            playPausePendingIntent
        ).build()

    val nextAction =
        Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_next),
            "Próxima",
            nextPendingIntent
        ).build()

    val builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          Notification.Builder(this, CHANNEL_ID)
        } else {
          @Suppress("DEPRECATION")
          Notification.Builder(this)
        }

    builder.setSmallIcon(R.drawable.ic_notif_music)
        .setContentTitle(currentTitle)
        .setContentText(currentArtist)
        .setSubText("SoundWave")
        .setContentIntent(contentPendingIntent)
        .setDeleteIntent(stopPendingIntent)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOngoing(isPlaying)
        .addAction(prevAction)
        .addAction(playPauseAction)
        .addAction(nextAction)
        .setStyle(
            Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSession?.sessionToken)
        )

    if (currentCoverBitmap != null) {
      builder.setLargeIcon(currentCoverBitmap)
    } else {
      val defaultIcon = BitmapFactory.decodeResource(resources, R.drawable.soundwave_icon_1793581483680)
      if (defaultIcon != null) {
        builder.setLargeIcon(defaultIcon)
      }
    }

    val notification = builder.build()

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.notify(NOTIFICATION_ID, notification)
    } catch (e: Exception) {
      Log.w("MusicPlaybackService", "Error entering foreground: ${e.message}")
    }
  }

  private fun stopPlaybackNotification() {
    isServiceRunning = false
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    } catch (_: Exception) {}
    stopSelf()
  }

  private fun downloadBitmap(urlStr: String): Bitmap? {
    return CoverLoader.loadBitmap(this, urlStr)
  }

  override fun onDestroy() {
    isServiceRunning = false
    updateLocks(false)
    mediaSession?.release()
    mediaSession = null
    super.onDestroy()
  }
}
