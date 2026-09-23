package app.terndays.android.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.terndays.android.DataBus
import app.terndays.android.Prefs
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.geo.Cities
import app.terndays.android.punch.PunchScheduler
import app.terndays.android.punch.PunchService
import app.terndays.android.punch.ThresholdAlerts
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.Backup
import app.terndays.core.MigrationCodec
import app.terndays.core.Regions
import app.terndays.core.Thresholds
import app.terndays.core.WidgetStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ---------------------------------------------------------------------------
// 桌面小组件外观:每个选项上方一张迷你预览(配色直接取小组件的颜色资源,深浅模式自动跟随)
// ---------------------------------------------------------------------------

@Composable
internal fun WidgetStyleCard() {
    val context = LocalContext.current
    var style by remember { mutableStateOf(Prefs.widgetStyle(context)) }
    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("外观", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WidgetStyle.entries.forEach { value ->
                    val selected = value == style
                    Column(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .selectable(selected = selected, role = Role.RadioButton) {
                                style = value
                                Prefs.setWidgetStyle(context, value)
                                TernDaysWidgetProvider.updateAll(context)
                            }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MiniWidget(value, selected)
                        Text(
                            value.label, fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Td.AccentDeep else Td.Muted,
                        )
                    }
                }
            }
            Text(style.androidHint, fontSize = 12.sp, color = Td.Muted, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun MiniWidget(style: WidgetStyle, selected: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    val bg: Modifier = when (style) {
        WidgetStyle.PLAIN -> Modifier.background(colorResource(R.color.widgetSurface))
        WidgetStyle.MATERIAL -> Modifier.background(colorResource(R.color.widgetMaterialBase))
        WidgetStyle.GRADIENT -> Modifier.background(
            Brush.verticalGradient(listOf(colorResource(R.color.widgetGradTop), colorResource(R.color.widgetGradBottom))),
        )
    }
    val (year, primary) = when (style) {
        WidgetStyle.GRADIENT -> colorResource(R.color.widgetOnBrandYear) to colorResource(R.color.widgetOnBrand)
        else -> colorResource(R.color.widgetAccent) to colorResource(R.color.widgetPrimary)
    }
    Column(
        Modifier.fillMaxWidth().height(72.dp).clip(shape).then(bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) Td.Accent else Td.Border, shape)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.width(22.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(year))
        repeat(3) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(24.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(primary.copy(alpha = 0.85f)))
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(12.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(primary))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 天数提醒(阈值)
// ---------------------------------------------------------------------------

private val COMMON_REGIONS = listOf("CN", "HK", "MO", "TW", "JP", "KR", "SG", "US", "GB")

@Composable
internal fun ThresholdsCard(regionsSeen: List<String>) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(Thresholds.decode(Prefs.thresholds(context))) }
    var adding by remember { mutableStateOf(false) }
    fun save(next: List<Thresholds.Threshold>) {
        list = next
        Prefs.setThresholds(context, Thresholds.encode(next))
        val app = context.applicationContext
        Thread { runCatching { ThresholdAlerts.check(app) } }.apply { isDaemon = true }.start()
        DataBus.bump()
    }
    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                "某个国家 / 地区的天数接近上限时提醒你（如在中国大陆 183 天）。只在本机计算。",
                fontSize = 12.sp, color = Td.Muted, lineHeight = 18.sp, modifier = Modifier.padding(end = 8.dp),
            )
            list.forEach { t ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Regions.nameOf(t.regionCode)} · ${t.days} 天 · ${t.window.label}",
                        fontSize = 14.sp, color = Td.Ink, modifier = Modifier.weight(1f),
                    )
                    TdTextButton("删除", color = Td.WarmDeep, fontSize = 12.sp) { save(list - t) }
                }
            }
            TdTextButton("＋ 添加天数提醒", fontSize = 13.sp) { adding = true }
        }
    }
    if (adding) {
        AddThresholdDialog(
            regions = (regionsSeen + COMMON_REGIONS).distinct(),
            onDismiss = { adding = false },
        ) { t ->
            adding = false
            save(list.filterNot { it.regionCode == t.regionCode && it.window == t.window } + t)
        }
    }
}

