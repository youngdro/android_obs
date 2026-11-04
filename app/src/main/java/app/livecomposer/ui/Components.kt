package app.livecomposer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 圆形图标按钮（工具栏）。 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tint: Color = Studio.Text,
    enabled: Boolean = true,
    size: Dp = 44.dp,
) {
    Column(modifier.alpha(if (enabled) 1f else 0.4f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (active) Studio.AccentDim else Studio.SurfaceHigh)
                .border(1.dp, if (active) Studio.Accent else Color.Transparent, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (active) Studio.Accent else tint, modifier = Modifier.size(size * 0.5f))
        }
        if (label != null) {
            Spacer(Modifier.height(3.dp))
            Text(label, fontSize = 10.sp, color = if (active) Studio.Accent else Studio.TextDim, maxLines = 1)
        }
    }
}

/** 小号图标按钮（列表行、面板内）。 */
@Composable
fun SmallIconButton(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = Studio.TextDim, enabled: Boolean = true) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp)) }
}

/** 选择芯片。 */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Studio.AccentDim else Studio.SurfaceHigh)
            .border(1.dp, if (selected) Studio.Accent else Studio.Outline, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) Studio.Accent else Studio.Text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** 分段选择（等宽）。 */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Studio.SurfaceHigh).padding(3.dp).alpha(if (enabled) 1f else 0.45f),
    ) {
        options.forEach { o ->
            val sel = o == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (sel) Studio.SurfaceHigher else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(o) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), fontSize = 13.sp, color = if (sel) Studio.Text else Studio.TextDim, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** 面板中的分组卡片。 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            trailing?.invoke(this)
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Studio.Surface)
                .border(1.dp, Studio.Outline, RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** 开关行。 */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Studio.Accent, checkedThumbColor = Color.White),
        )
    }
}

/** 紧凑文本输入框（深色，带标签）。提交时机：失去焦点或按下 IME 完成。 */
@Composable
fun Field(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    number: Boolean = false,
    secret: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    singleLine: Boolean = true,
    suffix: String? = null,
    onChange: ((String) -> Unit)? = null,
) {
    var text by remember(value) { mutableStateOf(value) }
    var reveal by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Studio.SurfaceHigh)
                .border(1.dp, when { error != null -> Studio.Live; focused -> Studio.Accent; else -> Studio.Outline }, RoundedCornerShape(10.dp))
                .alpha(if (enabled) 1f else 0.5f)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text(placeholder, color = Studio.TextFaint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it; onChange?.invoke(it) },
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = TextStyle(color = Studio.Text, fontSize = 14.sp, fontFamily = if (number) FontFamily.Monospace else FontFamily.Default),
                    cursorBrush = SolidColor(Studio.Accent),
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when { number -> KeyboardType.Number; secret -> KeyboardType.Password; else -> KeyboardType.Uri },
                        imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged {
                        if (focused && !it.isFocused && text != value) onCommit(text)
                        focused = it.isFocused
                    },
                )
            }
            if (suffix != null) Text(suffix, color = Studio.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            if (secret) {
                Text(if (reveal) "隐藏" else "显示", color = Studio.Accent, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp).clickable { reveal = !reveal })
            }
        }
        if (error != null) Text(error, color = Studio.Live, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 数值输入（X/Y/W/H），提交时解析。 */
@Composable
fun NumberField(label: String, value: Float, onCommit: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Field(label, "%.0f".format(value), { s -> s.toFloatOrNull()?.let(onCommit) }, modifier, number = true, enabled = enabled)
}

/** 状态药丸。 */
@Composable
fun Pill(text: String, color: Color, filled: Boolean, pulsing: Boolean = false, leadingDot: Boolean = true) {
    val a = if (pulsing) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value
    } else 1f
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) color else Studio.Glass)
            .border(BorderStroke(1.dp, if (filled) Color.Transparent else color.copy(alpha = 0.6f)), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingDot) {
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(if (filled) Color.White else color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (filled) Color.White else Studio.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 玻璃质感信息块。 */
@Composable
fun GlassChip(text: String, color: Color = Studio.Text) {
    Text(
        text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Studio.Glass).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** 一次性提示（自动消失）。 */
@Composable
fun Toast(message: String?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    LaunchedEffect(message) { kotlinx.coroutines.delay(3500); onDone() }
    Text(
        message, color = Studio.Text, fontSize = 13.sp,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Studio.SurfaceHigher)
            .border(1.dp, Studio.Outline, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
