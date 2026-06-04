package com.zhangbin.cast.phone

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 网络发现 — 通过 UDP 多播发现电视设备
 *
 * 电视端每 3 秒广播一次存在消息 (UDP 多播 239.255.42.42:9091)
 * 手机端监听多播，收到后解析设备 IP 和名称
 */
class DiscoveryClient {

    companion object {
        private const val TAG = "DiscoveryClient"
        private const val MULTICAST_ADDR = "239.255.42.42"
        private const val MULTICAST_PORT = 9091
        private const val BUF_SIZE = 1024

        /** 发现消息格式: "ZHANGBIN_CAST|<设备名>|<信令端口>|<版本>" */
        private const val DISCOVERY_PREFIX = "ZHANGBIN_CAST"
    }

    private var isRunning = false
    private var socket: DatagramSocket? = null

    /** 发现设备回调 */
    var onDeviceFound: ((TVDevice) -> Unit)? = null

    /**
     * 开始监听电视设备的广播
     */
    fun startDiscovery() {
        if (isRunning) return
        isRunning = true

        Thread("discovery-listener") {
            try {
                val group = InetAddress.getByName(MULTICAST_ADDR)
                socket = DatagramSocket(MULTICAST_PORT).apply {
                    reuseAddress = true
                    soTimeout = 5000
                }

                val buf = ByteArray(BUF_SIZE)

                while (isRunning) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket?.receive(packet)

                        val message = String(packet.data, 0, packet.length)
                        if (message.startsWith(DISCOVERY_PREFIX)) {
                            handleDiscoveryMessage(message, packet.address.hostAddress ?: "")
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // 正常超时，继续
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            } finally {
                socket?.close()
            }
        }.apply { isDaemon = true }.start()

        Log.i(TAG, "Discovery started, listening on port $MULTICAST_PORT")
    }

    private fun handleDiscoveryMessage(message: String, sourceIp: String) {
        try {
            val parts = message.split("|")
            if (parts.size >= 2) {
                val device = TVDevice(
                    name = parts[1],
                    ip = sourceIp,
                    signalingPort = parts.getOrElse(2) { "9090" }.toIntOrNull() ?: 9090,
                    version = parts.getOrElse(3) { "1.0" }
                )
                Log.i(TAG, "Device discovered: ${device.name} at ${device.ip}")
                onDeviceFound?.invoke(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse discovery message failed: $message", e)
        }
    }

    /**
     * 手动发送发现请求（当自动发现不生效时）
     */
    fun sendDiscoveryRequest() {
        Thread("discovery-request") {
            try {
                val message = "${DISCOVERY_PREFIX}|QUERY"
                val packet = DatagramPacket(
                    message.toByteArray(),
                    message.length,
                    InetAddress.getByName(MULTICAST_ADDR),
                    MULTICAST_PORT
                )
                DatagramSocket().apply {
                    send(packet)
                    close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send discovery request failed", e)
            }
        }.apply { isDaemon = true }.start()
    }

    fun stopDiscovery() {
        isRunning = false
        socket?.close()
        Log.i(TAG, "Discovery stopped")
    }
}
