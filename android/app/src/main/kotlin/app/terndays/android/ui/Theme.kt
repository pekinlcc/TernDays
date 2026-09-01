package app.terndays.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 设计稿配色（见 design 目录的 .dc.html） */
object Td {
    val Ink = Color(0xFF1F2B38)
    val Muted = Color(0xFF6B7A89)
    val Faint = Color(0xFF9AA7B4)
    val Border = Color(0xFFE4E9EE)
    val Divider = Color(0xFFEEF2F5)
    val Bg = Color(0xFFF4F6F8)
    val Surface = Color(0xFFFFFFFF)
    val Accent = Color(0xFF2E7FA8)
    val AccentDeep = Color(0xFF1F6289)
    val AccentSoft = Color(0xFFDEEDF5)
    val AccentFaintBg = Color(0xFFEAF3F8)
    val WarmSoft = Color(0xFFF6ECDD)
    val WarmDeep = Color(0xFF8A5F22)
}

@Composable
fun TernDaysTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Td.Accent,
            onPrimary = Color.White,
            primaryContainer = Td.AccentSoft,
            onPrimaryContainer = Td.AccentDeep,
            background = Td.Bg,
            onBackground = Td.Ink,
            surface = Td.Surface,
            onSurface = Td.Ink,
            surfaceVariant = Td.AccentSoft,
            onSurfaceVariant = Td.Muted,
            outline = Td.Border,
        ),
        content = content,
    )
}

/** 通用白色圆角卡片 */
@Composable
fun TdCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = Td.Surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 1.dp,
        content = content,
    )
}
