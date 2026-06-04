package com.zhangbin.cast.phone

import android.util.Log
import okhttp3.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket 信令客户端 — 与电视端信令服务器通信
 */
class SignalingClient(private val serverUrl: String) {

    companion object {
        private const val TAG = "SignalingClient"
        private const val TIMEOUT_SEC = 10L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS) // 长连接
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    /** 信令回调 */
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onOfferReceived: ((SessionDescription) -> Unit)? = null
    var onAnswerReceived: ((SessionDescription) -> Unit)? = null
    var onIceCandidateReceived: ((IceCandidate) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * 连接到电视的信令服务器
     */
    fun connect() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Signaling connected to $serverUrl")
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling closed: $code $reason")
                onDisconnected?.invoke()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Signaling error", t)
                onError?.invoke("信令连接失败: ${t.message}")
                onDisconnected?.invoke()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            when (type) {
                "answer" -> {
                    val sdp = json.getString("sdp")
                    onAnswerReceived?.invoke(
                        SessionDescription(SessionDescription.Type.ANSWER, sdp)
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
                "offer" -> {
                    val sdp = json.getString("sdp")
                    onOfferReceived?.invoke(
                        SessionDescription(SessionDescription.Type.OFFER, sdp)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse signaling message", e)
        }
    }

    /** 发送 SDP Offer */
    fun sendOffer(sdp: SessionDescription) {
        val json = JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp.description)
        }
        webSocket?.send(json.toString())
        Log.i(TAG, "Offer sent")
    }

    /** 发送 ICE Candidate */
    fun sendIceCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("type", "ice_candidate")
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        }
        webSocket?.send(json.toString())
    }

    /** 断开连接 */
    fun disconnect() {
        webSocket?.close(1000, "Client closing")
        client.dispatcher.executorService.shutdown()
    }
}
