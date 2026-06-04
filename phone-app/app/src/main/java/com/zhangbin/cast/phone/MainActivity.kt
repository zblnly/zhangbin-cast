package com.zhangbin.cast.phone

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper

/**
 * 手机端主界面 — 发现电视 → 开始投屏
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1000
    }

    // UI
    private lateinit var statusText: TextView
    private lateinit var deviceListText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var textureView: TextureView

    // 核心模块
    private val discoveryClient = DiscoveryClient()
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var discoveredDevice: TVDevice? = null
    private var isCasting = false
    private var mediaProjectionResultCode = -1
    private var mediaProjectionData: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.zhangbin.cast.phone.R.layout.activity_main)

        statusText = findViewById(com.zhangbin.cast.phone.R.id.statusText)
        deviceListText = findViewById(com.zhangbin.cast.phone.R.id.deviceListText)
        startButton = findViewById(com.zhangbin.cast.phone.R.id.startButton)
        stopButton = findViewById(com.zhangbin.cast.phone.R.id.stopButton)
        textureView = findViewById(com.zhangbin.cast.phone.R.id.previewView)

        updateStatus("正在搜索电视设备…")

        // 初始化 EGL（WebRTC 需要）
        eglBase = EglBase.create()

        // 设置 TextureView 监听
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // 发现设备
        discoveryClient.onDeviceFound = { device ->
            runOnUiThread {
                discoveredDevice = device
                deviceListText.text = "📺 ${device.name}\nIP: ${device.ip}"
                startButton.isEnabled = true
                updateStatus("✅ 找到设备: ${device.name}")
            }
        }
        discoveryClient.startDiscovery()
        // 主动请求一次
        discoveryClient.sendDiscoveryRequest()

        // 按钮
        startButton.setOnClickListener {
            if (discoveredDevice != null) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "未发现电视设备", Toast.LENGTH_SHORT).show()
                discoveryClient.sendDiscoveryRequest()
            }
        }

        stopButton.setOnClickListener {
            stopCasting()
        }

        // 手动搜索
        findViewById<Button>(com.zhangbin.cast.phone.R.id.searchButton).setOnClickListener {
            updateStatus("正在搜索…")
            discoveryClient.sendDiscoveryRequest()
        }
    }

    /**
     * 请求屏幕捕获权限
     */
    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjectionResultCode = resultCode
                mediaProjectionData = data
                startCasting()
            } else {
                Toast.makeText(this, "需要屏幕共享权限", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 开始投屏
     */
    private fun startCasting() {
        val device = discoveredDevice ?: return

        updateStatus("正在连接 ${device.name}…")
        isCasting = true
        startButton.isEnabled = false
        stopButton.isEnabled = true

        // 1. 连接信令服务器
        signalingClient = SignalingClient(device.signalingUrl).apply {
            onConnected = {
                runOnUiThread { updateStatus("🔗 信令已连接，建立 WebRTC…") }
            }
            onError = { msg ->
                runOnUiThread {
                    updateStatus("❌ $msg")
                    stopCasting()
                }
            }
            onDisconnected = {
                runOnUiThread {
                    updateStatus("⚠️ 信令断开")
                    stopCasting()
                }
            }
            connect()
        }

        // 2. 初始化 WebRTC
        webRTCManager = WebRTCManager(eglBase!!, signalingClient!!).apply {
            onConnected = {
                runOnUiThread { updateStatus("✅ 投屏中…") }
            }
            onDisconnected = { reason ->
                runOnUiThread {
                    updateStatus("⚠️ $reason")
                    stopCasting()
                }
            }
            onError = { msg ->
                runOnUiThread {
                    updateStatus("❌ $msg")
                    stopCasting()
                }
            }
            initialize()
            // 等一会再创建连接（让信令先连上）
            android.os.Handler(mainLooper).postDelayed({
                createPeerConnection()
                // 启动屏幕捕获
                val st = surfaceTextureHelper ?: return@postDelayed
                ScreenCaptureService.start(
                    this@MainActivity,
                    mediaProjectionResultCode,
                    mediaProjectionData!!,
                    st.surfaceTexture,
                    callback = { surface ->
                        webRTCManager?.startVideoCapture(st, surface)
                    }
                )
            }, 1000)
        }
    }

    private fun stopCasting() {
        if (!isCasting) return
        isCasting = false
        startButton.isEnabled = true
        stopButton.isEnabled = false

        ScreenCaptureService.stop(this)
        webRTCManager?.release()
        webRTCManager = null
        signalingClient?.disconnect()
        signalingClient = null

        updateStatus("⏹️ 投屏已停止")
        deviceListText.text = if (discoveredDevice != null) "📺 ${discoveredDevice!!.name}" else ""
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
        Log.i(TAG, msg)
    }

    override fun onDestroy() {
        stopCasting()
        discoveryClient.stopDiscovery()
        surfaceTextureHelper = null
        eglBase?.release()
        super.onDestroy()
    }
}
