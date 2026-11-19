package com.satobii.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class FloatingPlayerService : Service() {
    private var player: ExoPlayer? = null
    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
                val index = intent.getIntExtra(EXTRA_INDEX, 0)
                if (uris.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                showOverlay(uris, index)
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "satobii_playback"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId, "Satobii Player playback", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Satobii Player")
            .setContentText("Floating video is playing")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun showOverlay(uris: List<String>, startIndex: Int) {
        stopOverlay()
        val exo = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            setMediaItems(uris.map { MediaItem.fromUri(it) }, startIndex.coerceIn(0, uris.lastIndex), 0L)
            prepare()
            playWhenReady = true
            play()
        }
        player = exo

        val root = FrameLayout(this)
        val video = PlayerView(this).apply {
            player = exo
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        root.addView(video, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(18, 10, 18, 10)
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener {
                stopOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        val closeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        root.addView(close, closeParams)

        val width = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val height = (width * 9f / 16f).toInt()
        val params = WindowManager.LayoutParams(
            width, height,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }

        val dragHandle = TextView(this).apply {
            text = "SATOBII  ·  MOVE"
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(12, 9, 12, 9)
            setBackgroundColor(0xAA111111.toInt())
        }
        val handleParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )
        root.addView(dragHandle, handleParams)
        dragHandle.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0
            private var downY = 0
            private var startX = 0
            private var startY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX.toInt()
                        downY = event.rawY.toInt()
                        startX = params.x
                        startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + event.rawX.toInt() - downX
                        params.y = startY + event.rawY.toInt() - downY
                        try { windowManager?.updateViewLayout(root, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager?.addView(root, params)
            overlay = root
            layoutParams = params
        } catch (_: Exception) {
            exo.release()
            player = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopOverlay() {
        try {
            overlay?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlay = null
        layoutParams = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.satobii.player.FLOAT_START"
        const val ACTION_STOP = "com.satobii.player.FLOAT_STOP"
        const val EXTRA_URIS = "video_uris"
        const val EXTRA_INDEX = "start_index"
        private const val NOTIFICATION_ID = 701
    }
}
