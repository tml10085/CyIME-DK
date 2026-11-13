package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.SideSymbolTarget
import com.kingzcheung.xime.ui.keyboard.SymbolInputMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「按键符号」：每页只有一个和键盘上一样的预览，点哪个键／哪一格就弹小框改它，改完即生效，没有保存按钮。
 * 恢复默认、布局版本收在右上角菜单（11-01 用户：原页面上下重复、说明绕、两种保存方式）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun QwertySymbolEditor(onBack: () -> Unit, initialTarget: String? = null) {
    val context = LocalContext.current
    val inputSettings = rememberKeyboardInputPreferences()
    val reverseSwipe = inputSettings.reverseSymbolSwipe
    val topGesture = if (inputSettings.symbolInputMode == SymbolInputMode.LONG_PRESS) "长按"
        else if (reverseSwipe) "上拉" else "下拉"
    val bottomGesture = if (reverseSwipe) "下拉" else "上拉"
    val scope = rememberCoroutineScope()
    var english by rememberSaveable { mutableStateOf(false) }
    // 「丹麦语29键」页：改的是英文那一套（a–z 与英文26键共用），另加 å æ ø 三个键。为 true 时 english 一定是 true。
    var danish by rememberSaveable { mutableStateOf(false) }
    val danishAvailable = com.kingzcheung.xime.settings.InputLanguage.DANISH in com.kingzcheung.xime.settings.InputLanguage.supported
    // 合并键布局按键编辑：内置 14 键（FourteenKeySymbols）或某个自建双字母布局（CustomLayoutSymbols，存布局 id）；
    // null 是 26 键。不为 null 时 english 一定是 false。
    var merged by rememberSaveable { mutableStateOf<String?>(null) }
    val fourteen = merged == FOURTEEN
    var customLayouts by remember { mutableStateOf<List<CustomKeyboardLayout>>(emptyList()) }
    val customLayout = customLayouts.firstOrNull { it.id == merged }
    // 正在弹框编辑的键；null 表示没有打开编辑框。
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var swipes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var originalOverrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var originalValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // 26 键之外的固定栏（九键、笔画、手写、常用符号、数字行）；null 表示正在编辑字母键。
    var sideTarget by rememberSaveable { mutableStateOf(initialTarget?.takeIf { key ->
        SideSymbolTarget.entries.any { it.key == key } }) }
    var reload by remember { mutableStateOf(0) }
    var versions by remember { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    var menu by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var savingVersion by remember { mutableStateOf(false) }
    var choosingVersion by remember { mutableStateOf(false) }
    // 当前固定栏页登记的「恢复默认」。
    var sideReset by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun back() { if (!busy) onBack() }
    BackHandler { back() }
    LaunchedEffect(english, danish, merged, reload) {
        swipes = if (merged == null) LetterSwipeBindings.load(context, english) else emptyMap()
        loaded = false
        val layouts = withContext(Dispatchers.IO) {
            KeysConfigHelper.loadConfig(context)
            runCatching { CustomKeyboardLayouts.load(context).filter(CustomLayoutSymbols::editable) }.getOrDefault(emptyList())
        }
        customLayouts = layouts
        val custom = layouts.firstOrNull { it.id == merged }
        // 正在改的自建布局已经删掉或拆回单字母：回到中文26键。
        if (merged != null && merged != FOURTEEN && custom == null) { merged = null; return@LaunchedEffect }
        // Read the same effective gestures used by the keyboard, including existing YAML overrides.
        values = withContext(Dispatchers.IO) {
            if (merged == FOURTEEN) KeysConfigHelper.fourteenSymbolEditorValues(context)
            else if (custom != null) KeysConfigHelper.customLayoutSymbolEditorValues(context, custom)
            else KeysConfigHelper.qwertySymbolEditorValues(context, english)
        }
        originalOverrides = if (merged == FOURTEEN) FourteenKeySymbols.load(context)
            else if (custom != null) CustomLayoutSymbols.load(context, custom)
            else QwertySwipeSymbols.load(context, english)
        val boardKeys = if (merged == FOURTEEN) FourteenKeySymbols.keys
            else custom?.let(CustomLayoutSymbols::keys)
            ?: (QwertySwipeSymbols.keys.map(Char::toString) + if (danish) QwertySwipeSymbols.extraKeys else emptyList())
        if (editing !in boardKeys) editing = null
        versions = withContext(Dispatchers.IO) { QwertySwipeSymbols.versions(context, english) }
        originalValues = values
        loaded = true
    }
    fun changeTab(nextEnglish: Boolean, nextSide: String?, nextMerged: String? = null, nextDanish: Boolean = false) {
        if (nextEnglish == english && nextSide == sideTarget && nextMerged == merged && nextDanish == danish) return
        english = nextEnglish; danish = nextDanish; merged = nextMerged; sideTarget = nextSide; message = ""; editing = null
    }
    // 改完一个键（或载入一个版本）立刻写入，键盘上马上生效；只存和原来不同的位置，其余仍跟预设走。
    fun persist(next: Map<String, String>, nextSwipes: Map<String, String>, done: String = "") {
        busy = true
        val snapshot = originalOverrides + next.filter { (key, value) -> originalValues[key] != value }
        val board = fourteen
        val custom = customLayout
        val en = english
        values = next; swipes = nextSwipes
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                if (board) FourteenKeySymbols.save(context, snapshot)
                else if (custom != null) CustomLayoutSymbols.save(context, custom, snapshot)
                else QwertySwipeSymbols.save(context, en, snapshot, nextSwipes)
            } }
                .onSuccess { originalOverrides = snapshot; originalValues = next; message = done }
                .onFailure { message = "保存失败：${it.message}" }
            busy = false
        }
    }
    // 中英文一致：恢复后立即保存。英文清掉修改即预设；中文写入预设符号（覆盖自定义 YAML）。
    fun resetLetters() {
        busy = true
        val board = fourteen
        val custom = customLayout
        val en = english
        // 自建布局没有 YAML 那一层，清掉修改就是预设。
        // 中文 26 键第一排不写死：它跟着「数字行」开关在数字和另一套符号之间换（见 QwertySwipeSymbols.numberRowUp）。
        val firstRow = QwertySwipeSymbols.rows[0].map(Char::toString)
        val preset = if (en || custom != null) emptyMap() else buildMap {
            (if (board) FourteenKeySymbols.up else QwertySwipeSymbols.up.filterKeys { it !in firstRow })
                .forEach { (k, v) -> put("up.$k", v) }
            (if (board) FourteenKeySymbols.down else QwertySwipeSymbols.down.filterKeys { it !in firstRow })
                .forEach { (k, v) -> put("down.$k", v) }
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                if (board) FourteenKeySymbols.save(context, preset)
                else if (custom != null) CustomLayoutSymbols.save(context, custom, preset)
                else QwertySwipeSymbols.save(context, en, preset, emptyMap())
            } }
                .onSuccess { message = "已恢复默认"; reload++ }
                .onFailure { message = "保存失败：${it.message}" }
            busy = false
        }
    }
    val sideTargets = SideSymbolTarget.entries
    val side = sideTargets.firstOrNull { it.key == sideTarget }
    // 布局版本存的是 26 键 52 个位置，中文26键、英文26键各存各的（11-09 用户：英文布局没设计完没法存）。
    // 版本只存 26 键的 52 格，不含 å æ ø：丹麦语页不出版本（a–z 在「英文26键」页存版本）。
    val versionsAvailable = side == null && merged == null && !danish
    val tabName = side?.label ?: customLayout?.name ?: if (fourteen) "中文14键" else if (danish) "丹麦语29键"
        else if (english) "英文26键" else "中文26键"
    KeyboardSettingsScaffold("按键符号", ::back, preview = true, actions = {
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("symbol-menu")) {
                Icon(Icons.Default.MoreVert, "更多")
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("恢复默认") }, enabled = side != null || loaded,
                    onClick = { menu = false; confirmReset = true })
                // 原「按键与手势」里的 Z X C V 开关（11-02 用户：和 Ctrl 的逻辑不搭，收进这里）：一次填好四个键的滑动动作，之后每个键还能单独改。
                if (side == null && merged == null) {
                    val presetOn = LetterSwipeBindings.hasEditPreset(swipes, reverseSwipe)
                    DropdownMenuItem(text = { Text(if (presetOn) "取消 Z X C V 的撤销、剪切、复制、粘贴" else "Z X C V 设为撤销、剪切、复制、粘贴") },
                        enabled = loaded, modifier = Modifier.testTag("symbol-zxcv-preset"), onClick = {
                            menu = false
                            if (presetOn) persist(values, swipes - LetterSwipeBindings.editPresetActions.keys
                                .map { "$it.${LetterSwipeBindings.upperDirection(reverseSwipe)}" }.toSet(), "Z X C V 已换回符号")
                            else persist(values, LetterSwipeBindings.withEditPreset(swipes, reverseSwipe),
                                "Z X C V ${LetterSwipeBindings.directions[LetterSwipeBindings.upperDirection(reverseSwipe)]}改为撤销、剪切、复制、粘贴")
                        })
                }
                if (versionsAvailable) {
                    DropdownMenuItem(text = { Text("存为版本") }, enabled = loaded,
                        onClick = { menu = false; savingVersion = true })
                    DropdownMenuItem(text = { Text("载入版本") }, enabled = loaded && versions.isNotEmpty(),
                        onClick = { menu = false; choosingVersion = true })
                }
            }
        }
    }) { modifier ->
    Column(modifier.settingsVerticalScroll().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val sideIndex = sideTargets.indexOfFirst { it.key == sideTarget }
    val customIndex = customLayouts.indexOfFirst { it.id == merged }
    // 「丹麦语29键」排在「英文26键」后面；精简包没有丹麦语时不出这一页，后面的页序号不加 1。
    val danishTabs = if (danishAvailable) 1 else 0
    PrimaryScrollableTabRow(selectedTabIndex = if (sideIndex >= 0) 3 + danishTabs + customLayouts.size + sideIndex
        else if (customIndex >= 0) 3 + danishTabs + customIndex else if (fourteen) 2 + danishTabs
        else if (danish) 2 else if (english) 1 else 0,
        edgePadding = 0.dp) {
        Tab(selected = sideTarget == null && !english && merged == null, onClick = { changeTab(false, null) }, enabled = !busy,
            text = { Text("中文26键") })
        Tab(selected = sideTarget == null && english && !danish, onClick = { changeTab(true, null) }, enabled = !busy,
            text = { Text("英文26键") })
        if (danishAvailable) Tab(selected = sideTarget == null && danish, onClick = { changeTab(true, null, nextDanish = true) },
            enabled = !busy, text = { Text("丹麦语29键") }, modifier = Modifier.testTag("symbol-target:danish"))
        Tab(selected = sideTarget == null && fourteen, onClick = { changeTab(false, null, FOURTEEN) }, enabled = !busy,
            text = { Text("中文14键") }, modifier = Modifier.testTag("symbol-target:k14"))
        // 自建的双字母布局各有一页，标签用布局自己的名字；没有合并键的自建布局按 26 键位置，在「中文26键」里改。
        customLayouts.forEach { layout ->
            Tab(selected = sideTarget == null && merged == layout.id, onClick = { changeTab(false, null, layout.id) }, enabled = !busy,
                text = { Text(layout.name) }, modifier = Modifier.testTag("symbol-target:${layout.id}"))
        }
        sideTargets.forEach { target ->
            Tab(selected = sideTarget == target.key, onClick = { changeTab(english, target.key, merged, danish) }, enabled = !busy,
                text = { Text(target.label) }, modifier = Modifier.testTag("symbol-target:${target.key}"))
        }
    }
    if (side != null) SideSymbolEditor(side, onResetReady = { sideReset = it })
    else {
        Text("点按键修改，${topGesture}输入上方，${bottomGesture}输入下方", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (danish) Text("A–Z 和「英文26键」共用，这里改了英文键盘也会变；Å Æ Ø 只在丹麦语键盘上。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val boardRows = if (fourteen) FourteenKeySymbols.rows
            else customLayout?.let(CustomLayoutSymbols::rows)
            ?: (if (danish) QwertySwipeSymbols.danishRows else QwertySwipeSymbols.rows).map { row -> row.map(Char::toString) }
        // 自建布局和键盘上一样按字母数分键宽（合并键两格宽），每排居中；26 键、14 键每键一格。
        // 打开「合并键均匀排布」时同键盘一样，合并键只比单字母键宽三分之一。
        fun width(key: String): Double = com.kingzcheung.xime.ui.keyboard.letterKeySpan(key, customLayout != null,
            inputSettings.evenMergedKeys).toDouble()
        val rowSlots = boardRows.maxOf { row -> row.sumOf(::width) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            boardRows.forEach { row ->
                val units = row.sumOf(::width)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (units < rowSlots - 1e-3) Spacer(Modifier.weight(((rowSlots - units) / 2).toFloat()))
                    row.forEach { key ->
                        // 设了上下滑动作的方向，预览和键盘一样写动作名（强调色）。
                        val (boundUp, boundDown) = if (merged == null) LetterSwipeBindings.cornerLabels(swipes, key, reverseSwipe)
                            else null to null
                        Surface(modifier = Modifier.weight(width(key).toFloat()).height(64.dp).testTag("symbol-editor-key:$key")
                            .clickable(enabled = loaded && !busy) { editing = key; message = "" },
                            shape = RoundedCornerShape(8.dp),
                            color = if (editing == key) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceEvenly) {
                                Text(boundUp ?: values["up.$key"].orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    color = if (boundUp != null) MaterialTheme.colorScheme.primary else Color.Unspecified)
                                Text(key.uppercase(), style = MaterialTheme.typography.titleMedium)
                                Text(boundDown ?: values["down.$key"].orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    color = if (boundDown != null) MaterialTheme.colorScheme.primary else Color.Unspecified)
                            }
                        }
                    }
                    if (units < rowSlots - 1e-3) Spacer(Modifier.weight(((rowSlots - units) / 2).toFloat()))
                }
            }
        }
    }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    }
    editing?.takeIf { side == null && loaded }?.let { key ->
        KeySymbolDialog(key, values["up.$key"].orEmpty(), values["down.$key"].orEmpty(),
            // å æ ø 没有滑动动作（键盘上只给 26 个字母键绑动作），只改符号。
            topGesture, bottomGesture, swipes.takeIf { merged == null && key !in QwertySwipeSymbols.extraKeys },
            // 长按输入时上方符号靠长按，滑动动作占的是空着的那个方向，不挡符号。
            upperSlot = LetterSwipeBindings.upperDirection(reverseSwipe)
                .takeUnless { inputSettings.symbolInputMode == SymbolInputMode.LONG_PRESS },
            lowerSlot = LetterSwipeBindings.lowerDirection(reverseSwipe),
            onDismiss = { editing = null },
            onDone = { up, down, nextSwipes ->
                editing = null
                val next = values + ("up.$key" to up) + ("down.$key" to down)
                if (next != values || (nextSwipes != null && nextSwipes != swipes)) persist(next, nextSwipes ?: swipes)
            })
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text(if (danish) "「丹麦语29键」和「英文26键」一起恢复默认？" else "「$tabName」恢复默认？") },
        confirmButton = { TextButton(onClick = {
            confirmReset = false
            if (side != null) sideReset?.invoke() else resetLetters()
        }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
    if (savingVersion) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { savingVersion = false }, title = { Text("存为版本") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") },
                singleLine = true, modifier = Modifier.testTag("symbol-version-name")) },
            confirmButton = { TextButton(enabled = QwertySwipeSymbols.versionName(name).isNotEmpty(), onClick = {
                val clean = QwertySwipeSymbols.versionName(name)
                savingVersion = false
                runCatching { QwertySwipeSymbols.saveVersion(context, clean, values, english) }
                    .onSuccess { versions = QwertySwipeSymbols.versions(context, english); message = "已存为「$clean」" }
                    .onFailure { message = "保存失败：${it.message}" }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { savingVersion = false }) { Text("取消") } })
    }
    if (choosingVersion) AlertDialog(onDismissRequest = { choosingVersion = false }, title = { Text("载入版本") },
        text = {
            Column {
                versions.keys.forEach { name ->
                    Row(Modifier.fillMaxWidth().testTag("symbol-version:$name"), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f).clickable {
                            choosingVersion = false
                            persist(versions.getValue(name), swipes, "已载入「$name」")
                        }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            runCatching { QwertySwipeSymbols.deleteVersion(context, name, english) }
                                .onSuccess { versions = QwertySwipeSymbols.versions(context, english)
                                    if (versions.isEmpty()) choosingVersion = false }
                                .onFailure { message = "删除失败：${it.message}" }
                        }) { Icon(Icons.Default.Delete, "删除「$name」") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingVersion = false }) { Text("关闭") } })
}

