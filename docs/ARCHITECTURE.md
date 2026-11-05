# 架构方案（技术验证 Demo / P0 基线）

## 1. 总体链路

```
                         ┌───────────────── 主线程（Android View）─────────────────┐
                         │ 预览 SurfaceView ← Compose 编辑层 Stage（选中框/手柄/辅助线，仅屏幕）│
                         │ 离屏宿主 Host(FrameLayout) ⊃ WebView（挂在窗口上，但不画到屏幕） │
                         └───────────────┬───────────────────────────────────────┘
                                         │ 每当网页有新内容：Surface.lockHardwareCanvas()
                                         │ 清空为透明 → drawChild(WebView) → unlockAndPost
                                         ▼
后置摄像头 Camera2 ──► SurfaceTexture(OES) ─┐    SurfaceTexture(OES, RGBA 带 Alpha) ◄─┘
                                           ▼                       ▼
                         ┌──────────── 合成线程 Compositor（GLES2，固定帧率时钟）────────────┐
                         │ FBO(画布 W×H)：清黑 → 图层按顺序预乘 Alpha 混合（摄像头也是图层） │
                         │   网页(OES) / 图片、文字(Bitmap→2D 纹理)    glBlendFunc(ONE, 1-SRC_A) │
                         └───────┬─────────────────────────────────────┬──────────────────┘
                                 │ 同一张 FBO                           │
                                 ▼                                     ▼
                MediaCodec 输入 Surface（eglPresentationTime）     屏幕预览 Surface
                                 │ H.264（硬件，High/CBR/无 B 帧/GOP 2s）
麦克风 AudioRecord ─► AAC-LC ────┤
（静音=送 0，时间戳与视频同一 MONOTONIC 时钟）▼
                     RtmpPublisher：发送队列 → 拥塞丢帧（等关键帧）→ RtmpConnection（RTMP/RTMPS）
                     自动重连 1、2、5、10、10… 秒；错误分类上报
```

**关键结论：网页内容是作为一张带透明通道的纹理进入编码器画面的**，不是在预览上叠一个透明 WebView。
编码器和屏幕预览从同一张 FBO 取图，所以接收端与本地预览构图一致；选中框、按钮、错误提示都只在 Android View 层，不会进入视频。

## 2. 模块

| 包 | 文件 | 职责 |
| --- | --- | --- |
| `rtmp` | `RtmpUrl` `Amf0` `FlvMux` `RtmpConnection` `RtmpPublisher` | 纯 Kotlin，无 Android 依赖：地址解析/脱敏、握手、AMF0 命令、分块、FLV 封装、发送队列、重连、错误分类。可在 JVM 上测试（`verify/`）。 |
| `gl` | `EglCore` `QuadRenderer` `Compositor` | EGL 上下文、预乘 Alpha 着色器、合成线程、固定帧率时钟、FBO、编码/预览双输出。 |
| `source` | `CameraSource` `WebOverlaySource` | Camera2（连续 AF、点击对焦、变焦、占用恢复）；离屏网页采集（GPU/CPU 两种模式、冻结防白屏、错误/崩溃处理、视口校准、交互转发）。 |
| `source` | `PlayerSource` | Media3 ExoPlayer：拉流（HLS/RTMP/RTSP/HTTP-FLV/MP4）与本地视频。画面解码到合成器的 SurfaceTexture；音频经 TeeAudioProcessor 取 PCM 交给混音器，本机输出静音；拉流断线 1/2/5/10 秒重连。 |
| `mix` | `AudioMixer` | 纯 Kotlin 混音器：各媒体源 PCM 下混/重采样到 48kHz，按音量叠加进麦克风这一路后送 AAC；以麦克风为主时钟，积压超 400ms 裁到 120ms，欠载补静音。可在 JVM 测试（`verify/` 的 `mixerChecks`）。 |
| `encode` | `VideoEncoder` `AudioPipeline` | MediaCodec H.264（优先硬件）；AudioRecord + AAC，电平、静音、时间戳对齐。 |
| `stream` | `StreamController` | 串联编码器与推流，统计时长/输出帧率/发送码率/丢帧/重连。 |
| `core` | `AppConfig` | 画布、图层、网页、摄像头、推流配置持久化；推流地址和 Stream Key 用 Android Keystore AES-GCM 加密保存。 |
| `engine` | `StudioEngine` | 界面无关的引擎门面：串起合成、摄像头、网页、音频、推流与配置，对外提供不可变快照 `EngineState`。以后抽 Rust 核心时，这一层就是边界。 |
| `ui` | `StudioActivity` `StudioScreen` `Stage` `Panels` `Settings` `Components` `Theme` | Jetpack Compose 界面：横竖屏两套布局；舞台上长按选中图层、拖动、八向手柄缩放、边缘/中心吸附辅助线、双指缩放；图层、属性、添加、设置面板。 |

## 3. 网页进入合成链路的实现细节（本项目最大风险点）

