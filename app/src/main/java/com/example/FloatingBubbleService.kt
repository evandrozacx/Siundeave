package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingBubbleService : Service() {

  companion object {
    const val ACTION_SHOW_BUBBLE = "com.example.soundwave.SHOW_BUBBLE"
    const val ACTION_HIDE_BUBBLE = "com.example.soundwave.HIDE_BUBBLE"
    const val ACTION_UPDATE_INFO = "com.example.soundwave.UPDATE_BUBBLE_INFO"

    const val EXTRA_TRACK_TITLE = "extra_track_title"
    const val EXTRA_IS_PLAYING = "extra_is_playing"
    const val EXTRA_COVER_URL = "extra_cover_url"

    var isBubbleShowing = false
      private set

    fun show(
        context: Context,
        trackTitle: String? = null,
        isPlaying: Boolean = true,
        coverUrl: String? = null
    ) {
      if (!Settings.canDrawOverlays(context)) return
      val intent = Intent(context, FloatingBubbleService::class.java).apply {
        action = ACTION_SHOW_BUBBLE
        putExtra(EXTRA_TRACK_TITLE, trackTitle)
        putExtra(EXTRA_IS_PLAYING, isPlaying)
        putExtra(EXTRA_COVER_URL, coverUrl)
      }
      try {
        context.startService(intent)
      } catch (e: Exception) {
        Log.w("FloatingBubble", "Failed to start floating bubble service: ${e.message}")
      }
    }

    fun hide(context: Context) {
      val intent = Intent(context, FloatingBubbleService::class.java).apply {
        action = ACTION_HIDE_BUBBLE
      }
      try {
        context.startService(intent)
      } catch (e: Exception) {
        Log.w("FloatingBubble", "Failed to stop floating bubble service: ${e.message}")
      }
    }

    fun update(
        context: Context,
        trackTitle: String,
        isPlaying: Boolean,
        coverUrl: String? = null
    ) {
      if (!isBubbleShowing) return
      val intent = Intent(context, FloatingBubbleService::class.java).apply {
        action = ACTION_UPDATE_INFO
        putExtra(EXTRA_TRACK_TITLE, trackTitle)
        putExtra(EXTRA_IS_PLAYING, isPlaying)
        putExtra(EXTRA_COVER_URL, coverUrl)
      }
      try {
        context.startService(intent)
      } catch (_: Exception) {}
    }
  }

  private var windowManager: WindowManager? = null
  private var bubbleView: View? = null
  private var params: WindowManager.LayoutParams? = null
  private var titleTextView: TextView? = null
  private var playPauseBtnView: ImageView? = null
  private var indicatorView: View? = null
  private var coverImageView: ImageView? = null

  private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
  private var currentCoverUrl: String? = null
  private var currentCoverBitmap: Bitmap? = null

  private var initialX = 0
  private var initialY = 0
  private var initialTouchX = 0f
  private var initialTouchY = 0f
  private var isDragging = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  private fun startForegroundIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channelId = "soundwave_bubble_channel"
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel = NotificationChannel(
          channelId,
          "Mini Player Flutuante",
          NotificationManager.IMPORTANCE_MIN
      ).apply {
        description = "Mini Player Flutuante SoundWave em Segundo Plano"
        setShowBadge(false)
      }
      manager.createNotificationChannel(channel)

      val notification = Notification.Builder(this, channelId)
          .setSmallIcon(R.drawable.ic_notif_music)
          .setContentTitle("SoundWave")
          .setContentText("Mini player em segundo plano ativo")
          .setOngoing(true)
          .build()

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(
              1002,
              notification,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
          )
        } else {
          startForeground(1002, notification)
        }
      } catch (e: Exception) {
        Log.w("FloatingBubble", "startForeground error: ${e.message}")
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForegroundIfNeeded()
    when (intent?.action) {
      ACTION_HIDE_BUBBLE -> {
        removeBubbleView()
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_SHOW_BUBBLE -> {
        val title = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: "SoundWave"
        val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
        val coverUrl = intent.getStringExtra(EXTRA_COVER_URL)
        createOrUpdateBubble(title, isPlaying, coverUrl)
      }
      ACTION_UPDATE_INFO -> {
        val title = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: "SoundWave"
        val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
        val coverUrl = intent.getStringExtra(EXTRA_COVER_URL)
        updateBubbleState(title, isPlaying, coverUrl)
      }
      else -> {
        createOrUpdateBubble("SoundWave", true, null)
      }
    }
    return START_STICKY
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun createOrUpdateBubble(title: String, isPlaying: Boolean, coverUrl: String?) {
    if (!Settings.canDrawOverlays(this)) {
      stopSelf()
      return
    }

    if (bubbleView != null) {
      updateBubbleState(title, isPlaying, coverUrl)
      return
    }

    val density = resources.displayMetrics.density
    val miniWidth = (240 * density).toInt()
    val miniHeight = (54 * density).toInt()

    val layoutType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
          @Suppress("DEPRECATION")
          WindowManager.LayoutParams.TYPE_PHONE
        }

    val windowParams =
        WindowManager.LayoutParams(
            miniWidth,
            miniHeight,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
          gravity = Gravity.TOP or Gravity.START
          x = 16
          y = (resources.displayMetrics.heightPixels * 0.12).toInt().coerceAtLeast(32)
        }
    this.params = windowParams

    // Build a compact Floating Mini-Player Pill programmatically
    val container = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      layoutParams = FrameLayout.LayoutParams(miniWidth, miniHeight)
      val padH = (10 * density).toInt()
      val padV = (6 * density).toInt()
      setPadding(padH, padV, padH, padV)

      val bgDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28 * density
        colors = intArrayOf(Color.parseColor("#F00F172A"), Color.parseColor("#F5064E3B"))
        orientation = GradientDrawable.Orientation.LEFT_RIGHT
        setStroke((1.5f * density).toInt(), Color.parseColor("#34D399"))
      }
      background = bgDrawable
      elevation = 16 * density
    }

    // Left icon + status dot wrapper
    val iconWrapper = FrameLayout(this).apply {
      val wrapSize = (38 * density).toInt()
      layoutParams = LinearLayout.LayoutParams(wrapSize, wrapSize).apply {
        marginEnd = (8 * density).toInt()
      }
    }

    val iconView = ImageView(this).apply {
      val iconSize = (34 * density).toInt()
      layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
        gravity = Gravity.CENTER
      }
      scaleType = ImageView.ScaleType.CENTER_CROP
      outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
          outline.setRoundRect(0, 0, view.width, view.height, 8 * density)
        }
      }
      clipToOutline = true
      setImageResource(R.drawable.soundwave_icon_1793581483680)
    }
    this.coverImageView = iconView
    iconWrapper.addView(iconView)

    val indicator = View(this).apply {
      val dotSize = (9 * density).toInt()
      layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
        gravity = Gravity.BOTTOM or Gravity.END
      }
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (isPlaying) Color.parseColor("#1ED760") else Color.parseColor("#9CA3AF"))
        setStroke((1 * density).toInt(), Color.WHITE)
      }
    }
    this.indicatorView = indicator
    iconWrapper.addView(indicator)
    container.addView(iconWrapper)

    // Song title text in the middle
    val titleTv = TextView(this).apply {
      layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = (6 * density).toInt()
      }
      text = title
      setTextColor(Color.WHITE)
      textSize = 12.5f
      maxLines = 1
      ellipsize = android.text.TextUtils.TruncateAt.END
      paint.isFakeBoldText = true
    }
    this.titleTextView = titleTv
    container.addView(titleTv)

    // Play/Pause button inside the floating mini-player
    val playPauseBtn = ImageView(this).apply {
      val btnSize = (34 * density).toInt()
      layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
        marginEnd = (4 * density).toInt()
      }
      val pad = (6 * density).toInt()
      setPadding(pad, pad, pad, pad)
      setImageResource(if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play)
      setColorFilter(Color.WHITE)
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor("#3310B981"))
      }
      setOnClickListener {
        MainActivity.instance?.handleMediaAction("ACTION_PLAY_PAUSE")
      }
    }
    this.playPauseBtnView = playPauseBtn
    container.addView(playPauseBtn)

    // Next track button inside the floating mini-player
    val nextBtn = ImageView(this).apply {
      val btnSize = (32 * density).toInt()
      layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
      val pad = (6 * density).toInt()
      setPadding(pad, pad, pad, pad)
      setImageResource(R.drawable.ic_notif_next)
      setColorFilter(Color.WHITE)
      setOnClickListener {
        MainActivity.instance?.handleMediaAction("ACTION_NEXT")
      }
    }
    container.addView(nextBtn)

    // Touch and Drag listener on the mini-player pill
    container.setOnTouchListener { _, event ->
      val currentParams = params ?: return@setOnTouchListener false
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = currentParams.x
          initialY = currentParams.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          isDragging = false
          container.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
          true
        }
        MotionEvent.ACTION_MOVE -> {
          val dx = event.rawX - initialTouchX
          val dy = event.rawY - initialTouchY
          if (abs(dx) > 10 || abs(dy) > 10) {
            isDragging = true
            currentParams.x = (initialX + dx).toInt()
            currentParams.y = (initialY + dy).toInt()
            try {
              windowManager?.updateViewLayout(bubbleView, currentParams)
            } catch (_: Exception) {}
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          container.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
          val dx = abs(event.rawX - initialTouchX)
          val dy = abs(event.rawY - initialTouchY)
          if (!isDragging && dx < 15 && dy < 15) {
            openAppFromBubble()
          } else {
            snapToEdge(currentParams)
          }
          true
        }
        else -> false
      }
    }

    try {
      windowManager?.addView(container, windowParams)
      bubbleView = container
      isBubbleShowing = true
      updateCoverArt(coverUrl)
    } catch (e: Exception) {
      Log.e("FloatingBubble", "Error adding bubble view: ${e.message}")
    }
  }

  private fun snapToEdge(currentParams: WindowManager.LayoutParams) {
    val screenWidth = resources.displayMetrics.widthPixels
    val bubbleWidth = bubbleView?.width ?: (240 * resources.displayMetrics.density).toInt()
    val targetX = if (currentParams.x + bubbleWidth / 2 < screenWidth / 2) {
      12 // Snap to left edge
    } else {
      (screenWidth - bubbleWidth - 12).coerceAtLeast(12) // Snap to right edge
    }
    currentParams.x = targetX
    try {
      windowManager?.updateViewLayout(bubbleView, currentParams)
    } catch (_: Exception) {}
  }

  private fun updateBubbleState(title: String, isPlaying: Boolean, coverUrl: String?) {
    titleTextView?.text = title
    playPauseBtnView?.setImageResource(
        if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play
    )
    val density = resources.displayMetrics.density
    indicatorView?.background = GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(if (isPlaying) Color.parseColor("#1ED760") else Color.parseColor("#9CA3AF"))
      setStroke((1 * density).toInt(), Color.WHITE)
    }
    updateCoverArt(coverUrl)
  }

  private fun updateCoverArt(coverUrl: String?) {
    if (coverUrl.isNullOrEmpty()) {
      currentCoverUrl = null
      currentCoverBitmap = null
      coverImageView?.setImageResource(R.drawable.soundwave_icon_1793581483680)
      return
    }

    if (coverUrl == currentCoverUrl && currentCoverBitmap != null) {
      coverImageView?.setImageBitmap(currentCoverBitmap)
      return
    }

    currentCoverUrl = coverUrl
    val cached = CoverLoader.getCached(coverUrl)
    if (cached != null) {
      currentCoverBitmap = cached
      coverImageView?.setImageBitmap(cached)
      return
    }

    // Load asynchronously via CoverLoader
    val target = coverUrl
    serviceScope.launch {
      val bmp = CoverLoader.loadBitmap(this@FloatingBubbleService, target)
      withContext(Dispatchers.Main) {
        if (currentCoverUrl == target) {
          currentCoverBitmap = bmp
          if (bmp != null) {
            coverImageView?.setImageBitmap(bmp)
          } else {
            coverImageView?.setImageResource(R.drawable.soundwave_icon_1793581483680)
          }
        }
      }
    }
  }

  private fun openAppFromBubble() {
    try {
      val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
      }
      startActivity(intent)
      // Hide bubble while inside the app
      removeBubbleView()
      stopSelf()
    } catch (e: Exception) {
      Log.e("FloatingBubble", "Failed to open app from bubble: ${e.message}")
    }
  }

  private fun removeBubbleView() {
    isBubbleShowing = false
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    } catch (_: Exception) {}
    bubbleView?.let {
      try {
        windowManager?.removeView(it)
      } catch (_: Exception) {}
    }
    bubbleView = null
    params = null
  }

  override fun onDestroy() {
    serviceScope.cancel()
    removeBubbleView()
    super.onDestroy()
  }
}
