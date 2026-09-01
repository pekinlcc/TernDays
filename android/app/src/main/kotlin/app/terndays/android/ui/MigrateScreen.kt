package app.terndays.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terndays.android.R
import app.terndays.android.migrate.MigrateServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private sealed interface SendState {
    data object Preparing : SendState
    data class Showing(val qr: Bitmap, val status: String?) : SendState
    data class Done(val count: Int) : SendState
    data class Failed(val message: String) : SendState
}

/** 旧手机:「迁移到新手机」页——展示二维码,等新手机扫码连入并取走数据。 */
@Composable
fun MigrateSendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<SendState>(SendState.Preparing) }

    DisposableEffect(Unit) {
        val server = MigrateServer(
            context,
            onReady = { qrText -> state = SendState.Showing(qrBitmap(qrText), null) },
            onStatus = { msg ->
                (state as? SendState.Showing)?.let { state = it.copy(status = msg) }
            },
            onDone = { count -> state = SendState.Done(count) },
            onError = { msg -> state = SendState.Failed(msg) },
        )
        server.start()
        onDispose { server.stop() }
    }

    Column(Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(R.drawable.ic_chev_left, "返回") { onBack() }
            Text(
                "迁移到新手机", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(36.dp))
        }
        Spacer(Modifier.height(20.dp))

        when (val s = state) {
            is SendState.Preparing -> CenterHint { CircularProgressIndicator(color = Td.Accent) }
            is SendState.Showing -> {
                TdCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)).background(Color.White)
                                .padding(10.dp),
                        ) {
                            Image(
                                s.qr.asImageBitmap(), contentDescription = "迁移二维码",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            s.status ?: "在新手机上打开 TernDays → 设置 → 从旧手机导入,扫这个码",
                            fontSize = 13.sp, color = if (s.status != null) Td.AccentDeep else Td.Muted,
                            textAlign = TextAlign.Center, lineHeight = 20.sp,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "两台手机需连接同一个 Wi-Fi(或旧手机开热点、新手机连上)。\n" +
                        "数据经加密直接在两台手机之间传输,不经过任何服务器;\n" +
                        "此页面关闭后二维码立即失效。",
                    fontSize = 12.sp, color = Td.Faint, textAlign = TextAlign.Center, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is SendState.Done -> CenterHint {
                Text("✓", fontSize = 44.sp, color = Td.Accent)
                Spacer(Modifier.height(10.dp))
                Text("迁移完成", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "新手机已导入 ${s.count} 条记录。本机数据保持不变。",
                    fontSize = 13.sp, color = Td.Muted, textAlign = TextAlign.Center,
                )
            }
            is SendState.Failed -> CenterHint {
                Text(s.message, fontSize = 13.sp, color = Td.WarmDeep, textAlign = TextAlign.Center, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun CenterHint(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
