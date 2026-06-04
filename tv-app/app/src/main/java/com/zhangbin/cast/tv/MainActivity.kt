package com.zhangbin.cast.tv

import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * TV 端主界面 — 全屏显示接收到的投屏画面
 *
 * 流程:
 * 1. 启动 DiscoveryService（UDP 广播 + WebSocket 信令服务器）
 * 2. 初始化 WebRTC 接收器
 * 3. 手机发现电视 → 建立 WebSocket 信令 → 交换 SDP → WebRTC 视频流
 * 4. 视频渲染到 SurfaceView，全屏显示
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity-TV"
    }

    // UI
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusOverlay: View
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var container: ConstraintLayout

    // 核心
    private var webRTCReceiver: WebRTCReceiver? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.zhangbin.cast.tv.R.layout.activity_main)

        surfaceView = findViewById(com.zhangbin.cast.tv.R.id.surfaceView)
        statusOverlay = findViewById(com.zhangbin.cast.tv.R.id.statusOverlay)
        statusText = findViewById(com.zhangbin.cast.tv.R.id.statusText)
        ipText = findViewById(com.zhangbin.cast.tv.R.id.ipText)
        container = findViewById(com.zhangbin.cast.tv.R.id.container)

        // 隐藏系统 UI（沉浸模式）
        hideSystemUI()

        // 显示本机 IP
        val localIp = getLocalIpAddress()
        ipText.text = "IP: $localIp"
        updateStatus("📺 等待手机连接…")

        // 启动后台发现服务
        DiscoveryService.start(this)

        // 信令服务器就绪后初始化 WebRTC
        val discoveryService = (applicationContext as android.app.Application)
            .let {
                // 通过全局变量或手动方式获取服务实例
                // 这里通过 Intent 启动服务，信令服务器在 DiscoveryService 内部
            }

        // 监听 DiscoveryService 的信令服务器
        // 由于 DiscoveryService 是独立的 Service，我们需要等它启动
        android.os.Handler(mainLooper).postDelayed({
            initializeWebRTC()
        }, 1000)

        // 点击屏幕切换状态显示
        container.setOnClickListener {
            if (statusOverlay.visibility == View.VISIBLE) {
                statusOverlay.visibility = View.GONE
                hideSystemUI()
            } else {
                statusOverlay.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 初始化 WebRTC 接收器
     */
    private fun initializeWebRTC() {
        // 通过 DiscoveryService 获取信令服务器
        // 因为我们没法直接拿到 Service 内部对象，用全局变量传递
        val signalingServer = SignalingServerHolder.server
            ?: run {
                updateStatus("⚠️ 信令服务器未就绪")
                // 重试
                android.os.Handler(mainLooper).postDelayed({
                    initializeWebRTC()
                }, 2000)
                return
            }

        webRTCReceiver = WebRTCReceiver(signalingServer).apply {
            initialize(surfaceView)

            onConnected = {
                runOnUiThread {
                    isConnected = true
                    updateStatus("✅ 投屏中…")
                    statusOverlay.visibility = View.GONE
                    hideSystemUI()
                }
            }

            onDisconnected = { reason ->
                runOnUiThread {
                    isConnected = false
                    updateStatus("⚠️ $reason")
                    statusOverlay.visibility = View.VISIBLE
                }
            }

            onVideoSizeChanged = { w, h ->
                Log.i(TAG, "Video size: ${w}x${h}")
            }
        }
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
        Log.i(TAG, msg)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "未知"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get IP failed", e)
        }
        return "未知"
    }

    override fun onDestroy() {
        webRTCReceiver?.release()
        DiscoveryService.stop(this)
        super.onDestroy()
    }
}

/**
 * 全局信令服务器引用 — 用于 DiscoveryService 向 Activity 传递实例
 */
object SignalingServerHolder {
    var server: com.zhangbin.cast.tv.SignalingServer? = null
}