1. **WebView 必须挂在窗口上**：否则 Chromium 会暂停定时器、`requestAnimationFrame`、CSS 动画。宿主 `Host` 以渲染视口像素尺寸加在根布局最底层（可以超出屏幕），`draw()` 被重写为不画到屏幕，只做采集。
2. **采集**：`Host.capture()` 中 `Surface.lockHardwareCanvas()` → `drawColor(TRANSPARENT, CLEAR)` → `drawChild(WebView)` → `unlockCanvasAndPost()`。
   该 Surface 来自合成器创建的 `SurfaceTexture`（RGBA8888，预乘 Alpha），合成器在 GL 线程 `updateTexImage()` 后当作 OES 纹理使用。
   - 硬件加速下子 View 失效不会重录父级，因此重写 `onDescendantInvalidated()` 强制父级重绘；同时以输出帧率为上限持续 `postInvalidateOnAnimation()`，保证 Canvas/CSS 动画每帧进入画面。
   - **CPU 兜底**：设置里关闭“网页 GPU 采集”后，WebView 使用软件层，`lockCanvas()` 软件绘制。兼容性最好但 1080p 下性能有限。真机验证时两种模式都要测，以决定正式方案（见 VALIDATION.md）。
3. **透明**：`WebView.setBackgroundColor(TRANSPARENT)`；页面自身 `html, body { background: transparent }`；混合使用预乘 Alpha 公式，50% 色块、阴影、抗锯齿边缘不会出现黑边。不对整个网页降不透明度（图层不透明度默认 100%）。
4. **防止中间画面推出去**：开始加载/刷新时冻结采集（合成器继续用上一帧）；`onPageFinished` 后再用 `postVisualStateCallback` 等到首帧真正可绘制才恢复。主文档加载失败、HTTP 4xx/5xx、证书错误时在输出中隐藏网页图层，摄像头继续推流，错误只显示在操作界面。浏览器错误页永远不会被采集。
5. **网页进程崩溃**：`onRenderProcessGone` 返回 true，销毁 WebView 并隐藏图层，界面提示“可点击刷新恢复”。
6. **视口与显示尺寸分离**：图层有两组尺寸：渲染视口（`viewportW/H`，网页布局用的 CSS 像素，1 CSS px = 1 纹理像素，与 OBS 浏览器源一致）和画布中的显示矩形（`x/y/w/h`）。缩放图层只改变显示矩形，网页布局不变。
   WebView 的缩放比例首次加载后用 `window.innerWidth` 实测校准，界面显示“实测视口”供核对。
7. **不强制深色模式**：API 33+ `isAlgorithmicDarkeningAllowed=false`，以下 `forceDark=OFF`。
8. **网页交互模式**：编辑模式下触摸只用于选择/拖动/缩放图层；切换到网页交互模式后，预览上的触摸换算到视口坐标转发给 WebView。

## 4. 时间戳与音画同步

- 视频：合成线程每帧取 `System.nanoTime()`（CLOCK_MONOTONIC），通过 `eglPresentationTimeANDROID` 交给编码器。
- 音频：`AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` 推算每块首采样的采集时刻，同一时钟，因此长时间运行没有累积漂移。拿不到时间戳时退化为按采样数累计。
- RTMP 时间戳 = 相对本次连接第一包的毫秒数；每次重连重新起算，并先发序列头、等待关键帧。

## 5. 推流与错误处理

- 发送队列积压超过 2 秒时丢弃队列中的视频帧并请求关键帧（计入“丢帧”），音频保留最近 2 秒。
- 写阻塞超过 8 秒视为网络中断。
- 错误分类：地址错误 / 域名解析失败 / 连接超时 / 无法连接服务器 / TLS 证书错误 / 服务器拒绝（connect/publish 被拒、BadName） / 网络中断 / 编码失败。
  首次连接被服务器拒绝或证书错误直接失败；直播中断后的网络类错误按 1、2、5、10 秒持续重连，用户可随时停止。
- RTMPS 使用系统信任库，并开启主机名校验（`endpointIdentificationAlgorithm=HTTPS`），证书错误正常报错。

## 6. 生效时机

| 操作 | 直播中 |
| --- | --- |
| 网页刷新、修改 URL、图层显隐/位置/尺寸/层级/锁定、静音、变焦、对焦 | 立即生效 |
| 画布方向、分辨率、帧率、码率、关键帧间隔、推流地址、网页采集模式 | 锁定，停止推流后修改 |
| 修改网页渲染视口 | 会重建网页（短暂冻结上一帧），建议停播时修改 |

## 7. 首版已定义的系统行为

- 开播期间屏幕常亮；打开设置面板不影响摄像头、网页动画和推流（面板只是覆盖在预览上的 View）。
- **切后台 / 锁屏：自动停止推流并释放摄像头和麦克风**，回到前台时提示用户重新开始。这样避免界面显示“直播中”但画面已冻结。后台直播（前台服务 + 类型声明）列入 P1。
- 摄像头被占用：提示，并在可用时自动重新打开；麦克风被占用/失败：提示。

## 8. 跨平台演进（Rust + wgpu）

推流模块（`rtmp`）与场景模型不依赖 Android，后续可以迁移为 Rust 共享核心（uniffi 绑定），合成可迁到 wgpu。
网页离屏渲染（安卓 WebView / 桌面 CEF）、硬件编码、摄像头这三块仍需各平台原生实现。
