package app.livecomposer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livecomposer.core.AppConfig
import app.livecomposer.core.CanvasOrientation
import app.livecomposer.core.Resolution
import app.livecomposer.engine.EngineState
import app.livecomposer.rtmp.RtmpPublisher

class CamStreamActions(
    val onEdit: ((AppConfig) -> Unit) -> Unit,
    /** 开始第二路，返回错误信息或 null。 */
    val onStart: () -> String?,
    val onStop: () -> Unit,
    val validate: () -> String?,
)

/**
 * 第二路推流面板：把摄像头原始画面（不含网页、图片、文字等图层）推到另一个地址，与主推流独立开关。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CamStreamPanel(state: EngineState, act: CamStreamActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cam = state.cam
    var error by remember { mutableStateOf<String?>(null) }
    val st = cam.stats?.state
    val color = when {
        !cam.active -> if (cam.message != null) Studio.Live else Studio.TextDim
        st == RtmpPublisher.State.LIVE -> Studio.Ok
        st == RtmpPublisher.State.FAILED -> Studio.Live
        else -> Studio.Warn
    }
    PanelShell("第二路：纯摄像头", onClose, modifier) {
        Text("把摄像头原始画面单独推到另一个地址（不含网页、图片、文字等图层），和主推流各自开关、各自断线重连，互不影响。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))

        // 状态
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Studio.Surface)
                .border(1.dp, Studio.Outline, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val s = cam.stats
                Text(
                    when {
                        !cam.active -> "未开启"
                        s == null -> "连接中…"
                        else -> s.state.label
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (cam.active && s != null) {
                    val d = s.durationSec
                    Text("%02d:%02d:%02d · %.0f kbps · %.0f fps".format(d / 3600, d / 60 % 60, d % 60, s.bitrateKbps, s.outputFps),
                        style = MaterialTheme.typography.bodySmall)
                }
                cam.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (cam.active) Studio.Warn else Studio.Live) }
            }
        }

        Section("推流地址", modifier = Modifier.padding(top = 8.dp)) {
            Field("第二路推流地址", cam.url, { v -> act.onEdit { it.camUrl = v.trim() }; error = null },
                placeholder = "rtmp(s)://服务器/应用名/串流密钥?鉴权参数", secret = true, enabled = !cam.active, error = error,
                onChange = { v -> act.onEdit { it.camUrl = v.trim() }; error = null })
            Text("和主推流一样支持 RTMP / RTMPS 和带鉴权参数的地址，输入即加密保存。推流中不能修改，先停止第二路。",
                style = MaterialTheme.typography.bodySmall)
        }

        Section("画面与声音") {
            Text("分辨率（方向跟随画布）", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Resolution.P720, Resolution.P1080).filter { it in state.availableResolutions }.forEach { r ->
                    val (w, h) = if (state.orientation == CanvasOrientation.LANDSCAPE) r.long to r.short else r.short to r.long
                    Chip("${r.label}  $w×$h", cam.resolution == r, {
                        act.onEdit { it.camResolution = r; it.camBitrateKbps = if (r == Resolution.P1080) 4000 else 2500 }
                    }, enabled = !cam.active)
                }
            }
            Text("码率", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1500, 2500, 4000).forEach { k ->
                    Chip("${"%.1f".format(k / 1000f)} Mbps", cam.bitrateKbps == k, { act.onEdit { it.camBitrateKbps = k } }, enabled = !cam.active)
                }
            }
            SwitchRow("带麦克风声音", if (cam.audio) "只有麦克风原声（降噪后），不含网页和视频的声音" else "只推画面，没有声音",
                cam.audio, { v -> act.onEdit { it.camAudio = v } }, enabled = !cam.active)
            Text("帧率与主推流相同（最高 30fps）。多一路编码会增加发热，上行带宽需要两路码率之和。",
                style = MaterialTheme.typography.bodySmall)
        }

        // 开始 / 停止
        val canStart = cam.url.isNotBlank()
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp))
                .background(if (cam.active) Studio.Live else if (canStart) Studio.Accent else Studio.SurfaceHigher)
                .clickable(enabled = cam.active || canStart) {
                    if (cam.active) act.onStop()
                    else error = act.validate() ?: act.onStart()
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (cam.active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null,
                tint = if (cam.active || canStart) Studio.Bg else Studio.TextFaint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (cam.active) "停止第二路" else if (canStart) "开始第二路推流" else "先填写推流地址",
                color = if (cam.active || canStart) Studio.Bg else Studio.TextFaint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Text("停止第二路不影响主推流；主推流开播、停播也不影响第二路。", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** 顶部状态栏里的第二路入口：未开启时是灰色小按钮，推流中显示状态和码率。 */
@Composable
fun CamStreamChip(state: EngineState, active: Boolean, onClick: () -> Unit) {
    val cam = state.cam
    val s = cam.stats
    val color = when {
        !cam.active -> Studio.TextDim
        s?.state == RtmpPublisher.State.LIVE -> Studio.Ok
        else -> Studio.Warn
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) Studio.AccentDim else Studio.Glass)
            .border(1.dp, if (active) Studio.Accent else Studio.Outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Videocam, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            when {
                !cam.active -> "第二路"
                s == null -> "第二路 连接中"
                s.state == RtmpPublisher.State.LIVE -> "第二路 ${"%.1f".format(s.bitrateKbps / 1000f)}M"
                else -> "第二路 ${s.state.label}"
            },
            color = if (cam.active) Studio.Text else Studio.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}
