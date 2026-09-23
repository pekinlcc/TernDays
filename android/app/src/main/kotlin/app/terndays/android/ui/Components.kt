package app.terndays.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terndays.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 二级页面的返回栏。右侧占位与左侧返回键同为 48dp(触摸目标),标题才真正居中
 * (此前右侧只占 36dp,标题偏右 6dp)。
 */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconSquare(R.drawable.ic_chev_left, "返回") { onBack() }
        Text(
            title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
            modifier = Modifier.weight(1f).semantics { heading() }, textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.width(48.dp))
    }
}

/** 文字按钮:触摸区域至少 48dp,读屏念「按钮」。 */
@Composable
fun TdTextButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Td.AccentDeep,
    fontSize: TextUnit = 14.sp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
            color = if (enabled) color else Td.Faint,
        )
    }
}

/**
 * 页面底部的轻提示,可带一个动作(撤销)。进程级单例:更正写完就可以在任何页面弹出,
 * 页面切走也不丢;5 秒后自动消失。
 */
object UndoCenter {
    class Item(val message: String, val actionLabel: String?, val action: (suspend () -> Unit)?) {
        val id = System.nanoTime()
    }

    val current = mutableStateOf<Item?>(null)

    fun show(message: String) {
        current.value = Item(message, null, null)
    }

    fun show(message: String, actionLabel: String, action: suspend () -> Unit) {
        current.value = Item(message, actionLabel, action)
    }

    fun dismiss(item: Item) {
        if (current.value === item) current.value = null
    }
}

@Composable
fun UndoHost(modifier: Modifier = Modifier) {
    val item = UndoCenter.current.value ?: return
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) {
        delay(5_000)
        UndoCenter.dismiss(item)
    }
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(14.dp)).background(Td.Ink)
            .padding(start = 16.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.message, fontSize = 13.sp, color = Td.Bg,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        val action = item.action
        if (item.actionLabel != null && action != null) {
            TdTextButton(item.actionLabel, color = Td.Accent) {
                UndoCenter.dismiss(item)
                scope.launch { action() }
            }
        }
    }
}
