package com.zhangbin.cast.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat

/**
 * 屏幕捕获前台服务
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture"
        private const val VIRTUAL_DISPLAY_NAME = "ZhangbinCastCapture"

        /** 静态传递 SurfaceTexture（Parcelable 不支持直接传） */
        var pendingSurfaceTexture: SurfaceTexture? = null

        /** 启动服务 */
        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            surfaceTexture: SurfaceTexture,
            callback: (Surface) -> Unit
        ) {
            pendingSurfaceTexture = surfaceTexture
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            context.startForegroundService(intent)
        }

        /** 停止服务 */
        fun stop(context: Context) {
            pendingSurfaceTexture = null
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var onSurfaceCreated: ((Surface) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        val surfaceTexture = companion.pendingSurfaceTexture

        if (resultCode != -1 && data != null && surfaceTexture != null) {
            startCapture(resultCode, data, surfaceTexture)
        } else {
            Log.e(TAG, "Missing required extras")
            stopSelf()
        }

        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent, surfaceTexture: SurfaceTexture) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        val surface = Surface(surfaceTexture)
        val displayMetrics = resources.displayMetrics

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        Log.i(TAG, "Virtual display created: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        Log.i(TAG, "Screen capture service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "屏幕投屏",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "张斌的手机投屏正在运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("张斌的手机投屏")
            .setContentText("正在投屏到电视…")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
