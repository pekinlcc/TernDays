package app.terndays.android.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.terndays.android.R
import app.terndays.core.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ExportScreen(initialYear: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var year by rememberSaveable { mutableIntStateOf(initialYear) }
    var useXlsx by rememberSaveable { mutableStateOf(true) }
    var incSummary by rememberSaveable { mutableStateOf(true) }
    var incDaily by rememberSaveable { mutableStateOf(true) }
    var busy by rememberSaveable { mutableStateOf(false) }

    val data by produceState<YearData?>(initialValue = null, year) {
        value = loadYearData(context, year)
    }

    Column(Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(R.drawable.ic_chev_left) { onBack() }
            Text(
                "导出数据", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(36.dp))
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            item { SectionLabel("导出范围") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (data?.years ?: listOf(year)).forEach { y ->
                        val selected = y == year
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Td.AccentFaintBg else Td.Surface)
                                .border(
                                    if (selected) 1.5.dp else 1.dp,
                                    if (selected) Td.Accent else Td.Border,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { year = y }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$y 年", fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) Td.AccentDeep else Td.Muted,
                            )
                        }
                    }
                }
            }
            item { SectionLabel("文件格式") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormatCard("Excel", ".xlsx · 分表：汇总 + 明细", useXlsx, Modifier.weight(1f)) { useXlsx = true }
                    FormatCard("CSV", ".csv · 通用纯文本格式", !useXlsx, Modifier.weight(1f)) { useXlsx = false }
                }
            }
            item { SectionLabel("导出内容") }
            item {
                TdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        CheckRow("城市汇总", "每个城市的累计天数", incSummary) { incSummary = it }
                        HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                        CheckRow("每日明细", "每天早 / 晚打卡的时间、城市与计天结果", incDaily) { incDaily = it }
                    }
                }
            }
            item {
                val rows = data?.let { Exporter.dailyRows(it.stats, it.punches) }
                    ?.filter { it.morning != null || it.evening != null }?.takeLast(3)
                if (!rows.isNullOrEmpty()) {
                    TdCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("预览 · 每日明细", fontSize = 12.sp, color = Td.Faint)
                            PreviewRow(listOf("日期", "早7点", "晚5点", "计入"), header = true)
                            rows.forEach { r ->
                                PreviewRow(
                                    listOf(
                                        "%02d-%02d".format(r.date.monthValue, r.date.dayOfMonth),
                                        r.morning?.cityName ?: "–",
                                        r.evening?.cityName ?: "–",
                                        r.attribution.shares.joinToString(" ") {
                                            it.cityName + (if (it.weight >= 1.0) " +1" else " +0.5")
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(vertical = 10.dp).navigationBarsPadding()) {
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (busy) Td.Faint else Td.Accent)
                    .clickable(enabled = !busy && (incSummary || incDaily)) {
                        busy = true
                        scope.launch {
                            try {
                                shareExport(context, year, useXlsx, incSummary, incDaily)
                            } catch (e: Exception) {
                                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_share), null, Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (busy) "正在生成…" else "生成文件并分享",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "通过系统分享面板保存到手机或发送给其他 App\n文件在本机生成，不经过网络",
                fontSize = 11.sp, color = Td.Faint, textAlign = TextAlign.Center, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

@Composable
private fun FormatCard(title: String, sub: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.clip(shape)
            .background(if (selected) Td.AccentFaintBg else Td.Surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Td.Accent else Td.Border, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = if (selected) Td.AccentDeep else Td.Ink,
            )
            Text(sub, fontSize = 11.sp, color = if (selected) Td.Muted else Td.Faint)
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).size(18.dp).clip(RoundedCornerShape(9.dp)).background(Td.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(11.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (checked) Td.Accent else Td.Surface)
                .border(if (checked) 0.dp else 1.5.dp, if (checked) Td.Accent else Td.Border, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(12.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
            Text(sub, fontSize = 12.sp, color = Td.Muted)
        }
    }
}

@Composable
private fun PreviewRow(cells: List<String>, header: Boolean = false) {
    Row {
        cells.forEachIndexed { i, c ->
            Text(
                c, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = if (header) Td.Faint else Td.Ink,
                modifier = Modifier.weight(if (i == 3) 1.4f else 1f),
                maxLines = 1,
            )
        }
    }
}

private suspend fun shareExport(
    context: Context,
    year: Int,
    useXlsx: Boolean,
    incSummary: Boolean,
    incDaily: Boolean,
) {
    val uri = withContext(Dispatchers.IO) {
        val data = loadYearData(context, year)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file: File
        if (useXlsx) {
            file = File(dir, "TernDays-$year.xlsx")
            file.writeBytes(Exporter.exportXlsx(data.stats, data.punches, incSummary, incDaily))
        } else {
            file = File(dir, "TernDays-$year.csv")
            file.writeText(Exporter.exportCsv(data.stats, data.punches, incSummary, incDaily))
        }
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
    val mime = if (useXlsx) {
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    } else {
        "text/csv"
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "导出 TernDays 数据"))
}
