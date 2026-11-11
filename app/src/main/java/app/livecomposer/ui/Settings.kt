package app.livecomposer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.livecomposer.core.AppConfig
import app.livecomposer.core.CanvasOrientation
import app.livecomposer.core.Resolution
import app.livecomposer.engine.EngineState

/**
 * 设置面板。直播中锁定画布、编码与推流地址（停播后修改）；网页 HTTP 开关与诊断项随时可改。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsPanel(
    state: EngineState,
    urlError: String?,
    onApply: ((AppConfig) -> Unit) -> Unit,
    onSyncTest: (Boolean) -> Unit,
    showPerf: Boolean,
    onShowPerf: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = state.isStreaming
    PanelShell("设置", onClose, modifier) {
        if (locked) {
            Text("直播中：画布、编码和推流地址已锁定，停止推流后可修改。",
                style = MaterialTheme.typography.bodySmall, color = Studio.Warn,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Studio.Warn.copy(alpha = 0.12f)).padding(10.dp))
        }

        Section("推流目标") {
            Segmented(listOf(true, false), state.useFullUrl, { if (it) "完整地址" else "服务器 + 密钥" },
                { v -> onApply { it.useFullUrl = v } }, enabled = !locked)
            if (state.useFullUrl) {
                Field("推流地址", state.fullUrl, { v -> onApply { it.fullUrl = v.trim() } },
                    placeholder = "rtmp(s)://服务器/应用名/串流密钥", secret = true, enabled = !locked, error = urlError)
            } else {
                Field("服务器地址", state.serverUrl, { v -> onApply { it.serverUrl = v.trim() } },
                    placeholder = "rtmp(s)://服务器/应用名", enabled = !locked, error = urlError)
                Field("串流密钥 Stream Key", state.streamKey, { v -> onApply { it.streamKey = v.trim() } },
                    placeholder = "加密保存，日志中脱敏", secret = true, enabled = !locked)
            }
            Text("支持 RTMP 与 RTMPS。断线后按 1、2、5、10 秒自动重连，网络恢复时立即重连。", style = MaterialTheme.typography.bodySmall)
        }

        Section("画布") {
            Segmented(CanvasOrientation.values().toList(), state.orientation, { it.label }, { v -> onApply { it.orientation = v } }, enabled = !locked)
            Text("输出分辨率", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Resolution.values().forEach { r ->
                    val supported = r in state.availableResolutions
                    val (w, h) = if (state.orientation == CanvasOrientation.LANDSCAPE) r.long to r.short else r.short to r.long
                    Chip("${r.label}  $w×$h", state.resolution == r, {
                        onApply { it.resolution = r; it.videoBitrateKbps = if (r == Resolution.P1080) 4000 else 2500 }
                    }, enabled = !locked && supported)
                }
            }
            if (state.cameraSizes.isNotEmpty()) Text("摄像头支持：${state.cameraSizes}", style = MaterialTheme.typography.bodySmall)
        }

        Section("视频编码 · H.264 + AAC") {
            Text("帧率", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 25, 30).forEach { f -> Chip("$f fps", state.fps == f, { onApply { it.fps = f } }, enabled = !locked) }
            }
            Text("视频码率", style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                var br by remember(state.videoBitrateKbps) { mutableStateOf(state.videoBitrateKbps.toFloat()) }
                Slider(br, { br = (it / 250f).toInt() * 250f }, Modifier.weight(1f), enabled = !locked, valueRange = 500f..10000f,
                    onValueChangeFinished = { onApply { it.videoBitrateKbps = br.toInt() } },
                    colors = SliderDefaults.colors(thumbColor = Studio.Accent, activeTrackColor = Studio.Accent))
                Text("${"%.1f".format(br / 1000)} Mbps", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp).padding(start = 8.dp))
            }
            Text("关键帧间隔", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 4).forEach { g -> Chip("$g 秒", state.keyFrameIntervalSec == g, { onApply { it.keyFrameIntervalSec = g } }, enabled = !locked) }
            }
        }

        Section("网页") {
            SwitchRow("GPU 采集网页", "关闭后使用 CPU 软件绘制兜底（兼容性好，性能较低）", state.webGpu,
                { v -> onApply { it.webCaptureGpu = v } }, enabled = !locked)
            SwitchRow("允许局域网 HTTP 网页", "默认只允许 HTTPS；HTTPS 证书错误会正常报错", state.allowCleartext,
                { v -> onApply { it.allowCleartextWeb = v } })
        }

        Section("诊断") {
            SwitchRow("显示性能信息", "合成帧率、网页采集、内存与温度", showPerf, onShowPerf)
            SwitchRow("音画同步测试信号", "画面左上角每秒闪白 + 1kHz 提示音（替换麦克风），接收端用 tools/av_sync.py 测量", state.syncTest, onSyncTest)
            Text("摄像头旋转校正（个别机型方向异常时使用）", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 90, 180, 270).forEach { d -> Chip("$d°", state.cameraRotationFix == d, { onApply { it.cameraRotationFix = d } }, enabled = !locked) }
            }
        }
        Text("LiveComposer 0.2 · 直播中切到后台会自动停止推流", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
