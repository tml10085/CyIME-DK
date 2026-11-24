package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.QueryStats
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.stats.TypingStats
import com.kingzcheung.xime.stats.TypingStatsData
import com.kingzcheung.xime.stats.TypingStatsData.Day
import com.kingzcheung.xime.stats.TypingStatsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 打字统计（设置首页最上面一行进来）：今天、每天、时段、效率、纠错、趣味换算。
 * 只读本机计数，不联网、不记文字；卡片用 [SettingsSection]，跟着主题和玻璃走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypingStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<TypingStatsData?>(null) }
    var enabled by remember { mutableStateOf(TypingStats.isEnabled(context)) }
    var touchLog by remember { mutableStateOf(com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context)
        .getBoolean(com.kingzcheung.xime.stats.TouchLog.PREF_KEY, false)) }
    LaunchedEffect(Unit) { data = withContext(Dispatchers.IO) { TypingStats.snapshot(context) } }
    val today = remember { TypingStats.today() }

    Scaffold(modifier = Modifier.testTag("typing-stats"), containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(title = { Text("打字统计") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val stats = data ?: TypingStatsData()
            TodayCard(stats, today)
            DailyCard(stats, today)
            HoursCard(stats)
            EfficiencyCard(stats)
            ComboSpeedCard(stats)
            CorrectionCard(stats)
            FunCard(stats)
            SettingsSection(title = "设置", content = {
                SettingsToggleItem(Icons.TwoTone.QueryStats, "记录打字统计", "关闭后停止计数，已有数据保留",
                    checked = enabled, onCheckedChange = { enabled = it; TypingStats.setEnabled(context, it) },
                    costItem = com.kingzcheung.xime.settings.PerformanceCosts.Item.TYPING_STATS)
                SettingsToggleItem(Icons.TwoTone.TouchApp, "记录按键触点（调试）",
                    "记下每次按在键的哪个位置、之后有没有删，用来找误触多的键；会记下按了哪些字母",
                    checked = touchLog, onCheckedChange = {
                        touchLog = it
                        com.kingzcheung.xime.stats.TouchLog.setEnabled(context, it)
                    })
                SettingsItem(Icons.TwoTone.Share, "导出触点记录", "分享 touch-log.csv（CSV 表格）", onClick = {
                    val file = com.kingzcheung.xime.stats.TouchLog.file(context)
                    if (!file.isFile) {
                        android.widget.Toast.makeText(context, "还没有触点记录", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/csv")
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(android.content.Intent.createChooser(send, "导出触点记录")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
                SettingsItem(Icons.TwoTone.Delete, "清除触点记录", "删掉本机保存的触点记录", onClick = {
                    com.kingzcheung.xime.stats.TouchLog.clear(context)
                    android.widget.Toast.makeText(context, "已清除", android.widget.Toast.LENGTH_SHORT).show()
                })
            })
            SettingsNote("仅在本机计数，只记字数、按键数和次数，不记录输入内容，不联网。密码框和无痕模式不计数。" +
                "码长不含删除键，粘贴不计入字数和速度。分享内容仅含速度和字数。" +
                "按键触点记录默认关，只存本机，最多约 8 MB，导出由你自己分享。")
        }
    }
}

// ---------- 卡片 ----------

@Composable
private fun TodayCard(stats: TypingStatsData, today: Int) {
    val total = stats.total[Day.CHARS]
    val title = TypingStatsText.titleFor(total)
    SettingsSection(title = "今天", content = {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(TypingStatsText.count(stats.chars(today)), fontSize = 44.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, lineHeight = 46.sp)
                Text(" 字", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Text("${title.badge} ${title.name}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp))
            }
            TypingStatsText.nextTitle(total)?.let { (next, left) ->
                Text("距「${next.name}」还差 ${TypingStatsText.count(left)} 字", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat(stats.speed(today)?.toString() ?: "—", "速度 字/分", Modifier.weight(1f))
                MiniStat(stats.bestSpeed.takeIf { it > 0 }?.toString() ?: "—", "最快 字/分", Modifier.weight(1f))
                MiniStat("${stats.streak(today)}", "连续天数", Modifier.weight(1f))
                MiniStat(TypingStatsText.count(total), "累计字数", Modifier.weight(1f))
            }
        }
    })
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
        .padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun DailyCard(stats: TypingStatsData, today: Int) {
    var year by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    SettingsSection(title = "每日字数", content = {
        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("近 30 天", !year) { year = false }
                Pill("全年", year) { year = true }
            }
            Spacer(Modifier.height(12.dp))
            if (!year) {
                val values = (29 downTo 0).map { stats.chars(today - it) }
                val max = values.max().coerceAtLeast(1)
                Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                    val gap = 3.dp.toPx()
                    val w = (size.width - gap * 29) / 30
                    values.forEachIndexed { i, v ->
                        val h = if (v == 0L) 2.dp.toPx() else (size.height * v / max).coerceAtLeast(3.dp.toPx())
                        drawRoundRect(if (v == 0L) empty else accent, Offset(i * (w + gap), size.height - h), Size(w, h),
                            CornerRadius(2.dp.toPx()))
                    }
                }
                AxisLabels(listOf("30 天前", "今天"))
                Text("单日最高 ${TypingStatsText.count(stats.bestDayChars)} 字", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            } else {
                // 像日历一样一列一周：53 列 × 7 行，最右一列是这周，颜色越深打得越多。
                val weekday = Math.floorMod(today + 3, 7) // 1970-01-01 是星期四；0 = 星期一
                val start = today - weekday - 52 * 7
                val max = (start..today).maxOf { stats.chars(it) }.coerceAtLeast(1)
                Canvas(Modifier.fillMaxWidth().aspectRatio(53f / 7f)) {
                    val gap = 1.5.dp.toPx()
                    val cell = (size.width - gap * 52) / 53
                    for (d in start..today) {
                        val col = (d - start) / 7
                        val row = (d - start) % 7
                        val v = stats.chars(d)
                        val color = if (v == 0L) empty else accent.copy(alpha = 0.25f + 0.75f * v / max)
                        drawRoundRect(color, Offset(col * (cell + gap), row * (cell + gap)), Size(cell, cell),
                            CornerRadius(1.5.dp.toPx()))
                    }
                }
                AxisLabels(listOf("一年前", "本周"))
                val activeDays = (start..today).count { stats.chars(it) > 0 }
                Text("近一年活跃 $activeDays 天", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    })
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp))
}

@Composable
private fun AxisLabels(labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun HoursCard(stats: TypingStatsData) {
    val accent = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val peak = stats.hours.indices.maxByOrNull { stats.hours[it] }?.takeIf { stats.hours[it] > 0 }
    SettingsSection(title = "时段分布", content = {
        Column(Modifier.padding(16.dp)) {
            val max = stats.hours.max().coerceAtLeast(1)
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val gap = 2.dp.toPx()
                val w = (size.width - gap * 23) / 24
                stats.hours.forEachIndexed { i, v ->
                    val h = if (v == 0L) 2.dp.toPx() else (size.height * v / max).coerceAtLeast(3.dp.toPx())
                    drawRoundRect(if (v == 0L) empty else accent, Offset(i * (w + gap), size.height - h), Size(w, h),
                        CornerRadius(2.dp.toPx()))
                }
            }
            AxisLabels(listOf("0 点", "6", "12", "18", "23 点"))
            if (peak != null) Text("高峰 $peak:00–${peak + 1}:00", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EfficiencyCard(stats: TypingStatsData) {
    val colors = sourceColors()
    SettingsSection(title = "效率", content = {
        Column(Modifier.padding(16.dp)) {
            StatRow("平均码长", stats.keysPerChar()?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—")
            StatRow("首选率", stats.firstPickRate()?.let(TypingStatsText::percent) ?: "—")
            StatRow("联想上屏", "${TypingStatsText.count(stats.total[Day.ASSOC_CHARS])} 字")
            StatRow("粘贴", "${TypingStatsText.count(stats.total[Day.PASTED])} 字")
            val sum = stats.sources.sum()
            if (sum > 0) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                    stats.sources.forEachIndexed { i, v ->
                        if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxSize().background(colors[i]))
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TypingStatsData.Source.entries.forEachIndexed { i, source ->
                        val v = stats.sources[i]
                        if (v > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(colors[i]))
                            Text(" ${source.label} ${TypingStatsText.percent(v.toDouble() / sum)}",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    })
}

/**
 * 各输入方式（语言 · 方案 · 布局）分开的速度和用量，字多的在前；下面两个按钮把它做成图或文字分享。
 * 从加了这张卡的版本开始分开记，之前的字只在总数里。
 */
@Composable
private fun ComboSpeedCard(stats: TypingStatsData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember(stats) { stats.comboList() }
    val version = com.kingzcheung.xime.BuildConfig.VERSION_NAME
    val scheme = MaterialTheme.colorScheme
    val cardColors = TypingStatsShare.CardColors(background = scheme.surface.toArgb(), onBackground = scheme.onSurface.toArgb(),
        muted = scheme.onSurfaceVariant.toArgb(), accent = scheme.primary.toArgb())
    SettingsSection(title = "各输入方式速度", content = {
        Column(Modifier.padding(16.dp)) {
            if (rows.isEmpty()) {
                Text("暂无数据。按语言、方案、布局分别统计。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val max = rows.first().chars.coerceAtLeast(1)
                rows.forEach { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(TypingStatsText.speedText(c) ?: "—", style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(TypingStatsText.comboDetail(c), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))) {
                            Box(Modifier.fillMaxWidth((c.chars.toFloat() / max).coerceIn(0.02f, 1f)).fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
                Text("速度按连续输入时间计算，语音不计速度。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { TypingStatsShare.shareText(context, stats, version) }) { Text("分享文字") }
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val uri = runCatching {
                                withContext(Dispatchers.IO) { TypingStatsShare.writeCard(context, stats, version, cardColors) }
                            }.getOrNull()
                            if (uri != null) TypingStatsShare.shareCard(context, uri, stats, version)
                            else TypingStatsShare.shareText(context, stats, version)
                        }
                    }) { Text("分享图片") }
                }
            }
        }
    })
}

/** 七种输入方式的颜色：第一种跟主题色，其余固定色相，深色主题下调亮。 */
@Composable
private fun sourceColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val fixed = listOf(0xFF7E57C2, 0xFF26A69A, 0xFFFFA726, 0xFF42A5F5, 0xFFEC407A, 0xFF8D6E63).map {
        val c = Color(it)
        if (dark) Color.White.copy(alpha = 0.15f).compositeOver(c) else c
    }
    return listOf(MaterialTheme.colorScheme.primary) + fixed
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CorrectionCard(stats: TypingStatsData) {
    val fixes = stats.fixes
    SettingsSection(title = "纠错", content = {
        Column(Modifier.padding(16.dp)) {
            StatRow("自动纠错", "${TypingStatsText.count(stats.total[Day.CORRECTIONS])} 次")
            StatRow(TypingStatsData.Fix.entries.joinToString(" · ") { it.label },
                fixes.joinToString(" · ") { TypingStatsText.count(it) })
            StatRow("删除键占比", stats.deleteRate()?.let(TypingStatsText::percent) ?: "—")
            val wrong = stats.wrongKeyCounts()
            if (wrong.any { it > 0 }) {
                Spacer(Modifier.height(10.dp))
                WrongKeyHeatmap(wrong)
                val pairs = stats.topWrongPairs(3).filter { it.first != it.second }
                if (pairs.isNotEmpty()) Text(
                    "常见误触：" + pairs.joinToString("　") { (typed, intended, _) -> "$intended→$typed" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            } else {
                Text("暂无误触记录。26 键、双拼选用纠错候选后显示。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    })
}

/** 一块小键盘，按错越多的键越红。 */
@Composable
private fun WrongKeyHeatmap(wrong: LongArray) {
    val max = wrong.max().coerceAtLeast(1)
    val red = Color(0xFFE53935)
    val keyBase = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { c ->
                    val v = wrong[c - 'a']
                    val strength = v.toFloat() / max
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (v > 0) red.copy(alpha = 0.15f + 0.7f * strength) else keyBase),
                        contentAlignment = Alignment.Center) {
                        Text(c.toString(), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                            color = if (strength > 0.5f) Color.White else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun FunCard(stats: TypingStatsData) {
    val chars = stats.total[Day.CHARS]
    val essays = chars / TypingStatsText.ESSAY_CHARS
    SettingsSection(title = "累计", content = {
        Column(Modifier.padding(16.dp)) {
            StatRow("按键次数", TypingStatsText.count(stats.total[Day.KEYS]))
            StatRow("输入时长", formatDuration(stats.total[Day.ACTIVE_MS]))
            StatRow("折合 800 字作文", "${TypingStatsText.count(essays)} 篇")
        }
    })
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分钟"
}
