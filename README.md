# 张斌的手机投屏

**Android 手机 → Android TV / 投影仪 无线投屏** — 无需电脑中转，两个 APK 直连。

## 架构

```
┌──────────────────────┐        Wi-Fi 局域网         ┌──────────────────────┐
│  手机 (Sender APK)    │ ◄────────────────────────► │  电视 (Receiver APK)  │
│                      │                            │                      │
│  ┌────────────────┐  │    UDP 多播发现（手机找电视） │  ┌────────────────┐  │
│  │ DiscoveryClient│──┼───────────────────────────►│  │ DiscoveryService│  │
│  └────────────────┘  │    239.255.42.42:9091      │  └────────────────┘  │
│                      │                            │                      │
│  ┌────────────────┐  │    WebSocket 信令交换       │  ┌────────────────┐  │
│  │ SignalingClient│──┼───────────────────────────►│  │ SignalingServer│  │
│  └────────────────┘  │    SDP Offer/Answer        │  └────────────────┘  │
│                      │    ICE Candidate            │                      │
│  ┌────────────────┐  │                            │  ┌────────────────┐  │
│  │ WebRTC Sender  │──┼─────── WebRTC UDP ────────►│  │ WebRTC Receiver│  │
│  │ MediaProjection│  │     H.264 视频流            │  │ SurfaceView    │  │
│  └────────────────┘  │                            │  └────────────────┘  │
└──────────────────────┘                            └──────────────────────┘
```

- **纯无线**：手机和电视在同一个 Wi-Fi 即可，无需任何线缆
- **无中转**：不需要电脑、服务器，两台设备直连
- **自动发现**：电视开机后广播自身存在，手机自动发现

## 项目结构

```
zhangbin-cast-android/
├── phone-app/                  ← 手机端安装包（Sender）
│   └── app/src/main/java/com/zhangbin/cast/phone/
│       ├── MainActivity.kt       主页（发现电视 → 开始投屏）
│       ├── TVDevice.kt           电视设备数据模型
│       ├── DiscoveryClient.kt    UDP 多播发现电视
│       ├── SignalingClient.kt    WebSocket 信令客户端
│       ├── WebRTCManager.kt      WebRTC 连接管理 + 发送视频
│       └── ScreenCaptureService.kt  前台服务 + MediaProjection 录屏
├── tv-app/                     ← 电视端安装包（Receiver）
│   └── app/src/main/java/com/zhangbin/cast/tv/
│       ├── MainActivity.kt       主页（全屏显示投屏画面）
│       ├── DiscoveryService.kt   后台服务 + UDP 广播
│       ├── SignalingServer.kt    WebSocket 信令服务器
│       └── WebRTCReceiver.kt     WebRTC 接收 + SurfaceView 渲染
└── README.md
```

## 第一步：安装 Android Studio

Android Studio 是 Google 官方的 Android 开发工具，安装后即可编译本项目的两个 APK。

### Windows 安装

```bash
# 方式一：运行本项目的自动检测脚本
cd zhangbin-cast-android
setup-android-studio.bat

# 方式二：手动下载
# 1. 访问 https://developer.android.com/studio
# 2. 下载最新版 Android Studio (约 1.2 GB)
# 3. 运行安装程序，一路默认即可
# 4. 首次启动时会提示安装 Android SDK — 选择 API 34
```

### 其他系统

| 系统 | 下载地址 |
|------|---------|
| macOS | https://developer.android.com/studio#mac |
| Linux | https://developer.android.com/studio#linux |

### 安装后验证

```bash
# 检查 Android Studio
# Windows: C:\Program Files\Android\Android Studio\bin\studio64.exe
# macOS: /Applications/Android Studio.app
# Linux: /usr/local/android-studio/bin/studio.sh

# 检查 Java（Android Studio 自带 JDK）
java -version
# 输出: openjdk version "17.0.x"
```

## 第二步：编译方法

需要 **Android Studio Hedgehog (2023.1.1+)**。

### 手机端（编译并安装到 Android 手机）

```bash
cd zhangbin-cast-android/phone-app
./gradlew assembleDebug
# APK 输出: phone-app/app/build/outputs/apk/debug/app-debug.apk
```

或者在 Android Studio 中：
1. 打开 `zhangbin-cast-android/` 目录
2. 选择 `phone-app` 运行配置
3. 连接手机 → 点击 Run

### 电视端（编译并安装到 Android TV / 投影仪）

```bash
cd zhangbin-cast-android/tv-app
./gradlew assembleDebug
# APK 输出: tv-app/app/build/outputs/apk/debug/app-debug.apk
```

**安装到 Android TV**：
- 方法一：ADB 无线安装（电视开 ADB 调试）
- 方法二：U 盘拷贝 APK 后通过文件管理器安装
- 方法三：使用 `adb install app-debug.apk`

## 使用说明

### 第一次使用

1. **电视上** 打开「张斌的手机投屏」APK
   - 屏幕显示「等待手机连接…」和本机 IP
   - 会在后台广播自身存在（UDP 多播）

2. **手机上** 打开「张斌的手机投屏」APK
   - 自动搜索电视设备
   - 找到后显示设备名称和 IP
   - 点击「开始投屏」
   - 授予屏幕共享权限
   - 开始投屏！

3. **电视上** 自动全屏显示手机屏幕画面

### 注意事项

| 要求 | 说明 |
|------|------|
| 网络 | 手机和电视必须在 **同一 Wi-Fi** |
| 防火墙 | 电视端需开放 UDP 9091 + TCP 9090 端口 |
| Android 版本 | 手机 Android 8+，电视 Android 8+ |
| 画面延迟 | 同一 5GHz Wi-Fi 下约 100-200ms |

## 技术要点

- **屏幕捕获**: `MediaProjection` API + `VirtualDisplay`
- **视频编码**: WebRTC 内置 VP8/H.264 硬件编码
- **信令**: 自建 WebSocket 服务器（内嵌在 TV APK 中）
- **设备发现**: UDP 多播 239.255.42.42:9091
- **投屏协议**: WebRTC (ICE/STUN + SDP + RTP)
