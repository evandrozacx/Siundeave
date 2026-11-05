package com.example

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs

class FloatingBubbleService : Service() {

  companion object {
    const val ACTION_SHOW_BUBBLE = "com.example.soundwave.SHOW_BUBBLE"
    const val ACTION_HIDE_BUBBLE = "com.example.soundwave.HIDE_BUBBLE"
    const val ACTION_UPDATE_INFO = "com.example.soundwave.UPDATE_BUBBLE_INFO"

    const val EXTRA_TRACK_TITLE = "extra_track_title"
    const val EXTRA_IS_PLAYING = "extra_is_playing"

    var isBubbleShowing = false
      private set

    fun show(context: Context, trackTitle: String? = null, isPlaying: Boolean = true) {
      if (!Settings.canDrawOverlays(context)) return
      val intent = Intent(context, FloatingBubbleService::class.java).apply {
        action = ACTION_SHOW_BUBBLE
        putExtra(EXTRA_TRACK_TITLE, trackTitle)
        putExtra(EXTRA_IS_PLAYING, isPlaying)
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

    fun update(context: Context, trackTitle: String, isPlaying: Boolean) {
      if (!isBubbleShowing) return
      val intent = Intent(context, FloatingBubbleService::class.java).apply {
        action = ACTION_UPDATE_INFO
        putExtra(EXTRA_TRACK_TITLE, trackTitle)
        putExtra(EXTRA_IS_PLAYING, isPlaying)
      }
      try {
        context.startService(intent)
      } catch (_: Exception) {}
    }
  }

  private var windowManager: WindowManager? = null
  private var bubbleView: View? = null
  private var params: WindowManager.LayoutParams? = null

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
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
      val channel = android.app.NotificationChannel(
          channelId,
          "Balão Flutuante",
          android.app.NotificationManager.IMPORTANCE_MIN
      ).apply {
        description = "Balão Flutuante SoundWave"
        setShowBadge(false)
      }
      manager.createNotificationChannel(channel)

      val notification = android.app.Notification.Builder(this, channelId)
          .setSmallIcon(R.drawable.ic_notif_music)
          .setContentTitle("SoundWave")
          .setContentText("Balão flutuante ativo")
          .setOngoing(true)
          .build()

      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(
              1002,
              notification,
              android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
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
        createOrUpdateBubble(title, isPlaying)
      }
      ACTION_UPDATE_INFO -> {
        val title = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: "SoundWave"
        val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, true)
        updateBubbleState(title, isPlaying)
      }
      else -> {
        createOrUpdateBubble("SoundWave", true)
      }
    }
    return START_STICKY
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun createOrUpdateBubble(title: String, isPlaying: Boolean) {
    if (!Settings.canDrawOverlays(this)) {
      stopSelf()
      return
    }

    if (bubbleView != null) {
      updateBubbleState(title, isPlaying)
      return
    }

    val density = resources.displayMetrics.density
    val bubbleSize = (58 * density).toInt()

    val layoutType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
          @Suppress("DEPRECATION")
          WindowManager.LayoutParams.TYPE_PHONE
        }

    val windowParams =
        WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
          gravity = Gravity.TOP or Gravity.START
          x = 20
          y = (resources.displayMetrics.heightPixels * 0.45).toInt()
        }
    this.params = windowParams

    // Build the Bubble View layout programmatically for maximum speed & independence
    val container = FrameLayout(this).apply {
      layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)

      // Outer circular background with emerald border and dark glossy center
      val bgDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#064E3B"))
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = (bubbleSize * 0.8f)
        setStroke((2 * density).toInt(), Color.parseColor("#34D399"))
      }
      background = bgDrawable
      elevation = 16 * density
    }

    // App logo / music icon inside the bubble
    val iconView = ImageView(this).apply {
      val iconSize = (32 * density).toInt()
      val lp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
        gravity = Gravity.CENTER
      }
      layoutParams = lp
      setImageResource(R.drawable.soundwave_icon_1793581483680)
      scaleType = ImageView.ScaleType.FIT_CENTER
    }
    container.addView(iconView)

    // Pulsing indicator dot if playing
    val indicator = View(this).apply {
      id = View.generateViewId()
      val dotSize = (10 * density).toInt()
      val lp = FrameLayout.LayoutParams(dotSize, dotSize).apply {
        gravity = Gravity.TOP or Gravity.END
        setMargins(0, (4 * density).toInt(), (4 * density).toInt(), 0)
      }
      layoutParams = lp
      val dotDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (isPlaying) Color.parseColor("#1ED760") else Color.parseColor("#9CA3AF"))
        setStroke((1 * density).toInt(), Color.WHITE)
      }
      background = dotDrawable
    }
    container.addView(indicator)

    // Touch and Drag listener
    container.setOnTouchListener { _, event ->
      val currentParams = params ?: return@setOnTouchListener false
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = currentParams.x
          initialY = currentParams.y
          initialTouchX = event.rawX
          initialTouchY = event.rawY
          isDragging = false
          container.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start()
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
            // TAP DETECTED! Launch app and bring to foreground
            openAppFromBubble()
          } else {
            // Snap to nearest edge (left or right)
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
    } catch (e: Exception) {
      Log.e("FloatingBubble", "Error adding bubble view: ${e.message}")
    }
  }

  private fun snapToEdge(currentParams: WindowManager.LayoutParams) {
    val screenWidth = resources.displayMetrics.widthPixels
    val bubbleWidth = bubbleView?.width ?: (58 * resources.displayMetrics.density).toInt()
    val targetX = if (currentParams.x + bubbleWidth / 2 < screenWidth / 2) {
      12 // Snap to left edge
    } else {
      screenWidth - bubbleWidth - 12 // Snap to right edge
    }
    currentParams.x = targetX
    try {
      windowManager?.updateViewLayout(bubbleView, currentParams)
    } catch (_: Exception) {}
  }

  private fun updateBubbleState(title: String, isPlaying: Boolean) {
    val container = bubbleView as? FrameLayout ?: return
    val indicator = container.getChildAt(1) ?: return
    val density = resources.displayMetrics.density
    val dotDrawable = GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(if (isPlaying) Color.parseColor("#1ED760") else Color.parseColor("#9CA3AF"))
      setStroke((1 * density).toInt(), Color.WHITE)
    }
    indicator.background = dotDrawable
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
    removeBubbleView()
    super.onDestroy()
  }
}
