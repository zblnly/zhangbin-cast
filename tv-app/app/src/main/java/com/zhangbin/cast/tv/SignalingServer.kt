package com.zhangbin.cast.tv

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket 信令服务器 — 接收手机的 SDP Offer 并返回 Answer
 *
 * 基于 OkHttp 的 WebSocket 服务器（内嵌在 TV 端）
 */
class SignalingServer(private val port: Int = 9090) {

    companion object {
        private const val TAG = "SignalingServer"
    }

    private var server: ServerSocket? = null
    private var isRunning = false
    private val clients = ConcurrentHashMap<Int, WebSocket>()

    /** 信令事件回调 */
    var onOfferReceived: ((SessionDescription) -> Unit)? = null
    var onIceCandidateReceived: ((IceCandidate) -> Unit)? = null
    var onClientConnected: (() -> Unit)? = null
    var onClientDisconnected: (() -> Unit)? = null

    /**
     * 启动 WebSocket 服务器
     */
    fun start() {
        if (isRunning) return
        isRunning = true

        Thread {
            try {
                val serverSocket = java.net.ServerSocket(port)
                server = serverSocket
                Log.i(TAG, "Signaling server started on port $port")

                while (isRunning) {
                    try {
                        val socket = serverSocket.accept()
                        Log.i(TAG, "Client connected from ${socket.inetAddress.hostAddress}")
                        handleClient(socket)
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Accept error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
            }
        }.apply { name = "signaling-server"; isDaemon = true }.start()
    }

    /**
     * 处理 WebSocket 握手和消息（简易实现）
     *
     * 这里用原始 Socket + 简易 HTTP 升级，避免引入额外依赖。
     * 消息格式: JSON <-> 手机端
     */
    private fun handleClient(socket: java.net.Socket) {
        Thread {
            try {
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                // 读取 HTTP 升级请求
                val requestLines = mutableListOf<String>()
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    requestLines.add(line)
                    line = reader.readLine()
                }

                val firstLine = requestLines.firstOrNull()
                if (firstLine == null || !firstLine.contains("Upgrade: websocket", ignoreCase = true)
                    && requestLines.none { it.contains("Upgrade") }) {
                    // 不是 WebSocket 请求
                    socket.close()
                    return@Thread
                }

                // 简易 WebSocket 握手
                val key = requestLines
                    .find { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    ?.substringAfter(":")
                    ?.trim() ?: ""

                val acceptKey = generateAcceptKey(key)
                writer.write("HTTP/1.1 101 Switching Protocols\r\n")
                writer.write("Upgrade: websocket\r\n")
                writer.write("Connection: Upgrade\r\n")
                writer.write("Sec-WebSocket-Accept: $acceptKey\r\n")
                writer.write("\r\n")
                writer.flush()

                Log.i(TAG, "WebSocket handshake complete")

                // 通知客户端连接
                runOnUiThread { onClientConnected?.invoke() }

                // 读取 WebSocket 帧
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(65536)

                while (isRunning && socket.isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break

                    val message = decodeWebSocketFrame(buffer, bytesRead)
                    if (message != null) {
                        handleMessage(message)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Client handler error", e)
            } finally {
                try { socket.close() } catch (_: Exception) {}
                runOnUiThread { onClientDisconnected?.invoke() }
            }
        }.apply { name = "ws-client-${socket.inetAddress.hostAddress}"; isDaemon = true }.start()
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            when (type) {
                "offer" -> {
                    val sdp = json.getString("sdp")
                    Log.i(TAG, "Received SDP offer")
                    onOfferReceived?.invoke(
                        SessionDescription(SessionDescription.Type.OFFER, sdp)
                    )
                }
                "ice_candidate" -> {
                    val sdpMid = json.getString("sdpMid")
                    val sdpMLineIndex = json.getInt("sdpMLineIndex")
                    val candidate = json.getString("candidate")
                    onIceCandidateReceived?.invoke(
                        IceCandidate(sdpMid, sdpMLineIndex, candidate)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse message failed: $text", e)
        }
    }

    private var webSocketOutput: java.io.OutputStream? = null

    /** 发送 SDP Answer 给手机 */
    fun sendAnswer(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp.description)
        }
        sendMessage(json.toString())
    }

    /** 发送 ICE Candidate 给手机 */
    fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "ice_candidate")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        sendMessage(json.toString())
    }

    private fun sendMessage(text: String) {
        // 发送 WebSocket 文本帧
        val frameData = text.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(frameData.size + 2)
        frame[0] = 0x81.toByte()  // FIN + text opcode
        frame[1] = frameData.size.toByte()
        System.arraycopy(frameData, 0, frame, 2, frameData.size)

        try {
            webSocketOutput?.write(frame)
            webSocketOutput?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send message failed", e)
        }
    }

    fun stop() {
        isRunning = false
        try { server?.close() } catch (_: Exception) {}
        Log.i(TAG, "Signaling server stopped")
    }

    // ---- 辅助方法 ----

    private fun generateAcceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-5AB5DC11B735"
        import java.security.MessageDigest
        val digest = MessageDigest.getInstance("SHA-1").digest((key + magic).toByteArray())
        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    private fun decodeWebSocketFrame(buffer: ByteArray, length: Int): String? {
        if (length < 2) return null
        val opcode = buffer[0].toInt() and 0x0f
        if (opcode != 0x01) return null  // 只处理文本帧

        val masked = (buffer[1].toInt() and 0x80) != 0
        var payloadLen = buffer[1].toInt() and 0x7f
        var offset = 2

        if (payloadLen == 126) {
            payloadLen = ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
            offset = 4
        } else if (payloadLen == 127) {
            payloadLen = 0
            offset = 10 // 跳过 8 字节长度（简单实现只取低 32 位）
        }

        val mask = if (masked) {
            val m = ByteArray(4)
            System.arraycopy(buffer, offset, m, 0, 4)
            offset += 4
            m
        } else null

        val payload = ByteArray(payloadLen)
        System.arraycopy(buffer, offset, payload, 0, payloadLen)

        // 解掩码
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }

        return String(payload, Charsets.UTF_8)
    }

    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }
}
