# 第三方依赖与许可证清单

## 运行时（打包进 APK）

| 组件 | 用途 | 许可证 |
| --- | --- | --- |
| Jetpack Compose（ui / foundation / material3 / material-icons-extended，BOM 2024.10.01） | 界面 | Apache-2.0 |
| AndroidX Activity Compose 1.9.3（及其传递依赖 core / lifecycle / savedstate 等） | Activity 与 Compose 接入、权限、文件选择 | Apache-2.0 |
| Kotlin 标准库、kotlinx-coroutines（Compose 传递依赖） | 语言运行时 | Apache-2.0 |

音视频与推流部分不依赖第三方库，使用的均为 Android 系统 API：Camera2、MediaCodec、AudioRecord、WebView（系统 Android System WebView / Chromium）、OpenGL ES 2.0 / EGL、Android Keystore。
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
