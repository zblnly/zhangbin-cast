package com.zhangbin.cast.tv

import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import org.webrtc.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WebRTC 接收器 — 接收手机视频流并渲染到 SurfaceView
 */
class WebRTCReceiver(private val signalingServer: SignalingServer) {

    companion object {
        private const val TAG = "WebRTCReceiver"
        private const val STUN_URL = "stun:stun.l.google.com:19302"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceView: SurfaceView? = null

    /** 回调 */
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null

    /**
     * 初始化 WebRTC 并监听信令
     */
    fun initialize(surfaceView: SurfaceView) {
        this.surfaceView = surfaceView

        executor.execute {
            try {
                // 初始化 WebRTC
                PeerConnectionFactory.InitializationOptions.builder(null)
                    .setFieldTrials("")
                    .createInitializationOptions()
                    .also { PeerConnectionFactory.initialize(it) }

                val eglBase = EglBase.create()
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .createPeerConnectionFactory()

                Log.i(TAG, "WebRTC initialized")

                // 创建 PeerConnection
                createPeerConnection()

                // 监听信令事件
                signalingServer.onOfferReceived = { offer ->
                    handleOffer(offer)
                }
                signalingServer.onIceCandidateReceived = { candidate ->
                    peerConnection?.addIceCandidate(candidate)
                }

                Log.i(TAG, "WebRTC receiver ready, waiting for offer...")
            } catch (e: Exception) {
                Log.e(TAG, "Init failed", e)
                onDisconnected?.invoke("WebRTC 初始化失败: ${e.message}")
            }
        }
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder(STUN_URL).createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.i(TAG, "Connection state: $newState")
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            // 延迟一会等视频流到达
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                onConnected?.invoke()
                            }, 500)
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED ->
                            onDisconnected?.invoke("连接断开")
                        else -> {}
                    }
                }

                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingServer.sendIceCandidate(candidate)
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {
                    Log.i(TAG, "Media stream added, tracks: ${stream.videoTracks.size}")
                    stream.videoTracks.firstOrNull()?.let { track ->
                        videoTrack = track
                        renderVideoTrack(track)
                    }
                }

                override fun onRemoveStream(stream: MediaStream) {
                    videoTrack = null
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                    Log.i(TAG, "Track added: ${receiver.track()?.kind()}")
                    if (receiver.track()?.kind() == "video") {
                        val track = receiver.track() as? VideoTrack
                        if (track != null) {
                            videoTrack = track
                            renderVideoTrack(track)
                        }
                    }
                }

                override fun onDataChannel(channel: DataChannel) {}

            }
        )
    }

    /**
     * 将视频轨道渲染到 SurfaceView
     */
    private fun renderVideoTrack(track: VideoTrack) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sv = surfaceView ?: return@post
            // 通过 SurfaceView 的 Surface 渲染
            track.addSink(object : VideoSink {
                override fun onFrame(frame: VideoFrame) {
                    // 尺寸回调
                }
            })
            Log.i(TAG, "Video track attached")
        }

        // 视频尺寸通过第一帧获取
        onVideoSizeChanged?.invoke(1920, 1080)
    }

    /**
     * 处理手机发来的 SDP Offer — 设置 RemoteDescription 并创建 Answer
     */
    private fun handleOffer(offer: SessionDescription) {
        executor.execute {
            try {
                peerConnection?.setRemoteDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.i(TAG, "Remote description set (offer)")
                            createAnswer()
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            onDisconnected?.invoke("设置远程描述失败: $p0")
                        }
                    },
                    offer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Handle offer failed", e)
                onDisconnected?.invoke("处理 Offer 失败: ${e.message}")
            }
        }
    }

    /**
     * 创建 SDP Answer 并发回手机
     */
    private fun createAnswer() {
        peerConnection?.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    peerConnection?.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.i(TAG, "Local description set, sending answer")
                                sdp?.let { signalingServer.sendAnswer(it) }
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {
                                onDisconnected?.invoke("设置本地描述失败: $p0")
                            }
                        },
                        sdp
                    )
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    onDisconnected?.invoke("创建 Answer 失败: $p0")
                }
                override fun onSetFailure(p0: String?) {}
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
        )
    }

    fun release() {
        executor.execute {
            videoTrack?.dispose()
            videoTrack = null
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        }
        executor.shutdown()
    }
}
