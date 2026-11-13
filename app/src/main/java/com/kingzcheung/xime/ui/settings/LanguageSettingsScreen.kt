package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.twotone.ViewModule
import androidx.compose.material.icons.twotone.Inventory
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material.icons.twotone.Abc
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kingzcheung.xime.settings.*
import com.kingzcheung.xime.ui.menubar.rememberDragOrder
import com.kingzcheung.xime.ui.menubar.dragOrderItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.kingzcheung.xime.ui.theme.MaterialLevel
import com.kingzcheung.xime.ui.theme.VisualStyles
import com.kingzcheung.xime.ui.theme.visualMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsContent(onOpenSchemaResources: () -> Unit = {}, onOpenDoublePinyin: () -> Unit = {},
    onOpenChineseInput: () -> Unit = {}, onOpenEnglishInput: () -> Unit = {}, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var languages by remember { mutableStateOf(LanguagePreferences.enabled(context)) }
    var orderedLanguages by remember { mutableStateOf(InputModes.languageOrder(context)) }
    var saving by remember { mutableStateOf(false) }
    var expandedLanguage by remember { mutableStateOf<InputLanguage?>(orderedLanguages.firstOrNull()) }
    // 没开的西、德、法、意、俄、韩语默认收在「更多语言」里（用户 11-08：一般人用不着这么多）。
    var moreLanguagesOpen by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val profilesModel: com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val profilesState by profilesModel.uiState.collectAsState()
    var remembered by remember { mutableStateOf(InputModes.rememberedModes(context)) }
    val availableProfiles = InputModes.ordered(context, profilesState.allSchemas
        .filter { it.schemaId in profilesState.enabledSchemas }.map { it.toSchemaInfo() })
    // 一步选方案（用户 11-01 批准）：关着的内置方案（全拼 14 键、内置双拼）和它们的组合（如小鹤 14 键）也列在
    // 布局、方案里，点了自动启用并部署，不用先去「已安装的方案」打开再回来。
    val installedIds = profilesState.allSchemas.map { it.schemaId }.toSet()
    val offeredIds = CyimeInputDefaults.withCompanions(
        (profilesState.enabledSchemas + CyimeInputDefaults.listedWhenOff).filter { it in installedIds }.distinct(), installedIds).toSet()
    val pickableProfiles = InputModes.ordered(context, profilesState.allSchemas
        .filter { it.schemaId in offeredIds || it.schemaId in profilesState.enabledSchemas }.map { it.toSchemaInfo() })
    val dragOrder = rememberDragOrder(orderedLanguages.map { it.id }) { ids ->
        InputModes.saveLanguageOrder(context, ids)
        orderedLanguages = InputModes.languageOrder(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        languages = LanguagePreferences.enabled(context)
        orderedLanguages = InputModes.languageOrder(context)
        profilesModel.refresh()
        remembered = InputModes.rememberedModes(context)
    }
    KeyboardSettingsScaffold("语言与方案", onBack, preview = true, modifier = Modifier.testTag("language-settings")) { modifier ->
        SettingsSearchAutoScroll(dragOrder.list)
        LazyColumn(modifier = modifier.settingsGutterScroll(dragOrder.list).testTag("language-settings-list")
            .padding(horizontal = 16.dp, vertical = 8.dp),
            state = dragOrder.list, verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = keyboardPreviewContentPadding()) {
            item { Text("点开语言，分别设置输入方案和键盘布局；下次切换到该语言时使用。长按可调整语言顺序。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // 拖动顺序要等下一帧才跟上开关：刚关掉的语言还在 dragOrder.order 里，又出现在未启用那段，
            // 同一个 key 出现两次，LazyColumn 直接崩溃、设置页重开（11-02 关西班牙语）。这里只认当前真启用的。
            val rows = languageRows(dragOrder.order + orderedLanguages.map { it.id }, languages)
            items(languageListRows(rows, languages, moreLanguagesOpen), key = { it }) { id ->
                if (id == MORE_LANGUAGES_ROW) {
                    MoreLanguagesRow(foldedLanguages(rows, languages), moreLanguagesOpen) { moreLanguagesOpen = !moreLanguagesOpen }
                    return@items
                }
                val language = InputLanguage.entries.first { it.id == id }
                val checked = language in languages
                val canChange = language != InputLanguage.ENGLISH && !saving
                val selected = InputProfileSelection.preferred(availableProfiles, language,
                    remembered[language], profilesState.currentSchema)
                val expanded = checked && expandedLanguage == language
                Surface(modifier = Modifier.fillMaxWidth().testTag("language-drag:${language.id}")
                    .then(dragOrderItem(dragOrder, id, checked && orderedLanguages.size > 1 && !saving)),
                    shape = RoundedCornerShape(12.dp),
                    border = if (dragOrder.dragging == id) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    color = if (dragOrder.dragging == id) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().testTag("language-${language.id}")
                        .visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                            .clickable(enabled = checked) { expandedLanguage = if (expanded) null else language }
                            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(language.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(if (!checked) "未启用" else selected?.selectionLabel ?: "正在准备可用组合",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (checked) IconButton(onClick = { expandedLanguage = if (expanded) null else language },
                                modifier = Modifier.testTag("language-expand:${language.id}")) {
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    if (expanded) "收起${language.displayName}设置" else "展开${language.displayName}设置")
                            }
                            Switch(checked, enabled = canChange, colors = settingsSwitchColors(), modifier = Modifier.testTag("language-toggle:${language.id}"),
                                onCheckedChange = { enabled ->
                                scope.launch {
                                    saving = true
                                    try {
                                        withContext(Dispatchers.IO) { LanguagePreferences.save(context, language, enabled) }
                                        languages = LanguagePreferences.enabled(context)
                                        orderedLanguages = InputModes.languageOrder(context)
                                        profilesModel.refresh()
                                        if (enabled) expandedLanguage = language
                                        message = if (enabled) "已开启，输入方案会在后台自动准备" else "已关闭，已下载词库保留"
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (error: Exception) { message = error.message ?: "保存失败，请稍后再试" }
                                    finally { saving = false }
                                }
                            })
                        }
                        // 日语是插件：轻量版的词库和转换引擎是下载的，没下好时在这里说明、给下载按钮。
                        if (checked && language == InputLanguage.JAPANESE) JapanesePackStatus(
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
                        if (expanded && selected != null) InputProfileSelectors(pickableProfiles, selected,
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) { entry ->
                            InputModes.selectProfile(context, entry)
                            remembered = remembered + (language to entry.schemaId)
                            // 正在用的语言：同时改当前方案，键盘下次弹出就换（收起时在这里改了布局也算数）。
                            val current = SettingsPreferences.getCurrentSchema(context)
                            val active = current.isNotBlank() && !com.kingzcheung.xime.ui.keyboard.isHandwritingSchema(current) &&
                                InputModes.languageOf(current, availableProfiles) == language
                            if (entry.schemaId == InputModes.ENGLISH) {
                                // 英文直接输入不是 Rime 方案，不启用、不部署、不写成当前方案；切到英文时按这里的选择开 ascii。
                                message = "已保存：${language.displayName} · ${entry.selectionLabel}，下次切换到该语言时使用"
                            } else if (entry.schemaId in profilesState.enabledSchemas) {
                                if (active) SettingsPreferences.setCurrentSchema(context, entry.schemaId, language)
                                message = "已保存：${language.displayName} · ${entry.selectionLabel}，" +
                                    if (active) "键盘下次弹出时使用" else "下次切换到该语言时使用"
                            } else if (!saving) scope.launch {
                                saving = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        // 组合（如小鹤 14 键）启用它需要的双拼和 14 键，组合本身由 setEnabledSchemas 补上。
                                        val needs = CyimeInputDefaults.companionNeeds(entry.schemaId) ?: listOf(entry.schemaId)
                                        SchemaManager.setEnabledSchemas(context, (SchemaManager.getEnabledSchemas(context) + needs).distinct())
                                        LanguagePreferences.enableForSchema(context, entry.schemaId)
                                    }
                                    profilesModel.refresh()
                                    // 关着的方案（笔画、仓颉等）点了也要像开着的一样换上：以前只启用不换，键盘还是拼音，
                                    // 以为点的是笔画、其实在拼音键盘上打（11-06）。部署完再换，没编译好的方案键盘会退回第一个方案。
                                    profilesModel.deploySchema {
                                        if (active) SettingsPreferences.setCurrentSchema(context, entry.schemaId, language)
                                    }
                                    message = "已打开并选好：${language.displayName} · ${entry.selectionLabel}，正在部署，几秒后好；" +
                                        if (active) "键盘下次弹出时使用" else "下次切换到该语言时使用"
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (error: Exception) { message = error.message ?: "打开方案失败，请稍后再试" }
                                finally { saving = false }
                            }
                        }
                        // 西班牙、德、法、俄语的补全词表在商店里，开启时自动下载；没下好时在这里说一声。
                        if (checked) LetterLanguage.of(language)?.let {
                            WordListStatus(it, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
                        }
                    }
                }
            }
            // 没写语言的导入方案（声笔等，GitHub #86）：在这里选一次语言，字母布局、语音、手写跟着走。
            val unlabeled = profilesState.allSchemas.filter { it.languageAssignable }
            if (unlabeled.isNotEmpty()) item(key = "imported-schema-language") {
                var picking by remember { mutableStateOf<SchemaMeta?>(null) }
                SettingsSection(title = "导入方案的语言") {
                    unlabeled.forEachIndexed { index, schema ->
                        if (index > 0) HorizontalDivider()
                        SettingsItem(Icons.TwoTone.Translate, schema.name, "方案没写语言，点开选一次",
                            onClick = { picking = schema }, showArrow = true,
                            status = if (schema.languageChosen || schema.language == InputLanguage.UNSPECIFIED) schema.language.displayName
                                else "${schema.language.displayName}(自动)",
                            searchKey = "导入方案的语言:${schema.schemaId}")
                    }
                }
                picking?.let { target ->
                    SchemaLanguageDialog(target, onChosen = { language ->
                        picking = null
                        profilesModel.setSchemaLanguage(target.schemaId, language) {
                            languages = LanguagePreferences.enabled(context)
                            orderedLanguages = InputModes.languageOrder(context)
                            message = if (language == null) "已改回未标注语言" else "已设为${language.displayName}，切到这个方案时生效"
                        }
                    }, onDismiss = { picking = null })
                }
            }
            // 已装的方案组合（启停、排序、应用更改）和导入，原首页「输入资源」。
            item(key = "schema-resources") {
                SettingsSection(title = "方案资源") {
                    SettingsItem(Icons.TwoTone.Inventory, "已安装的方案",
                        "启用、停用和排序语言、方案与布局的组合，导入方案文件", onClick = onOpenSchemaResources, showArrow = true)
                    SettingsItem(Icons.TwoTone.ViewModule, "双拼方案",
                        "小鹤、自然码、微软等 6 种内置双拼的键位，新建和修改自己的双拼方案", onClick = onOpenDoublePinyin, showArrow = true)
                }
            }
            item(key = "language-key-switch") {
                LanguageKeySwitchSettings(availableProfiles, orderedLanguages, profilesState.currentSchema)
            }
            // 中文输出、拼音纠错、模糊音和英文输入 11-02 各收进一页（原样搬过去，名字和存的值不变），这页只留入口。
            item(key = "input-settings") {
                SettingsSection(title = "输入设置") {
                    SettingsItem(Icons.TwoTone.Translate, "中文输入", "繁体、生僻字、拼音纠错、模糊音",
                        onClick = onOpenChineseInput, showArrow = true)
                    SettingsItem(Icons.TwoTone.Abc, "英文输入", "自动切英文、自动加空格、邮箱后缀",
                        onClick = onOpenEnglishInput, showArrow = true)
                }
            }
            item { if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

internal const val MORE_LANGUAGES_ROW = "more-languages"

/** 收进「更多语言」的：没开启的字母类语言（西、德、法、意、俄、韩语；丹麦语像日语一样直接列在顶层）。 */
internal fun foldedLanguages(rows: List<String>, enabled: Set<InputLanguage>): List<String> =
    rows.filter { id -> id != InputLanguage.DANISH.id && enabled.none { it.id == id } && InputLanguage.fromId(id)?.let { LetterLanguage.of(it) } != null }

/** 实际列出的行：收起时只到「更多语言」那一行，展开时它下面接着列这些语言。 */
internal fun languageListRows(rows: List<String>, enabled: Set<InputLanguage>, moreOpen: Boolean): List<String> {
    val folded = foldedLanguages(rows, enabled)
    if (folded.isEmpty()) return rows
    return rows.filterNot { it in folded } + MORE_LANGUAGES_ROW + (if (moreOpen) folded else emptyList())
}

@Composable
private fun MoreLanguagesRow(folded: List<String>, open: Boolean, onToggle: () -> Unit) {
    val names = folded.mapNotNull { InputLanguage.fromId(it)?.displayName }.joinToString("、")
    Surface(modifier = Modifier.fillMaxWidth().testTag("language-more"), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().visualMaterial(VisualStyles.current, 12.dp, level = MaterialLevel.RAISED)
            .heightIn(min = 72.dp).clickable(onClick = onToggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("更多语言", style = MaterialTheme.typography.titleMedium)
                Text(names, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "收起更多语言" else "展开更多语言")
        }
    }
}

/** 语言卡片的顺序：启用的按拖动顺序，后面是没启用的；每种语言只出现一次（LazyColumn 的 key 不能重复）。 */
internal fun languageRows(enabledOrder: List<String>, enabled: Set<InputLanguage>): List<String> {
    val head = enabledOrder.distinct().filter { id -> enabled.any { it.id == id } }
    return head + InputLanguage.supported.map { it.id }.filterNot { it in head }
}
