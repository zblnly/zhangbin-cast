package com.zhangbin.cast.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 后台发现服务 — 每 3 秒广播电视的存在 + 运行信令服务器
 *
 * 广播格式: "ZHANGBIN_CAST|<设备名>|<信令端口>|<版本>"
 */
class DiscoveryService : Service() {

    companion object {
        private const val TAG = "DiscoveryService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "tv_discovery"
        private const val MULTICAST_ADDR = "239.255.42.42"
        private const val MULTICAST_PORT = 9091
        private const val SIGNALING_PORT = 9090
        private const val DEVICE_NAME = "张斌的电视"
        private const val BROADCAST_INTERVAL = 3000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DiscoveryService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DiscoveryService::class.java))
        }
    }

    private var isRunning = false
    private var broadcastThread: Thread? = null
    private val signalingServer = SignalingServer(SIGNALING_PORT)

    /** WebRTC 接收器 — 由 Activity 设置 */
    var onSignalingReady: ((SignalingServer) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 启动信令服务器
        signalingServer.start()
        Log.i(TAG, "Signaling server started on port $SIGNALING_PORT")

        // 暴露给 Activity
        SignalingServerHolder.server = signalingServer

        // 通知 Activity 信令已就绪
        android.os.Handler(mainLooper).postDelayed({
            onSignalingReady?.invoke(signalingServer)
        }, 500)

        // 启动 UDP 广播
        startUdpBroadcast()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startUdpBroadcast() {
        isRunning = true
        val t = Thread {
            try {
                val message = "ZHANGBIN_CAST|$DEVICE_NAME|$SIGNALING_PORT|1.0"
                val data = message.toByteArray()

                val group = InetAddress.getByName(MULTICAST_ADDR)

                while (isRunning) {
                    try {
                        val packet = DatagramPacket(
                            data, data.size, group, MULTICAST_PORT
                        )
                        DatagramSocket().apply {
                            broadcast = true
                            send(packet)
                            close()
                        }
                        Log.d(TAG, "Broadcast: $message")
                    } catch (e: Exception) {
                        Log.e(TAG, "Broadcast error", e)
                    }

                    Thread.sleep(BROADCAST_INTERVAL)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast thread error", e)
            }
        }
        t.name = "udp-broadcast"
        t.isDaemon = true
        t.start()
        broadcastThread = t

        Log.i(TAG, "UDP broadcast started on $MULTICAST_ADDR:$MULTICAST_PORT")
    }

    override fun onDestroy() {
        isRunning = false
        broadcastThread?.interrupt()
        signalingServer.stop()
        SignalingServerHolder.server = null
        Log.i(TAG, "Discovery service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "投屏接收端",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "张斌的手机投屏接收端正在运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("张斌的手机投屏")
            .setContentText("等待手机连接…")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
