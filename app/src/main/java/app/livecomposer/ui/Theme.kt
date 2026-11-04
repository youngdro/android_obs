package app.livecomposer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** “演播室”深色主题：低饱和深灰承载画面，青色表示编辑/选中，红色只留给直播状态。 */
object Studio {
    val Bg = Color(0xFF0B0C10)
    val Surface = Color(0xFF15171D)
    val SurfaceHigh = Color(0xFF1E2129)
    val SurfaceHigher = Color(0xFF282C36)
    val Outline = Color(0xFF2E323D)
    val Text = Color(0xFFF2F3F5)
    val TextDim = Color(0xFF9AA0AC)
    val TextFaint = Color(0xFF666C78)
    val Accent = Color(0xFF3DDCFF)
    val AccentDim = Color(0x333DDCFF)
    val Live = Color(0xFFFF3355)
    val Warn = Color(0xFFFFB020)
    val Ok = Color(0xFF34D399)
    val Guide = Color(0xFFFFD60A)
    val Web = Color(0xFF3DDCFF)
    val Image = Color(0xFFB388FF)
    val TextLayer = Color(0xFFFFB020)
    val Camera = Color(0xFF34D399)
    val Media = Color(0xFFFF7AC6)
    val Scrim = Color(0xB3000000)
    val Glass = Color(0xCC15171D)
}

private val scheme = darkColorScheme(
    primary = Studio.Accent,
    onPrimary = Color(0xFF00202A),
    secondary = Studio.Accent,
    background = Studio.Bg,
    onBackground = Studio.Text,
    surface = Studio.Surface,
    onSurface = Studio.Text,
    surfaceVariant = Studio.SurfaceHigh,
    onSurfaceVariant = Studio.TextDim,
    outline = Studio.Outline,
    error = Studio.Live,
)

private val typography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Studio.Text),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Studio.Text),
    bodyMedium = TextStyle(fontSize = 14.sp, color = Studio.Text),
    bodySmall = TextStyle(fontSize = 12.sp, color = Studio.TextDim),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Studio.TextDim, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Studio.Text),
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
