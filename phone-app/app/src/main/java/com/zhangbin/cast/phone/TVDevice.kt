package com.zhangbin.cast.phone

/**
 * 网络中发现的一台电视/投影仪设备
 */
data class TVDevice(
    val name: String,            // 设备名称
    val ip: String,              // IP 地址
    val signalingPort: Int = 9090, // WebSocket 信令端口
    val version: String = "1.0"   // 协议版本
) {
    /** WebSocket 信令服务器地址 */
    val signalingUrl: String get() = "ws://$ip:$signalingPort/signal"
}