@Composable
private fun AddThresholdDialog(regions: List<String>, onDismiss: () -> Unit, onAdd: (Thresholds.Threshold) -> Unit) {
    var region by remember { mutableStateOf(regions.first()) }
    var daysText by remember { mutableStateOf("183") }
    var window by remember { mutableStateOf(Thresholds.Window.YEAR) }
    val days = daysText.toIntOrNull()?.takeIf { it in 1..366 }
    Dialog(onDismissRequest = onDismiss) {
        TdCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("添加天数提醒", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                Text("国家 / 地区", fontSize = 12.sp, color = Td.Muted)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    regions.forEach { code -> ScopeChip(Regions.nameOf(code), code == region) { region = code } }
                }
                OutlinedTextField(
                    value = daysText, onValueChange = { v -> daysText = v.filter { it.isDigit() }.take(3) },
                    label = { Text("上限天数（1–366）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, isError = days == null, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Thresholds.Window.entries.forEach { w -> ScopeChip(w.label, w == window) { window = w } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TdTextButton("取消", color = Td.Muted, onClick = onDismiss)
                    TdTextButton("添加", enabled = days != null) {
                        days?.let { onAdd(Thresholds.Threshold(region, it, window)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 数据:暂停自动打卡 / 加密备份 / 从备份恢复 / 清除本机数据
// ---------------------------------------------------------------------------

private sealed interface DataTask {
    data class Busy(val message: String) : DataTask
    data class Result(val title: String, val message: String) : DataTask
}

@Composable
internal fun DataCard() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf(Prefs.punchPaused(context)) }
    var lastBackup by remember { mutableStateOf(Prefs.lastBackupAt(context)) }
    var task by remember { mutableStateOf<DataTask?>(null) }
    var askBackupPass by remember { mutableStateOf(false) }
    var backupPass by remember { mutableStateOf<String?>(null) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var clearStep by remember { mutableStateOf(0) }
    var clearCount by remember { mutableStateOf(0) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val pass = backupPass
        backupPass = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (pass == null) {
            // 选文件期间页面被重建(旋转、切深浅色、进程被回收),口令不会跨重建保存(不该落盘):
            // 删掉系统已经建好的空文件,并说清楚要重来,否则会留下一个恢复时报「不是备份文件」的 0 字节文件
            runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
            task = DataTask.Result("备份没有完成", "选文件时页面被重建了，口令没有保留。请重新备份一次。")
            return@rememberLauncherForActivityResult
        }
        task = DataTask.Busy("正在加密备份…")
        scope.launch {
            task = try {
                withContext(Dispatchers.IO) {
                    val db = PunchDb.get(app)
                    val json = MigrationCodec.toJson(
                        Cities.DATASET_VERSION, System.currentTimeMillis(), db.allPunches(), db.allOverrides(),
                    )
                    val bytes = Backup.seal(pass, json.toByteArray(Charsets.UTF_8))
                    app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("没法写入所选位置")
                }
                val now = System.currentTimeMillis()
                Prefs.setLastBackupAt(app, now)
                lastBackup = now
                DataTask.Result("备份完成 ✓", "请记住口令：忘了口令，备份就打不开。")
            } catch (e: Exception) {
                DataTask.Result("备份没有成功", e.message ?: e.javaClass.simpleName)
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            when {
                bytes == null -> task = DataTask.Result("恢复没有成功", "读不了所选文件")
                !Backup.isBackup(bytes) -> task = DataTask.Result("恢复没有成功", "这不是 TernDays 的备份文件")
                else -> restoreBytes = bytes
            }
        }
    }

    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("暂停自动打卡", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                    Text("换了新手机后，可以停掉旧手机的记录", fontSize = 12.sp, color = Td.Muted)
                }
                Switch(checked = paused, onCheckedChange = { v ->
                    paused = v
                    Prefs.setPunchPaused(app, v)
                    if (v) {
                        PunchScheduler.cancelAll(app)
                    } else {
                        PunchScheduler.scheduleNext(app)
                        PunchService.maybeBackfill(app, fromForeground = true)
                    }
                    DataBus.bump()
                })
            }
            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
            DataRow(
                "加密备份到文件",
                if (lastBackup > 0) "上次备份 ${stamp(lastBackup)}" else "用口令加密，存到手机或网盘；全程不联网",
            ) { askBackupPass = true }
            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
            DataRow("从备份恢复", "本机已有的记录保留，只补上缺的") { openDoc.launch(arrayOf("*/*")) }
            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
            DataRow("清除本机全部数据", "删除所有打卡与手动记录，无法撤销", danger = true) {
                scope.launch {
                    clearCount = withContext(Dispatchers.IO) { runCatching { PunchDb.get(app).recordCount() }.getOrDefault(0) }
                    clearStep = 1
                }
            }
        }
    }

    if (askBackupPass) {
        PassphraseDialog(title = "设置备份口令", confirmTwice = true, onDismiss = { askBackupPass = false }) { pass ->
            askBackupPass = false
            backupPass = pass
            createDoc.launch("TernDays-备份-${LocalDate.now()}.${Backup.FILE_EXTENSION}")
        }
    }
    restoreBytes?.let { bytes ->
        PassphraseDialog(title = "输入备份口令", confirmTwice = false, onDismiss = { restoreBytes = null }) { pass ->
            restoreBytes = null
            task = DataTask.Busy("正在解密并合并…")
            scope.launch {
                task = try {
                    val (result, remapped) = withContext(Dispatchers.IO) {
                        val payload = MigrationCodec.parse(String(Backup.open(pass, bytes), Charsets.UTF_8))
                        val db = PunchDb.get(app)
                        val r = db.mergeImported(payload.punches, payload.overrides)
                        // 备份可能来自旧版本城市库:按时间重放重解析(幂等)
                        val remapped = if (r.punchesAdded > 0) db.replayResolveAll(Cities.get(app)) else 0
                        r to remapped
                    }
                    TernDaysWidgetProvider.updateAll(app)
                    DataBus.bump()
                    DataTask.Result("恢复完成 ✓", mergeSummary(result, remapped, "备份"))
                } catch (e: IllegalArgumentException) {
                    DataTask.Result("恢复没有成功", e.message ?: "口令不对，或备份文件已损坏")
                } catch (e: Exception) {
                    DataTask.Result("恢复没有成功", e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
    when (clearStep) {
        1 -> ConfirmDialog(
            title = "清除本机全部数据？",
            text = "所有打卡与手动记录都会被删除，无法撤销。建议先「加密备份到文件」。",
            confirm = "继续",
            onDismiss = { clearStep = 0 },
            onConfirm = { clearStep = 2 },
        )
        2 -> ConfirmDialog(
            title = "确定清除全部 $clearCount 条记录？",
            text = "这是最后一次确认。",
            confirm = "清除",
            onDismiss = { clearStep = 0 },
            onConfirm = {
                clearStep = 0
                scope.launch {
                    withContext(Dispatchers.IO) { PunchDb.get(app).clearAll() }
                    Prefs.clearRecordState(app)
                    TernDaysWidgetProvider.updateAll(app)
                    DataBus.bump()
                    UndoCenter.show("已清除本机全部数据")
                }
            },
        )
    }
    when (val t = task) {
        null -> Unit
        is DataTask.Busy -> Dialog(onDismissRequest = { }) {
            TdCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(color = Td.Accent)
                    Text(t.message, fontSize = 13.sp, color = Td.Muted)
                }
            }
        }
        is DataTask.Result -> ConfirmDialog(
            title = t.title, text = t.message, confirm = "好", danger = false,
            onDismiss = { task = null }, onConfirm = { task = null },
        )
    }
}

/** 导入 / 恢复结果的说明(迁移与备份共用口径) */
internal fun mergeSummary(r: PunchDb.MergeResult, remapped: Int, source: String): String = buildString {
    append("新增 ${r.punchesAdded} 条打卡、${r.overridesAdded} 条手动记录")
    val skipped = r.punchesSkipped + r.overridesSkipped
    if (skipped > 0) append("；本机已有的 $skipped 条保持不变")
    val conflicts = r.punchesConflicting + r.overridesConflicting
    if (conflicts > 0) append("\n其中 $conflicts 条与${source}不一致，已保留本机版本")
    if (remapped > 0) append("\n已按本机城市库修正 $remapped 条城市判定")
}

private fun stamp(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return "%d-%02d-%02d %02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
}

@Composable
private fun DataRow(title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (danger) Td.Danger else Td.Ink)
            Text(sub, fontSize = 12.sp, color = Td.Muted)
        }
    }
}

@Composable
private fun PassphraseDialog(title: String, confirmTwice: Boolean, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    val tooShort = p1.length < Backup.MIN_PASSPHRASE
    val mismatch = confirmTwice && p1 != p2
    Dialog(onDismissRequest = onDismiss) {
        TdCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                if (confirmTwice) {
                    Text(
                        "至少 ${Backup.MIN_PASSPHRASE} 位。口令不保存在任何地方，忘了就无法恢复。",
                        fontSize = 12.sp, color = Td.Muted, lineHeight = 18.sp,
                    )
                }
                OutlinedTextField(
                    value = p1, onValueChange = { p1 = it }, label = { Text("口令") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = p2, onValueChange = { p2 = it }, label = { Text("再输一次") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true, isError = p2.isNotEmpty() && mismatch, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TdTextButton("取消", color = Td.Muted, onClick = onDismiss)
                    TdTextButton("确定", enabled = !tooShort && !mismatch) { onDone(p1) }
                }
            }
        }
    }
}
