# LiveComposer：安卓直播合成推流

在手机本地把**后置摄像头 + 透明网页图层 + 图片/文字图层**合成为一路画面，经 H.264/AAC 编码后推送到自定义 RTMP/RTMPS 地址。
接收端拿到的是已经合成好的完整视频，不需要再叠加网页或处理透明通道。

> 当前版本：`0.4.0`，在 P0 基础上增加了摄像头图层、拉流与本地视频图层、每路音量和场景方案。真机验证步骤见 [docs/VALIDATION.md](docs/VALIDATION.md)。

## 文档

- [架构方案](docs/ARCHITECTURE.md)：网页如何进入编码画面、时间戳、重连、生效时机、首版系统行为
- [透明网页接入规范](docs/WEB_OVERLAY_SPEC.md)：透明背景写法、推荐视口、字体、支持的动画类型
- [技术验证与真机测试清单](docs/VALIDATION.md)、[真机测试报告模板](docs/TEST_REPORT_TEMPLATE.md)
- [第三方依赖与许可证清单](docs/THIRD_PARTY.md)
- 音画同步测量：[`tools/av_sync.py`](tools/av_sync.py)
- 固定测试网页：[`app/src/main/assets/testpage/index.html`](app/src/main/assets/testpage/index.html)，数据服务器：[`tools/overlay-server.mjs`](tools/overlay-server.mjs)

## 功能（本版本）

| 模块 | 状态 |
| --- | --- |
| 后置主摄采集、连续自动对焦、点击对焦（未选中图层时单击预览）、双指变焦（未选中图层时） | ✅ |
| 摄像头作为普通图层：可移动、缩放（等比铺满框内，可做画中画）、调层级、隐藏；音量即麦克风音量 | ✅ 需真机验证 |
| 媒体图层：拉流地址（HLS / RTMP / RTSP / HTTP-FLV / HTTP MP4，断线自动重连）、本地视频（循环播放），最多 3 路 | ✅ 需真机验证 |
| 每个有声图层独立音量（0~200%）与电平；媒体声音混进直播，不外放 | ✅ 混音器云端测试，需真机验证 |
| 场景方案：把当前全部图层、层级、位置和画布/编码设置保存为场景，可存多个，一键切换（直播中也可切换画面） | ✅ 需真机验证 |
| 画布横屏 16:9 / 竖屏 9:16，720p / 1080p，推流中锁定 | ✅ |
| 网页图层：URL、刷新、加载状态/错误、透明与半透明合成、渲染视口与显示尺寸分离、网页交互模式 | ✅ |
| 网页 GPU 采集（默认）/ CPU 软件采集（兜底），待真机对比确定 | ✅ 需真机验证 |
| 图片图层（含透明 PNG）、文字图层（字号/颜色/位置） | ✅ |
| 图层编辑：长按或列表选中；拖动移动；八个手柄自由缩放（角手柄可锁定比例）；双指缩放；画布边缘/中心吸附辅助线；浮动工具栏（属性、上移/下移、显隐、锁定、删除）；数值精调、对齐、铺满/适应/50%/原始尺寸、不透明度 | ✅ |
| 麦克风、静音（接收端同步静音）、电平显示 | ✅ |
| RTMP / RTMPS，完整 URL 或“服务器 + Stream Key”，密钥加密保存、日志脱敏 | ✅ 云端实测 |
| 状态：连接中 / 直播中 / 重连中 / 失败；时长、输出帧率、发送码率、丢帧、重连次数 | ✅ |
| 常驻性能信息：合成帧率、网页采集模式/帧率/耗时、内存、电池温度、热状态；直播运行日志 CSV 自动导出 | ✅ |
| 系统网络监听：网络恢复立即重连，断网主动进入重连 | ✅ |
| 音画同步测试信号 + `tools/av_sync.py` 自动测量 | ✅ |
| 自动重连 1、2、5、10 秒递增，最大 10 秒；错误分类 | ✅ 云端实测 |
| 配置保存与恢复（推流需手动开始） | ✅ |

## 操作速览

- **舞台**：未选中图层时，单击对焦、双指变焦；长按任意图层（包括摄像头画面）选中，选中后拖动移动、拉手柄缩放、双指整体缩放，单击空白取消选中。网页图层可切到“交互模式”，触摸直接传给网页。
- **横屏**：右侧竖排工具栏（设置 / 图层 / 添加 / 更多），麦克风和开播按钮固定在底部；**竖屏**：底部工具栏 + 底部面板。网页交互模式和性能信息开关在“更多”里。顶部常驻状态、帧率、码率和性能信息。
- **场景**：顶部左侧的场景标签显示当前场景，点开可保存当前画面为新场景、切换、更新、重命名、排序、删除。推流地址不随场景变化。
- **开播**：右下（横屏）/底部中间（竖屏）的圆形按钮。地址不合法会直接提示，停止推流需二次确认。

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
- 界面使用 Jetpack Compose（Apache-2.0）；音视频与推流**没有第三方依赖**，Camera2、MediaCodec、WebView、OpenGL ES 都是系统 API，RTMP 协议为项目内实现。

## 已知限制（首版）

- **切后台 / 锁屏会自动停止推流**，回到前台后需要重新开始。后台直播列入 P1。
- 网页图层的声音不会进入直播（系统 WebView 不提供音频输出接口，需录屏授权才能采集），网页请保持静音。
- 拉流 / 本地视频的声音按解码时间混入，画面按播放器的音画同步显示；两者之间的偏差还没在真机上测量（可用音画同步测试信号和 `tools/av_sync.py` 测）。
- 同时解码的视频路数受手机硬件解码器数量限制，首版最多 3 路媒体图层。
- 首版只支持一个网页图层。WebGL、网页视频/音频未承诺支持，网页声音不会混入直播。
- 网页 GPU 采集依赖系统 WebView 在离屏 Surface 上的硬件绘制，必须在约定真机上验证；异常机型可切换到 CPU 采集。
- 摄像头方向在少数机型上可能需要用“摄像头旋转校正”手动修正。
- release 包目前用 debug 签名。正式发布前生成自己的签名（`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias livecomposer`），在 `app/build.gradle.kts` 的 `signingConfigs` 中配置，密码放在 CI Secrets 或本机 `~/.gradle/gradle.properties`，不要提交到仓库。

## 目录

```
app/src/main/java/app/livecomposer/
  rtmp/     RTMP/RTMPS 客户端、FLV 封装、发送队列与重连（纯 Kotlin，可在 JVM 测试）
  gl/       EGL、着色器、合成器（摄像头 + 图层 → FBO → 编码器/预览）
  source/   Camera2 采集、离屏网页采集
  encode/   H.264 / AAC 编码
  stream/   推流控制与统计
  core/     配置与加密存储
  engine/   引擎门面 StudioEngine（界面无关）
  ui/       Compose 界面（舞台编辑、面板、设置）
verify/     云端/CI 校验工程（推流端到端测试、App 源码编译检查）
tools/      测试网页数据服务器
```
