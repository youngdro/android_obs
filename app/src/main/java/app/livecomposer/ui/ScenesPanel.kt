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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.livecomposer.core.CanvasOrientation
import app.livecomposer.engine.EngineState
import app.livecomposer.engine.SceneInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SceneActions(
    val onSave: (String) -> Unit,
    val onOverwrite: (String) -> Unit,
    val onApply: (String) -> Unit,
    val onRename: (String, String) -> Unit,
    val onDelete: (String) -> Unit,
    val onMove: (String, Int) -> Unit,
)

/**
 * 场景方案面板：把当前画面（图层、层级、位置、画布与编码设置）存成场景，一键切换；可存多个。
 */
@Composable
fun ScenesPanel(state: EngineState, act: SceneActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var newName by remember(state.scenes.size) { mutableStateOf("场景 ${state.scenes.size + 1}") }
    var editing by remember { mutableStateOf<String?>(null) }
    PanelShell("场景方案", onClose, modifier) {
        Text("保存当前的全部图层、层级、位置和画布设置，之后一键切换。推流地址不随场景变化。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))

        // 保存当前为新场景
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Studio.Surface)
                .border(1.dp, Studio.Outline, RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field("场景名称", newName, { newName = it }, onChange = { newName = it }, placeholder = "例如：开场、带货、访谈")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Studio.Accent)
                    .clickable { act.onSave(newName) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Save, null, tint = Studio.Bg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存当前画面为新场景", color = Studio.Bg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        if (state.isStreaming) {
            Text("直播中可以切换场景，画面立即生效；如果场景的画布方向、分辨率或帧率与当前不同，这些设置停播后才会应用。",
                style = MaterialTheme.typography.bodySmall, color = Studio.Warn, modifier = Modifier.padding(top = 10.dp))
        }

        Section("已保存（${state.scenes.size}）", modifier = Modifier.padding(top = 8.dp)) {
            if (state.scenes.isEmpty()) {
                Text("还没有场景。摆好画面后点上面的按钮保存。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            }
            state.scenes.forEachIndexed { i, sc ->
                SceneCard(
                    sc, active = sc.id == state.activeSceneId, editing = editing == sc.id,
                    first = i == 0, last = i == state.scenes.lastIndex, act = act,
                    onEdit = { editing = if (editing == sc.id) null else sc.id },
                )
            }
        }
    }
}

@Composable
private fun SceneCard(sc: SceneInfo, active: Boolean, editing: Boolean, first: Boolean, last: Boolean, act: SceneActions, onEdit: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (active) Studio.AccentDim else Studio.Surface)
            .border(1.dp, if (active) Studio.Accent.copy(alpha = 0.7f) else Studio.Outline, shape)
            .clickable { act.onApply(sc.id) }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sc.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Text("当前", color = Studio.Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Studio.Accent).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                Text(
                    "${if (sc.orientation == CanvasOrientation.LANDSCAPE) "横屏" else "竖屏"} ${sc.resolution.label} ${sc.fps}fps · " +
                        "${sc.layerTypes.size} 个图层 · ${TIME.format(Date(sc.savedAt))}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SmallIconButton(Icons.Rounded.Edit, "管理", onEdit, tint = if (editing) Studio.Accent else Studio.TextDim)
        }
        // 图层类型缩略
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            sc.layerTypes.asReversed().take(10).forEach { t ->
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(layerColor(t).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(layerIcon(t), null, tint = layerColor(t), modifier = Modifier.size(13.dp))
                }
            }
        }
        if (editing) {
            var name by remember(sc.id) { mutableStateOf(sc.name) }
            Column(Modifier.padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("名称", name, { name = it; act.onRename(sc.id, it) }, onChange = { name = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("用当前画面更新", false, { act.onOverwrite(sc.id) })
                    Spacer(Modifier.weight(1f))
                    SmallIconButton(Icons.Rounded.ArrowUpward, "上移", { act.onMove(sc.id, -1) }, enabled = !first)
                    SmallIconButton(Icons.Rounded.ArrowDownward, "下移", { act.onMove(sc.id, 1) }, enabled = !last)
                    SmallIconButton(Icons.Rounded.Delete, "删除", { act.onDelete(sc.id) }, tint = Studio.Live)
                }
            }
        } else {
            Text("点按切换", style = MaterialTheme.typography.labelSmall, color = Studio.TextFaint, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private val TIME = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
