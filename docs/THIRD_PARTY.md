# 第三方依赖与许可证清单

## 运行时（打包进 APK）

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| Jetpack Compose（ui / foundation / material3 / material-icons-extended，BOM 2024.10.01） | 界面 | Apache-2.0 |
| AndroidX Activity Compose 1.9.3（及其传递依赖 core / lifecycle / savedstate 等） | Activity 与 Compose 接入、权限、文件选择 | Apache-2.0 |
| Kotlin 标准库、kotlinx-coroutines（Compose 传递依赖） | 语言运行时 | Apache-2.0 |
| AndroidX Media3 1.4.1（exoplayer、exoplayer-hls、exoplayer-rtsp、datasource-rtmp） | 媒体图层：拉流与本地视频解码 | Apache-2.0 |
| io.antmedia:rtmp-client 3.2.0（Media3 RTMP 扩展的依赖，内含 librtmp 原生库） | 拉取 RTMP 流 | 封装层 Apache-2.0；librtmp 为 LGPL-2.1（以独立 .so 动态库分发）。正式发布前请法务确认 |

摄像头采集、合成、编码与推流部分不依赖第三方库，使用的均为 Android 系统 API：Camera2、MediaCodec、AudioRecord、WebView（系统 Android System WebView / Chromium）、OpenGL ES 2.0 / EGL、Android Keystore。
RTMP/RTMPS、AMF0、FLV 封装为项目自有实现。

## 构建与测试工具（不进入 APK）

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| Android Gradle Plugin 8.7 | 构建 | Apache-2.0 |
| Kotlin 2.0 标准库 / 编译器 | 语言（标准库会打包进 APK） | Apache-2.0 |
| Gradle 8.11 | 构建 | Apache-2.0 |
| Robolectric android-all（仅 `verify/` 编译检查） | 云端编译检查 | Apache-2.0 |
| FFmpeg（仅测试/测量脚本调用，不分发） | 接收端模拟、音画同步测量 | LGPL-2.1+/GPL |
| Node.js（仅 `tools/overlay-server.mjs`） | 测试网页数据服务器 | MIT |

| Kotlin Compose 编译器插件 2.0.21 | 编译 Compose | Apache-2.0 |