/** [merged] 里表示内置中文 14 键；其他值是自建布局 id。 */
private val FOURTEEN = InputLayout.MERGED14.id

/**
 * 点一个键弹出的小框：上方、下方两个符号，26 键另有滑动动作。点「完成」才写入，「取消」不改。
 * [swipes] 为 null 时（14 键、自建布局）不显示滑动动作。
 */
@Composable
private fun KeySymbolDialog(key: String, up: String, down: String, topGesture: String, bottomGesture: String,
    swipes: Map<String, String>?, upperSlot: String?, lowerSlot: String,
    onDismiss: () -> Unit, onDone: (String, String, Map<String, String>?) -> Unit) {
    var upText by rememberSaveable(key) { mutableStateOf(up) }
    var downText by rememberSaveable(key) { mutableStateOf(down) }
    var swipeDraft by remember(key) { mutableStateOf(swipes) }
    // 这个方向设了滑动动作时，对应的符号暂时用不上：写在框下，免得以为改了符号没反应。
    fun shadowedBy(slot: String?): String? = slot?.let { swipeDraft?.get("${key.lowercase()}.$it") }
        ?.let { "现在是「${LetterSwipeBindings.label(it)}」，清掉下面的滑动动作才输入符号" }
    val upNote = shadowedBy(upperSlot)
    val downNote = shadowedBy(lowerSlot)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("symbol-editor-dialog"),
        title = { Text(key.uppercase()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = upText, onValueChange = { upText = it }, singleLine = true,
                    label = { Text("上方（$topGesture）") }, placeholder = { Text("留空不放符号") },
                    supportingText = upNote?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("symbol-editor-up"))
                OutlinedTextField(value = downText, onValueChange = { downText = it }, singleLine = true,
                    label = { Text("下方（$bottomGesture）") }, placeholder = { Text("留空不放符号") },
                    supportingText = downNote?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("symbol-editor-down"))
                swipeDraft?.let { current ->
                    LetterSwipeEditor(key, current) { slot, binding ->
                        swipeDraft = if (binding.isEmpty()) current - slot else current + (slot to binding)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(upText, downText, swipeDraft) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun LetterSwipeEditor(key: String, swipes: Map<String, String>, change: (String, String) -> Unit) {
    var direction by rememberSaveable { mutableStateOf("left") }
    var chooseDirection by remember { mutableStateOf(false) }
    var chooseAction by remember { mutableStateOf(false) }
    val slot = "$key.$direction"
    val binding = swipes[slot].orEmpty()
    Text("滑动动作", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { chooseDirection = true }) {
                Text(LetterSwipeBindings.directions.getValue(direction))
            }
            DropdownMenu(chooseDirection, onDismissRequest = { chooseDirection = false }) {
                LetterSwipeBindings.directions.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { direction = id; chooseDirection = false })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { chooseAction = true }) {
                Text(LetterSwipeBindings.actions[binding] ?: if (binding.startsWith("text:")) "输入文字" else "沿用原动作")
            }
            DropdownMenu(chooseAction, onDismissRequest = { chooseAction = false }) {
                (mapOf("" to "沿用原动作", "text:" to "输入文字") + LetterSwipeBindings.actions).forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { change(slot, id); chooseAction = false })
                }
            }
        }
    }
    if (binding.startsWith("text:")) OutlinedTextField(value = binding.removePrefix("text:"),
        onValueChange = { change(slot, "text:$it") }, label = { Text("滑动输入的文字") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
}
