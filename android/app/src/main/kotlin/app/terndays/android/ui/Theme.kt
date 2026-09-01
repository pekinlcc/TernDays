package app.terndays.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计稿配色(浅色见 design 目录 .dc.html,深色对应 DarkAlt 方向)。
 * getter 按当前深浅模式取值:系统切换深色时 Activity 因 uiMode 变化重建,
 * TernDaysTheme 重新执行并刷新 [Td.dark],全部调用点无需改动。
 */
object Td {
    internal var dark: Boolean = false

    val Ink: Color get() = if (dark) Color(0xFFE8EEF3) else Color(0xFF1F2B38)
    val Muted: Color get() = if (dark) Color(0xFFA8B6C2) else Color(0xFF5F6F7E)
    val Faint: Color get() = if (dark) Color(0xFF7E8C99) else Color(0xFF7A8894)
    val Border: Color get() = if (dark) Color(0xFF2C3A4A) else Color(0xFFE4E9EE)
    val Divider: Color get() = if (dark) Color(0xFF26313E) else Color(0xFFEEF2F5)
    val Bg: Color get() = if (dark) Color(0xFF141C26) else Color(0xFFF4F6F8)
    val Surface: Color get() = if (dark) Color(0xFF1D2937) else Color(0xFFFFFFFF)
    val Accent: Color get() = if (dark) Color(0xFF7CC0E8) else Color(0xFF2E7FA8)
    val AccentDeep: Color get() = if (dark) Color(0xFFA5D4EE) else Color(0xFF1F6289)
    val AccentSoft: Color get() = if (dark) Color(0xFF24435A) else Color(0xFFDEEDF5)
    val AccentFaintBg: Color get() = if (dark) Color(0xFF1E3648) else Color(0xFFEAF3F8)
    val WarmSoft: Color get() = if (dark) Color(0xFF3D3122) else Color(0xFFF6ECDD)
    val WarmDeep: Color get() = if (dark) Color(0xFFE0B570) else Color(0xFF8A5F22)

    /** 强调色按钮上的文字:深色模式强调色变浅,配深墨字才有对比。 */
    val OnAccent: Color get() = if (dark) Color(0xFF10222E) else Color(0xFFFFFFFF)

    /** 装饰性箭头等次要图形 */
    val Chevron: Color get() = if (dark) Color(0xFF57667A) else Color(0xFFC3CCD4)
}

@Composable
fun TernDaysTheme(content: @Composable () -> Unit) {
    Td.dark = isSystemInDarkTheme()
    val scheme = if (Td.dark) {
        darkColorScheme(
            primary = Td.Accent,
            onPrimary = Td.OnAccent,
            primaryContainer = Td.AccentSoft,
            onPrimaryContainer = Td.AccentDeep,
            background = Td.Bg,
            onBackground = Td.Ink,
            surface = Td.Surface,
            onSurface = Td.Ink,
            surfaceVariant = Td.AccentSoft,
            onSurfaceVariant = Td.Muted,
            outline = Td.Border,
        )
    } else {
        lightColorScheme(
            primary = Td.Accent,
            onPrimary = Td.OnAccent,
            primaryContainer = Td.AccentSoft,
            onPrimaryContainer = Td.AccentDeep,
            background = Td.Bg,
            onBackground = Td.Ink,
            surface = Td.Surface,
            onSurface = Td.Ink,
            surfaceVariant = Td.AccentSoft,
            onSurfaceVariant = Td.Muted,
            outline = Td.Border,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 通用圆角卡片 */
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
