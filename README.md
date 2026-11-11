# LiveComposer：安卓直播合成推流

在手机本地把**后置摄像头 + 透明网页图层 + 图片/文字图层**合成为一路画面，经 H.264/AAC 编码后推送到自定义 RTMP/RTMPS 地址。
接收端拿到的是已经合成好的完整视频，不需要再叠加网页或处理透明通道。

> 当前版本：`0.1.0-demo`，即**技术验证 Demo（第一个交付节点）**，同时包含 P0 的大部分基础功能。真机验证步骤见 [docs/VALIDATION.md](docs/VALIDATION.md)。

## 文档

- [架构方案](docs/ARCHITECTURE.md)：网页如何进入编码画面、时间戳、重连、生效时机、首版系统行为
- [透明网页接入规范](docs/WEB_OVERLAY_SPEC.md)：透明背景写法、推荐视口、字体、支持的动画类型
- [技术验证与真机测试清单](docs/VALIDATION.md)
- 固定测试网页：[`app/src/main/assets/testpage/index.html`](app/src/main/assets/testpage/index.html)，数据服务器：[`tools/overlay-server.mjs`](tools/overlay-server.mjs)

## 功能（本版本）

| 模块 | 状态 |
| --- | --- |
| 后置主摄采集、连续自动对焦、点击对焦（未选中图层时单击预览）、双指变焦（未选中图层时） | ✅ |
| 画布横屏 16:9 / 竖屏 9:16，720p / 1080p，推流中锁定 | ✅ |
| 网页图层：URL、刷新、加载状态/错误、透明与半透明合成、渲染视口与显示尺寸分离、网页交互模式 | ✅ |
| 网页 GPU 采集（默认）/ CPU 软件采集（兜底），待真机对比确定 | ✅ 需真机验证 |
| 图片图层（含透明 PNG）、文字图层（字号/颜色/位置） | ✅ |
| 图层选择（长按或列表）、拖动、双指缩放、中心吸附辅助线、层级、显隐、锁定、删除、数值设置 | ✅ |
| 麦克风、静音（接收端同步静音）、电平显示 | ✅ |
| RTMP / RTMPS，完整 URL 或“服务器 + Stream Key”，密钥加密保存、日志脱敏 | ✅ 云端实测 |
| 状态：连接中 / 直播中 / 重连中 / 失败；时长、输出帧率、发送码率、丢帧、重连次数 | ✅ |
| 自动重连 1、2、5、10 秒递增，最大 10 秒；错误分类 | ✅ 云端实测 |
| 配置保存与恢复（推流需手动开始） | ✅ |

## 构建

**方式一：直接下载 CI 产物。** 每次推送都会触发 GitHub Actions 的 `Build & Verify`，在对应运行页面的 Artifacts 里下载 `livecomposer-apk`。

**方式二：本地构建**（JDK 17 + Android SDK 35，或直接用 Android Studio 打开工程）：

```bash
./gradlew assembleDebug        # 输出 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**推流模块端到端测试**（不需要 Android SDK，需要 JDK、Gradle、ffmpeg、python3）：

```bash
bash verify/run-e2e.sh
```

- 最低系统：Android 10（API 29），目标 API 35。
- 运行时**没有第三方依赖**。Camera2、MediaCodec、WebView、OpenGL ES 都是系统 API，RTMP 协议为项目内实现。构建工具链：Android Gradle Plugin、Kotlin（Apache-2.0）。

## 已知限制（首版）

- **切后台 / 锁屏会自动停止推流**，回到前台后需要重新开始。后台直播列入 P1。
- 首版只支持一个网页图层。WebGL、网页视频/音频未承诺支持，网页声音不会混入直播。
- 网页 GPU 采集依赖系统 WebView 在离屏 Surface 上的硬件绘制，必须在约定真机上验证；异常机型可切换到 CPU 采集。
- 摄像头方向在少数机型上可能需要用“摄像头旋转校正”手动修正。
- release 包目前用 debug 签名，正式发布前需替换为正式签名。

## 目录

```
app/src/main/java/app/livecomposer/
  rtmp/     RTMP/RTMPS 客户端、FLV 封装、发送队列与重连（纯 Kotlin，可在 JVM 测试）
  gl/       EGL、着色器、合成器（摄像头 + 图层 → FBO → 编码器/预览）
  source/   Camera2 采集、离屏网页采集
  encode/   H.264 / AAC 编码
  stream/   推流控制与统计
  core/     配置与加密存储
  ui/       编辑层与界面工具
verify/     云端/CI 校验工程（推流端到端测试、App 源码编译检查）
tools/      测试网页数据服务器
```
