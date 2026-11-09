# LiveComposer 整体配置格式与云端配置接口 v1

本文给两类读者：一是用 App 导出、导入配置文件的人，二是提供“按 liveId 下发配置”接口的服务端。

## 1. 服务端要实现的接口

> 已部署：`https://pre-live.hemaos.com/api/tb-live-live2d/device-config`（App 默认地址），需要控制台生成的配置访问密钥（`lc_cfg_` 开头）。服务端会在响应头 `X-LiveComposer-Vision-Expires-At` 给出截图凭证有效期，App 在截图通道面板显示；截图凭证过期（`TOKEN_EXPIRED` / `TOKEN_REVOKED`）或范围不符（`SCOPE_MISMATCH`）时停止轮询并提示重新拉取配置。App 不跟随重定向。

只需要一个 GET 接口，地址由服务端自定，在 App 里填写，例如 `https://example.com/api/livecomposer/device-config`。

### 请求（App 发出）

```http
GET <接口地址>?liveId=4387566503550045&deviceId=lc-0b5b3a7e-1d2c-4f7a-9e44-2f6f3b7c9d10
Authorization: Bearer <访问密钥>        # 仅当 App 里填了访问密钥时才有
Accept: application/json
X-LiveComposer-Version: 0.9.0
X-LiveComposer-Device: lc-0b5b3a7e-...
```

| 参数 | 必需 | 说明 |
| --- | --- | --- |
| `liveId` | 是 | 直播间 ID，按字符串处理，服务端据此返回该场直播的配置 |
| `deviceId` | 否 | 设备固定 ID（`lc-` 开头），同一 liveId 多台设备需要不同配置时才用 |
| `Authorization` | 建议 | `Bearer <访问密钥>`。配置里通常有推流密钥，建议鉴权；不鉴权时 App 不带此头 |

### 响应

- **200**，`Content-Type: application/json`，响应体是下文第 2 节的配置 JSON。也可以包一层：`{"ok": true, "data": { 配置 }}`。
- **404**：这个 liveId 没有配置。
- **401 / 403**：访问密钥不对。
- 其他状态码，App 会显示“服务返回 HTTP xxx”。
- 只支持 https；响应不超过 2MB；建议 `Cache-Control: no-store`。
- 服务端日志里不要记录访问密钥和配置里的推流密钥。

### 最小可用的响应

只下发需要设置的项即可，没写的项 App 保持原样：

```json
{
  "format": "livecomposer-config",
  "version": 1,
  "settings": { "visionLiveId": "4387566503550045" },
  "secrets": { "fullUrl": "rtmp://push.example.com/live/streamname?auth_key=xxxx" }
}
```

最省事的做法：在 App 里把一台调好的手机配置“导出到文件”，把这个文件当模板存到服务端，只替换推流地址等随场次变化的字段。

## 2. 配置 JSON

```json
{
  "format": "livecomposer-config",
  "version": 1,
  "exportedAt": 1793923200000,
  "app": { "name": "LiveComposer", "version": "0.9.0" },
  "settings": { ... },
  "secrets": { "fullUrl": "", "streamKey": "", "camUrl": "", "visionApiKey": "" },
  "scenes": [ { "id": "...", "name": "开场", "savedAt": 1793923200000, "data": { ... } } ],
  "activeSceneId": "..."
}
```

| 字段 | 必需 | 说明 |
| --- | --- | --- |
| `format` | 是 | 固定为 `livecomposer-config` |
| `version` | 是 | 固定为 `1`；比 App 支持的版本新时，App 会提示先升级 |
| `settings` | 是 | 设置，可以只写一部分，缺少的项保持原样 |
| `secrets` | 否 | 推流地址和各类密钥；没有这个字段时保留手机上已填的；某一项写空字符串表示清空 |
| `scenes` | 否 | 场景列表；有这个字段时整体替换手机上的场景，没有时保留 |
| `activeSceneId` | 否 | 当前场景 ID |
| `exportedAt`、`app` | 否 | 仅用于显示 |

### settings 常用项

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `orientation` | `"LANDSCAPE"` / `"PORTRAIT"` | 画布横屏 16:9 / 竖屏 9:16 |
| `resolution` | `"P720"` / `"P1080"` | 主推流分辨率，手机不支持时自动降级 |
| `fps` | 15–60 | 帧率 |
| `videoBitrateKbps` | 300–20000 | 视频码率 |
| `keyFrameIntervalSec` | 1–10 | 关键帧间隔 |
| `useFullUrl` | bool | true 时使用 `secrets.fullUrl`；false 时使用 `serverUrl` 加 `secrets.streamKey`。只给了 fullUrl 时自动设为 true |
| `serverUrl` | string | 服务器地址（“服务器 + 密钥”方式） |
| `noiseSuppression` | bool | 麦克风降噪 |
| `micMuted` | bool | 麦克风静音 |
| `monitorLocal` | bool | 网页 / 视频声音是否也在手机上播放 |
| `powerSave`、`autoDim` | bool | 省电、无操作调暗 |
| `camAudio`、`camResolution`、`camBitrateKbps` | | 第二路（纯摄像头）推流：是否带麦克风、分辨率、码率。地址在 `secrets.camUrl` |
| `visionBaseUrl`、`visionLiveId`、`visionEnabled` | | 截图通道的服务域名、liveId、打开 App 时是否自动连接。接入密钥在 `secrets.visionApiKey`。云端配置没写 `visionLiveId` 时，用请求里的 liveId |
| `layers` | array | 当前图层，有这个字段时整体替换；结构见下 |

### 图层（layers 里的一项）

```json
{ "id": "web-1", "type": "WEB", "name": "Live2D", "x": 0, "y": 0, "w": 1080, "h": 1920,
  "visible": true, "opacity": 1, "url": "https://example.com/live2d?liveId=4387566503550045",
  "viewportW": 1080, "viewportH": 1920, "volume": 1 }
```

- `type`：`CAMERA`、`WEB`、`MEDIA`、`IMAGE`、`TEXT`。
- 位置和尺寸（`x`、`y`、`w`、`h`）是画布像素坐标，左上角为原点。
- 网页图层用 `url`；拉流或网络视频用 `mediaUri`，配合 `mediaLive` 和 `loop`；文字用 `text`、`textSizePx`、`textColor`。
- 摄像头图层不写时会自动补一个铺满的。
- 本机图片和视频（`content://`）换手机后打不开，加载时会提示重新选择。

## 3. 不会被导出或覆盖的内容

- 本机设备 ID（`deviceId`）。
- 云端配置的接口地址、liveId 和访问密钥。
- 加载配置时 App 必须没在推流（主推流和第二路都要停）。加载后画面会重新加载一次。
