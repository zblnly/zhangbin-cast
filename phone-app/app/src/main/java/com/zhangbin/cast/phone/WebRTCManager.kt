package com.zhangbin.cast.phone

import android.util.Log
import android.view.Surface
import org.webrtc.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WebRTC 管理器 — 负责建立连接、发送视频流
 */
class WebRTCManager(
    private val eglBase: EglBase,
    private val signalingClient: SignalingClient
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private const val STUN_URL = "stun:stun.l.google.com:19302"
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    /** 连接状态回调 */
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * 初始化 PeerConnectionFactory
     */
    fun initialize() {
        executor.execute {
            try {
                // 初始化 WebRTC
                PeerConnectionFactory.InitializationOptions.builder(/* appContext */ null)
                    .setFieldTrials("")
                    .createInitializationOptions()
                    .also { PeerConnectionFactory.initialize(it) }

                val options = PeerConnectionFactory.Options()
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .createPeerConnectionFactory()

                Log.i(TAG, "WebRTC initialized")
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC init failed", e)
                onError?.invoke("WebRTC 初始化失败: ${e.message}")
            }
        }
    }

    /**
     * 创建连接到电视的 PeerConnection
     */
    fun createPeerConnection() {
        executor.execute {
            try {
                val iceServers = listOf(
                    PeerConnection.IceServer.builder(STUN_URL).createIceServer()
                )
                val config = PeerConnection.RTCConfiguration(iceServers)
                config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

                peerConnection = peerConnectionFactory?.createPeerConnection(
                    config,
                    object : PeerConnection.Observer {
                        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                            Log.i(TAG, "Connection state: $newState")
                            when (newState) {
                                PeerConnection.PeerConnectionState.CONNECTED ->
                                    onConnected?.invoke()
                                PeerConnection.PeerConnectionState.DISCONNECTED,
                                PeerConnection.PeerConnectionState.FAILED ->
                                    onDisconnected?.invoke("连接断开")
                                else -> {}
                            }
                        }

                        override fun onIceCandidate(candidate: IceCandidate) {
                            signalingClient.sendIceCandidate(candidate)
                        }

                        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                        override fun onAddStream(stream: MediaStream) {}
                        override fun onRemoveStream(stream: MediaStream) {}
                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                        override fun onRenegotiationNeeded() {}
                        override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {}
                        override fun onDataChannel(channel: DataChannel) {}

                    }
                )

                // 监听信令消息
                signalingClient.onOfferReceived = { sdp ->
                    // 手机端是发起方，不应该接收 offer
                }
                signalingClient.onAnswerReceived = { sdp ->
                    peerConnection?.setRemoteDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.i(TAG, "Remote description set")
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {
                                onError?.invoke("设置远程描述失败: $p0")
                            }
                        },
                        sdp
                    )
                }
                signalingClient.onIceCandidateReceived = { candidate ->
                    peerConnection?.addIceCandidate(candidate)
                }

                Log.i(TAG, "PeerConnection created")
            } catch (e: Exception) {
                Log.e(TAG, "Create PeerConnection failed", e)
                onError?.invoke("创建连接失败: ${e.message}")
            }
        }
    }

    /**
     * 创建视频轨道并开始捕获屏幕
     */
    fun startVideoCapture(surfaceTextureHelper: SurfaceTextureHelper, surface: Surface) {
        executor.execute {
            try {
                val videoSource = peerConnectionFactory?.createVideoSource(false)
                val videoCapturer = object : VideoCapturer {
                    override fun initialize(
                        surfaceTextureHelper: SurfaceTextureHelper?,
                        context: android.content.Context?,
                        capturerObserver: CapturerObserver?
                    ) {}

                    override fun startCapture(width: Int, height: Int, framerate: Int) {
                        // 由 ScreenCaptureService 通过 Surface 推送帧
                    }

                    override fun stopCapture() {}
                    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}
                    override fun dispose() {}
                    override fun isScreencast(): Boolean = true
                }

                this.videoSource = videoSource
                videoTrack = peerConnectionFactory?.createVideoTrack("screen_track", videoSource)

                val stream = peerConnectionFactory?.createLocalMediaStream("screen_stream")
                stream?.addTrack(videoTrack)
                peerConnection?.addStream(stream)

                // 创建并发送 Offer
                createAndSendOffer()

                Log.i(TAG, "Video capture started")
            } catch (e: Exception) {
                Log.e(TAG, "Start video capture failed", e)
                onError?.invoke("启动视频捕获失败: ${e.message}")
            }
        }
    }

    private fun createAndSendOffer() {
        peerConnection?.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    peerConnection?.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                            override fun onSetSuccess() {
                                Log.i(TAG, "Local description set, sending offer")
                                sdp?.let { signalingClient.sendOffer(it) }
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {
                                onError?.invoke("设置本地描述失败: $p0")
                            }
                        },
                        sdp
                    )
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    onError?.invoke("创建 Offer 失败: $p0")
                }
                override fun onSetFailure(p0: String?) {}
            },
            MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }
        )
    }

    fun release() {
        executor.execute {
            peerConnection?.close()
            peerConnection = null
            videoSource?.dispose()
            videoSource = null
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        }
        executor.shutdown()
    }
}
