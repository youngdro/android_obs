# 透明网页接入规范

适用于 LiveComposer 首版的网页图层。网页会以“渲染视口”大小离屏渲染，带透明通道叠加到摄像头画面上，然后编码推流。

## 1. 透明背景（必须）

```css
html, body { background: transparent; margin: 0; overflow: hidden; }
```

- 不要给 `html`/`body`/全屏容器设置任何背景色（包括 `#fff`、`#000`）。没有内容的地方摄像头会透出来。
- 半透明请用 `rgba()` / `hsla()` / `opacity`，例如 `background: rgba(0,0,0,.5)`。阴影（`box-shadow`、`text-shadow`）、渐变、圆角抗锯齿边缘都能按透明度正确混合。
- 不要靠降低整个页面的不透明度来“模拟透明”。
- Canvas 每帧用 `ctx.clearRect()` 清空，不要 `fillRect` 底色。
- 建议在页面里声明 `color-scheme: only light;`。App 不会对网页强制应用深色模式。

## 2. 推荐视口尺寸

App 中“渲染视口”就是网页的 CSS 布局尺寸，1 CSS px 对应 1 个纹理像素，与 OBS 浏览器源的宽/高含义一致。

| 画布 | 推荐视口 |
| --- | --- |
| 横屏 1080p | 1920 × 1080 |
| 横屏 720p | 1280 × 720 |
| 竖屏 1080p | 1080 × 1920 |
| 竖屏 720p | 720 × 1280 |

- 网页图层在画布中的显示尺寸可以另外设置。缩放图层不会改变网页布局。
- 页面加载后 App 会显示“实测视口 W×H CSS px”，可以用来核对。`window.innerWidth/innerHeight` 等于视口设置。
- 想让页面同时适配横竖屏，用 `vw/vh/vmin` 或 flex/grid 布局。

## 3. 字体加载

- 首版没有内置额外字体，中文用系统字体：`font-family: "Noto Sans CJK SC", "PingFang SC", sans-serif;`（安卓上实际生效的是 Noto Sans CJK）。
- 需要品牌字体时，用 `@font-face` 从**手机能访问的地址**加载 woff2，并加 `font-display: block;`。这样字体就绪前不显示文字，避免回退字体闪一下被推到直播里。
- 字体文件较大时，建议放在局域网或 CDN，并在开播前先加载好页面。

## 4. 支持的内容与动画

| 类型 | 首版 | 说明 |
| --- | --- | --- |
| HTML / CSS 布局、透明 PNG、SVG、渐变、阴影 | ✅ | |
| CSS `transition` / `@keyframes`（transform、opacity 等） | ✅ | 推荐用 transform/opacity，性能最好 |
| JavaScript、`requestAnimationFrame` | ✅ | 采集帧率上限等于输出帧率（默认 30fps） |
| Canvas 2D | ✅ | |
| WebSocket / `fetch` / XHR 数据更新 | ✅ | 地址必须能被手机访问 |
| WebGL | ⚠️ 未承诺 | 单独验证 |
| `<video>` / 网页音频 | ⚠️ 未承诺 | 网页声音不会混入直播音频 |
| 需要登录、弹窗、文件选择的页面 | ❌ | |

## 5. 网络与安全

- 推荐使用 HTTPS。证书错误会正常报错，不会忽略。
- 局域网 HTTP 页面需要在 App 中打开“允许局域网 HTTP 明文网页”（默认关闭）。HTTPS 页面里不能混用 HTTP 资源。
- 加载失败或返回 HTTP 4xx/5xx 时，网页图层会从输出中隐藏，摄像头继续推流，错误只显示在操作界面。
- 刷新/切换 URL 时，直播画面会保持上一帧网页内容，直到新页面首帧准备好，不会推出白屏或浏览器错误页。

## 6. 交互

- 默认是“编辑模式”，触摸用于移动、缩放图层。
- 需要点击网页里的按钮时，切到“网页交互模式”。

## 7. 声音

网页的声音会混进直播，不从手机外放（避免麦克风再收进去形成回声）。在网页图层的属性里可以调音量（0~200%）并看到电平；隐藏图层时不出声。

- 实现方式：App 在页面脚本之前注入音频桥（`app/src/main/assets/webaudio/bridge.js`），把所有连到 `AudioContext.destination` 的 Web Audio 连接，以及 `<audio>`、`<video>`、`new Audio()` 的播放，转成 PCM 交给 App 混音。
- 允许自动播放：叠加页的提示音、背景音乐不需要用户点击。
- **跨域音频需要 CORS**：音频或视频文件和网页不同源时，服务器要返回 `Access-Control-Allow-Origin`，媒体元素要设 `crossorigin="anonymous"`，否则浏览器出于安全只给静音数据（这种声音进不了直播，本机也不会响）。同源文件和 Web Audio 合成的声音没有这个限制。
- 页面最好只创建一个 `AudioContext`。每个上下文都会单独采样，可以工作，但更耗电。
- 测试：内置测试页右侧有“声音测试”按钮（交互模式下点击），或在地址后加 `?beep=1` 自动开始，每秒一声提示音。

## 8. 测试素材

- 固定测试网页：`app/src/main/assets/testpage/index.html`（App 内置，“网页”面板点“测试页”即可加载）。
  包含：透明区域、50% 色块、透明度阶梯、中文文字、透明 PNG/SVG、移动动画、Canvas 动画、动态计数器、WebSocket/HTTP 数据、四角视口标记。
- 局域网数据服务器：`node tools/overlay-server.mjs 8787`，然后用
  `http://<电脑IP>:8787/index.html?ws=ws://<电脑IP>:8787` 或 `?poll=http://<电脑IP>:8787/data`。
  在服务器终端输入一行文字会立即推送到网页。
