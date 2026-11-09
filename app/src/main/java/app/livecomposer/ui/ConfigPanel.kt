package app.livecomposer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livecomposer.core.AppConfig
import app.livecomposer.core.ConfigBundle
import app.livecomposer.engine.EngineState

class ConfigActions(
    /** 导出到文件，参数：是否包含密钥。 */
    val onExport: (Boolean) -> Unit,
    val onImportFile: () -> Unit,
    val onImportCloud: () -> Unit,
    val onEditCloud: ((AppConfig) -> Unit) -> Unit,
    val onConfirmImport: () -> Unit,
    val onCancelImport: () -> Unit,
)

/** 待确认的配置（来自文件或云端）。 */
class ImportPreview(val parsed: ConfigBundle.Parsed, val source: String)

/**
 * 整体配置：导出到文件、从文件加载、按 liveId 从云端一键加载。
 * 加载会替换全部设置、图层和场景（本机设备 ID 不变），需确认；推流中不能加载。
 */
@Composable
fun ConfigPanel(state: EngineState, ui: StudioUi, act: ConfigActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var includeSecrets by remember { mutableStateOf(true) }
    val locked = state.isStreaming || state.cam.active
    val cloud = state.cloud
    PanelShell("导入 / 导出配置", onClose, modifier) {
        Text("把这台手机的全部设置（画布、编码、推流地址、降噪、省电、第二路、截图通道）、当前图层和全部场景存成一个文件，换手机或重装后一键恢复。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))

        Section("导出") {
            SwitchRow("包含推流地址和密钥", if (includeSecrets) "文件里有推流密钥、第二路地址和截图接入密钥，请妥善保管，不要发到群里" else "不含任何密钥，加载时保留对方手机已填的",
                includeSecrets, { includeSecrets = it })
            ActionRow(Icons.Rounded.FileDownload, "导出到文件", true) { act.onExport(includeSecrets) }
        }

        Section("从文件加载") {
            ActionRow(Icons.Rounded.FileUpload, if (locked) "推流中不能加载" else "选择配置文件", !locked) { act.onImportFile() }
        }

        Section("从云端加载") {
            Field("配置接口地址", cloud.url, { v -> act.onEditCloud { it.cloudConfigUrl = v.trim() } },
                placeholder = "https://服务端域名/api/livecomposer/device-config",
                onChange = { v -> act.onEditCloud { it.cloudConfigUrl = v.trim() } })
            Field("liveId", cloud.liveId, { v -> act.onEditCloud { it.cloudLiveId = v.trim() } },
                placeholder = "直播间 liveId", onChange = { v -> act.onEditCloud { it.cloudLiveId = v.trim() } })
            Field("访问密钥（可选）", cloud.token, { v -> act.onEditCloud { it.cloudConfigToken = v.trim() } },
                placeholder = "服务端要求鉴权时填写", secret = true,
                onChange = { v -> act.onEditCloud { it.cloudConfigToken = v.trim() } })
            val can = !locked && cloud.url.isNotBlank() && cloud.liveId.isNotBlank() && !ui.cloudLoading
            ActionRow(Icons.Rounded.CloudDownload,
                when { locked -> "推流中不能加载"; ui.cloudLoading -> "正在获取…"; cloud.url.isBlank() -> "先填写配置接口地址"; cloud.liveId.isBlank() -> "先填写 liveId"; else -> "获取并加载" },
                can) { act.onImportCloud() }
            ui.cloudError?.let { Text(it, color = Studio.Live, style = MaterialTheme.typography.bodySmall) }
            Text("按 liveId 向服务端获取这场直播的配置，确认后一键设置好。地址、liveId 和密钥只存在本机，不会被导出。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Studio.Accent else Studio.SurfaceHigher)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) Studio.Bg else Studio.TextFaint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) Studio.Bg else Studio.TextFaint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** 加载前的确认内容。 */
fun ImportPreview.describe(): String = buildString {
    val p = parsed
    append("来源：$source")
    if (p.appVersion.isNotEmpty()) append("（导出自 LiveComposer ${p.appVersion}")
    if (p.exportedAt > 0) append(if (p.appVersion.isNotEmpty()) "，" else "（").append(
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(p.exportedAt)))
    if (p.appVersion.isNotEmpty() || p.exportedAt > 0) append("）")
    append("\n图层 ${p.layerCount} 个")
    if (p.scenes != null) append("，场景 ${p.sceneCount} 个") else append("，不含场景（保留本机场景）")
    append(if (p.secrets != null) "，包含推流地址和密钥" else "")
    append("\n\n加载后会替换这台手机现在的设置、图层")
    if (p.scenes != null) append("和场景")
    append("，画面会重新加载一次。本机设备 ID 不变。")
    if (p.warnings.isNotEmpty()) append("\n\n注意：\n").append(p.warnings.joinToString("\n") { "· $it" })
}
