package app.livecomposer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livecomposer.engine.EngineState
import app.livecomposer.vision.DeviceVisionClient
import app.livecomposer.vision.DeviceVisionClient.State

class VisionActions(
    val onEdit: ((app.livecomposer.core.AppConfig) -> Unit) -> Unit,
    /** 连接，返回错误信息或 null。 */
    val onConnect: () -> String?,
    val onDisconnect: () -> Unit,
)

private fun visionColor(s: State) = when (s) {
    State.ONLINE -> Studio.Ok
    State.IDLE -> Studio.TextDim
    State.STOPPED -> Studio.Live
    else -> Studio.Warn
}

/**
 * 截图通道面板：连接助播服务端的设备视觉 WebSocket，服务端要求截图时把摄像头当下的画面发过去。
 */
@Composable
fun VisionPanel(state: EngineState, act: VisionActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val v = state.vision
    val st = v.status
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val locked = v.enabled

    PanelShell("截图通道", onClose, modifier) {
        Text("连接助播服务端后，服务端需要时会要求本机截图，App 把摄像头此刻的原始画面（不含网页、文字等图层）压成 JPEG 发过去。不影响推流。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))

        // 状态
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Studio.Surface)
                .border(1.dp, Studio.Outline, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(visionColor(st.state)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(st.state.label, style = MaterialTheme.typography.titleSmall)
                if (st.state == State.ONLINE || st.captures > 0 || st.failures > 0) {
                    Text("已截图 ${st.captures} 次" + (if (st.failures > 0) " · 失败 ${st.failures} 次" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                st.lastCapture?.let { Text("最近：$it", style = MaterialTheme.typography.bodySmall) }
                st.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (st.state == State.STOPPED) Studio.Live else if (st.state == State.ONLINE) Studio.TextDim else Studio.Warn)
                }
            }
        }

        Section("连接信息", modifier = Modifier.padding(top = 8.dp)) {
            Field("服务域名", v.baseUrl, { s -> act.onEdit { it.visionBaseUrl = s.trim() }; error = null },
                placeholder = DeviceVisionClient.DEFAULT_BASE_URL, enabled = !locked,
                onChange = { s -> act.onEdit { it.visionBaseUrl = s.trim() }; error = null })
            if (!locked && v.baseUrl.trim().trimEnd('/') != DeviceVisionClient.DEFAULT_BASE_URL) {
                Text("恢复默认域名", color = Studio.Accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { act.onEdit { it.visionBaseUrl = DeviceVisionClient.DEFAULT_BASE_URL } }.padding(vertical = 2.dp))
            }
            Field("liveId", v.liveId, { s -> act.onEdit { it.visionLiveId = s.trim() }; error = null },
                placeholder = "淘宝直播间 liveId", enabled = !locked,
                onChange = { s -> act.onEdit { it.visionLiveId = s.trim() }; error = null })
            Field("设备接入密钥", v.apiKey, { s -> act.onEdit { it.visionApiKey = s.trim() }; error = null },
                placeholder = "由服务端提供", secret = true, enabled = !locked, error = error,
                onChange = { s -> act.onEdit { it.visionApiKey = s.trim() }; error = null })
            Text("密钥加密保存在本机，只放在请求头里发给服务端。连接中不能修改，先断开。",
                style = MaterialTheme.typography.bodySmall)
        }

        Section("本机设备 ID") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.deviceId, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Studio.Text, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { clipboard.setText(AnnotatedString(v.deviceId)); copied = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, tint = Studio.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) "已复制" else "复制", color = Studio.Accent, fontSize = 13.sp)
                }
            }
            Text("首次安装时生成，之后固定不变（卸载重装会重新生成）。服务端用它识别这台手机。",
                style = MaterialTheme.typography.bodySmall)
        }

        // 连接 / 断开
        val canConnect = v.liveId.isNotBlank() && v.apiKey.isNotBlank() && v.baseUrl.isNotBlank()
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp))
                .background(if (locked) Studio.Live else if (canConnect) Studio.Accent else Studio.SurfaceHigher)
                .clickable(enabled = locked || canConnect) {
                    if (locked) act.onDisconnect() else error = act.onConnect()
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            val on = locked || canConnect
            Icon(if (locked) Icons.Rounded.LinkOff else Icons.Rounded.Link, null,
                tint = if (on) Studio.Bg else Studio.TextFaint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (locked) "断开" else if (canConnect) "连接" else "先填写 liveId 和密钥",
                color = if (on) Studio.Bg else Studio.TextFaint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Text("连接后会一直保持，断线自动重连；下次打开 App 也会自动连接，直到点“断开”。截图需要摄像头开着。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 顶部状态栏里的截图通道入口。 */
@Composable
fun VisionChip(state: EngineState, active: Boolean, onClick: () -> Unit) {
    val v = state.vision
    val color = if (v.enabled) visionColor(v.status.state) else Studio.TextDim
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) Studio.AccentDim else Studio.Glass)
            .border(1.dp, if (active) Studio.Accent else Studio.Outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CameraAlt, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            if (!v.enabled) "截图通道" else if (v.status.state == State.ONLINE) "截图 ${v.status.captures}" else "截图 ${v.status.state.label}",
            color = if (v.enabled) Studio.Text else Studio.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}
