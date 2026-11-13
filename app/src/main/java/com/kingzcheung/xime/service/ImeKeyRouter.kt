package com.kingzcheung.xime.service

import android.util.Log
import android.view.KeyEvent
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asContextElement

/**
 * 按键输入路由。
 *
 * 承载按键派发（handleKeyPress）、长按退格合并、候选选择/翻页、计算器候选等逻辑。
 * 所有共享状态通过 service 引用访问（同模块 internal 成员）。
 */
/** 26-key schemas whose speller alphabet has A–Z, so a capital joins the composition (rime-ice family):
 *  rime_ice and the layouts made from its schema (QWJRTK, Cyletix10 and the user's own custom_pinyin_*). */
private fun isRimeIceCapitalSchema(id: String) =
    id == "rime_ice" || com.kingzcheung.xime.settings.CustomKeyboardLayouts.isCustom(id)

/**
 * 大写字母进 Rime 组字、回车上屏的英文串记进 cyime_en_memory.txt、长按删除能忘掉记住的词：
 * 雾凇系 26 键，以及英文单词方案（打 Hello 出 Hello）。雾凇的 Unicode 码（U2603）只在雾凇系，不在这里。
 */
private fun typesEnglishCapitals(id: String) =
    isRimeIceCapitalSchema(id) || id == com.kingzcheung.xime.settings.InputModes.ENGLISH_WORDS

/** 雾凇的 Unicode 码（recognizer `^U[a-f0-9]+`，U2603 → ☃）：大写 U 起头，后面只有数字和小写 a–f。 */
internal fun isUnicodeCodeInput(input: String) =
    input.firstOrNull() == 'U' && input.drop(1).all { it in '0'..'9' || it in 'a'..'f' }

/** 打了数字的 Unicode 码（U2、U26a）：只打字母的 Uber、Ubad 不算，照旧是英文。 */
internal fun isUnicodeCodeWithDigit(input: String) = isUnicodeCodeInput(input) && input.any { it in '0'..'9' }

internal class ImeKeyRouter(private val service: XimeInputMethodService) {
    // Tag only the queued keys from one glide; ordinary typing still publishes every result.
    private var submittingGlide = false
    private val glideBatch = ThreadLocal<Boolean>()
    private val chineseGlideDeletes = ChineseGlideDeleteHistory()

    private fun chineseGlideDeleteEnabled(): Boolean = SettingsPreferences.getPrefsPublic(service).getBoolean(
        com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.DELETE_CHINESE_GLIDE_CHARACTER_KEY, false)

    private fun chineseGlideState(): ChineseGlideDeleteHistory.State? {
        val snapshot = service.rimeEngine.readPinyinEditSnapshot()
        if (snapshot.size < 4) return null
        val caret = snapshot[1].toIntOrNull() ?: return null
        val confirmed = snapshot[2].toIntOrNull() ?: return null
        val ui = service.uiState.value
        return ChineseGlideDeleteHistory.State(snapshot[0], caret, confirmed, snapshot[3],
            ui.currentSchemaId, ui.inputSessionId, ui.inputTarget, service.rimeEngine.currentCandidateRevision())
    }

    private fun chineseCodeSyllables(code: String, result: com.kingzcheung.xime.rime.RimeProcessResult?,
        allowIncomplete: Boolean = false): List<String>? {
        val isDouble = service.uiState.value.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN
        return runCatching {
            com.kingzcheung.xime.glide.chineseCodeSyllables(code, isDouble,
                listOf(result?.candidates?.firstOrNull()?.comment.orEmpty(), result?.preeditText.orEmpty()),
                if (isDouble) emptyList() else com.kingzcheung.xime.glide.GlideLexicon.pinyin(service), allowIncomplete)
        }.getOrNull()
    }
    @Volatile internal var letterNeighbors: Pair<String, String> = "" to ""
    private val hardwareNavigation = HardwareCandidateNavigation()
    private val hardwareSelectionRevision = androidx.compose.runtime.mutableIntStateOf(0)
    internal val usesDirectFieldInput: Boolean get() = service.editorInputPolicy.directInput &&
        !service.uiState.value.inputTarget.isInternal

    /**
     * 没在打字时的回车、Shift+回车、Ctrl+回车，所有入口同一规则（[EnterKeyAction]）：键面写什么就做什么，
     * Shift+回车换行，Ctrl+回车发送。换行直接上屏 "\n"，不发模拟回车键，聊天软件不会把它当实体键盘的回车发出去（用户 11-03）。
     */
    internal fun performIdleEnter(connection: android.view.inputmethod.InputConnection? = service.currentInputConnection,
        ctrl: Boolean = false, shift: Boolean = false) {
        val ic = connection ?: return
        val info = service.currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val type = info?.inputType ?: 0
        val terminal = service.isTerminalEditor()
        val plan = when {
            ctrl -> EnterKeyAction.ctrlEnter(options, type, terminal)
            shift -> EnterKeyAction.shiftEnter(options, type, terminal)
            else -> EnterKeyAction.enter(options, type, terminal, appSend = SettingsPreferences.isEnterSendsEnabled(service))
        }
        // Ctrl+回车在没声明动作的框里执行「发送」；有的应用不理这个动作，见 [sendWithFallback]。
        if (ctrl && !terminal && plan == EnterKeyAction.EditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND))
            return sendWithFallback(ic, info?.packageName)
        when (plan) {
            is EnterKeyAction.EditorAction -> ic.performEditorAction(plan.action)
            EnterKeyAction.Newline -> ic.commitText("\n", 1)
            EnterKeyAction.EnterKey -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            EnterKeyAction.CtrlEnterKey -> com.kingzcheung.xime.keyboard.CtrlShortcuts.enterKeyEvents(
                android.os.SystemClock.uptimeMillis()).forEach(ic::sendKeyEvent)
            EnterKeyAction.ShiftEnterKey -> com.kingzcheung.xime.keyboard.CtrlShortcuts.shiftEnterKeyEvents(
                android.os.SystemClock.uptimeMillis()).forEach(ic::sendKeyEvent)
        }
    }

    /**
     * Ctrl+回车发送：先执行「发送」动作（QQ、TIM、微信认这个，11-04 实测）。过一会儿输入框没变，说明应用没接这个动作
     * （用户 11-05：Claude 里 Ctrl+回车发不出去；Compose 写的输入框没设 onSend 时「发送」动作什么都不做），
     * 就补发一次回车键：这类应用一般把实体键盘的回车当发送。补发后输入框只是多了一个换行（应用不拿回车发送），
     * 把这个换行删掉，Ctrl+回车不在里面留东西。空框不补发；输入框换了、内容被应用改了都当已经发出。
     */
    private fun sendWithFallback(ic: android.view.inputmethod.InputConnection, pkg: String?) {
        val before = CtrlEnterSend.snapshot(ic)
        ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
        if (before == null || before.isEmpty) return
        service.mainHandler.postDelayed({
            if (service.currentInputConnection !== ic) return@postDelayed
            if (CtrlEnterSend.outcome(before, CtrlEnterSend.snapshot(ic)) != CtrlEnterSend.Outcome.UNCHANGED) return@postDelayed
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            service.mainHandler.postDelayed({
                if (service.currentInputConnection !== ic) return@postDelayed
                when (CtrlEnterSend.outcome(before, CtrlEnterSend.snapshot(ic))) {
                    CtrlEnterSend.Outcome.NEWLINE_ADDED -> {
                        ic.deleteSurroundingText(1, 0)
                        FileLogger.w(XimeInputMethodService.TAG, "Ctrl+Enter: $pkg takes neither send action nor Enter key")
                    }
                    CtrlEnterSend.Outcome.UNCHANGED ->
                        FileLogger.w(XimeInputMethodService.TAG, "Ctrl+Enter: $pkg ignored send action and Enter key")
                    else -> Unit
                }
            }, CtrlEnterSend.SETTLE_MS)
        }, CtrlEnterSend.SETTLE_MS)
    }

    /** Literal field input bypasses the decoder, calculator, learning and candidate snapshots. */
    private suspend fun routeDirectFieldKey(key: String, shifted: Boolean, event: KeyEvent?): Boolean {
        val policy = service.editorInputPolicy
        when {
            key == "ime_switch" -> return policy.locksNativeInput
            key in setOf("shift", "mode_change", "abc", "number", "common_symbol") -> return false
            key.startsWith("hardware_candidate_") -> {
                if (event != null && isHardwareArrow(event.keyCode)) withEditor {
                    service.schemaController.moveHardwareCursor(event)
                }
            }
            key == "\t" && event != null -> withEditor {
                service.currentInputConnection?.let { connection ->
                    connection.sendKeyEvent(event)
                    connection.sendKeyEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
                }
            }
            key == "enter" -> withEditor { performIdleEnter() }
            key == "clear_all" -> withEditor {
                // Never read or retain a password in the clear/undo buffer.
                service.lastClearedText = ""
                service.lastClearedDocument = null
                service.lastSwipeDelete = null
                service.currentInputConnection?.let { connection ->
                    if (com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(service).getBoolean(
                            com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CLEAR_BEFORE_CURSOR_ONLY_KEY, false)) {
                        // 「只清空光标前的内容」：不读密码，删掉选中的和光标前的，光标后的留着。
                        connection.beginBatchEdit()
                        try {
                            connection.commitText("", 1)
                            connection.deleteSurroundingText(XimeInputMethodService.SAFE_TEXT_LIMIT, 0)
                        } finally {
                            connection.endBatchEdit()
                        }
                    } else if (connection.performContextMenuAction(android.R.id.selectAll)) connection.commitText("", 1)
                }
            }
            key == "undo_clear" || key == "clear_composition" || key == "emoji" -> Unit
            else -> {
                val literal = when (key) {
                    "space" -> " "
                    "word_separator" -> "'"
                    else -> if (key.length == 1) { if (shifted) key.uppercase() else key } else ""
                }
                commitDirectFieldLiteral(literal)
                // 上屏时已清过候选栏；邮箱框接着可能给了邮箱后缀，别再清掉。
                if (literal.isNotEmpty()) return true
            }
        }
        withEditor {
            service.predictionManager.invalidatePendingPredictions()
            service.candidateState.value = CandidateState()
        }
        return true
    }

    /** Not a paste API: only a keyboard key or explicit keyboard-symbol gesture uses it. */
    internal suspend fun commitDirectFieldLiteral(text: String): Boolean = withEditor {
        if (!usesDirectFieldInput) return@withEditor false
        val literal = service.editorInputPolicy.field.typedLiteral(text)
        service.predictionManager.invalidatePendingPredictions()
        service.candidateState.value = CandidateState()
        if (literal.isEmpty()) return@withEditor true
        if (service.editorInputPolicy.field.kind in keyEventFieldKinds) {
            // PIN views in secure apps (e.g. Samsung Secure Folder, bank SDKs) ignore commitText and
            // only handle key events. sendKeyChar sends digits as KEYCODE_0..9, as stock IMEs do,
            // and still commits any other character as text.
            if (service.currentInputConnection == null) return@withEditor false
            // 数字框的小数点也按键发（KEYCODE_PERIOD）：只认按键的价格框收不到 commitText 的「.」（#92）。
            val numberField = service.editorInputPolicy.field.kind == EditorFieldPolicy.Kind.NUMBER
            literal.forEach { c ->
                if (numberField && c == '.') service.sendDownUpKeyEvents(KeyEvent.KEYCODE_PERIOD)
                else service.sendKeyChar(c)
            }
            return@withEditor true
        }
        val committed = service.currentInputConnection?.commitText(literal, 1) == true
        // 邮箱框里打 @ 和后面的字：给对得上的邮箱后缀（默认开）。
        if (committed && service.editorInputPolicy.field.kind == EditorFieldPolicy.Kind.ADDRESS) offerEmailSuffixes()
        committed
    }

    /**
     * 打 @ 时给邮箱后缀（默认开，见 [EmailSuffixes]）：光标前是「邮箱名@…」就把对得上的后缀放到候选栏；
     * 接着打的字对不上了就收起这一组。开关关着时不读输入框；密码框、键盘里的面板输入框、打着拼音时不给。主线程调用。
     */
    private fun offerEmailSuffixes() {
        if (!SettingsPreferences.getPrefsPublic(service).getBoolean(EmailSuffixes.PREF_KEY, true)) return
        if (service.uiState.value.inputTarget.isInternal || service.isSecretEditor()) return
        // 接着实体键盘时数字键、空格会选候选栏上的词：打 126.com 的 1 会选走 qq.com，不给。
        if (service.hasHardwareKeyboard) return
        val state = service.candidateState.value
        if (state.isComposing || state.inputText.isNotEmpty() || service.t9PartialSegments.isNotEmpty()) return
        val ic = service.currentInputConnection ?: return
        val before = runCatching { ic.getTextBeforeCursor(EmailSuffixes.CONTEXT_LENGTH, 0)?.toString() }.getOrNull() ?: return
        val domains = EmailSuffixes.suggestions(before)
        if (domains.isEmpty()) {
            if (EmailSuffixes.isSuggestionList(state.associationCandidates))
                service.candidateState.value = state.copy(associationCandidates = emptyList())
            return
        }
        service.predictionManager.invalidatePendingPredictions()
        // 联想上下文对上光标前的文字：光标更新时才不会当成上下文变了，把后缀清掉。
        service.predictionManager.replaceCommittedText(before)
        service.candidateState.value = state.copy(associationCandidates = domains, pendingEnglishText = "",
            isShowingRecentClipboard = false)
    }

    /** 屏幕键盘、角标、长按和符号面板打的 @ 上屏之后，或给着后缀时接着打的数字、点（不在邮箱框）：重新给邮箱后缀。 */
    internal suspend fun offerEmailSuffixesAfterAt() = withEditor { offerEmailSuffixes() }

    /**
     * 数字上屏之后（数字键盘、数字行、角标）：光标前那串数字对得上「我的短语」的编码开头时把短语放到候选栏（默认关，见 [DigitPhrases]），
     * 打过两次以上的常用数字也放进这一组（默认开，见 [NumberHistory]）。
     * 两个开关都关着时不读短语表也不读输入框。短语表、记下的数字在按键队列里读（只读一次），输入框在主线程读。
     */
    internal suspend fun offerDigitPhrases() {
        val numbers = if (NumberHistory.isEnabled(service)) NumberHistory.load(service) else null
        val phrases = if (DigitPhrases.isEnabled(service)) DigitPhrases.phrases(service) else emptyList()
        if (phrases.isEmpty() && numbers == null) return
        val finished = withEditor { showDigitPhrases(phrases, numbers) }
        // 上一串打完了的数字：记一次（文件在后台写）。
        finished?.let { NumberHistory.record(service, it) }
    }

    /** 跟着光标前正在打的那串数字，打完才记（见 [NumberHistory]）。主线程用。 */
    private val numberRun = NumberRunTracker()

    /** 打数字给的那一组：先是「我的短语」，再是记下的常用数字。 */
    private fun digitWords(typed: String, phrases: List<com.kingzcheung.xime.settings.DictEntry>, numbers: NumberMemory?): List<String> {
        val code = DigitPhrases.code(typed)
        val learned = numbers?.suggestions(code).orEmpty()
        return (DigitPhrases.matching(code, phrases) + learned).distinct().take(DigitPhrases.LIMIT)
    }

    /** 光标前读一段给打数字出短语用；有选区、密码框、键盘里的面板输入框、实体键盘、终端、打着字或在算式里时返回 null。主线程调用。 */
    private fun digitPhraseContext(): String? {
        if (service.uiState.value.inputTarget.isInternal || service.isSecretEditor() || service.hasHardwareKeyboard) return null
        if (!service.supportsEnglishCandidateReplace() || service.calculatorEngine.isActive()) return null
        val state = service.candidateState.value
        if (state.isComposing || state.inputText.isNotEmpty() || state.pendingEnglishText.isNotEmpty() ||
            service.t9PartialSegments.isNotEmpty()) return null
        val ic = service.currentInputConnection ?: return null
        if (runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return null
        val before = runCatching { ic.getTextBeforeCursor(DigitPhrases.CONTEXT_LENGTH, 0)?.toString() }.getOrNull() ?: return null
        // 邮箱 @ 后面打的数字（abc@1 → 163.com）归邮箱后缀管。
        return before.takeIf { EmailSuffixes.typedDomain(it) == null }
    }

    /** 返回上一串已经打完、该记下的常用数字（没开「联想常用数字」或没有时为 null）。 */
    private fun showDigitPhrases(phrases: List<com.kingzcheung.xime.settings.DictEntry>, numbers: NumberMemory?): String? {
        val before = digitPhraseContext()
        // 算式里的数字、无痕时打的数字不记；这一串也丢掉，不接着算。
        val finished = when {
            numbers == null -> null
            service.calculatorEngine.isActive() || com.kingzcheung.xime.settings.IncognitoState.active -> { numberRun.reset(); null }
            else -> numberRun.onDigit(before)
        }
        val typed = before?.let(DigitPhrases::typedDigits)
        val words = typed?.let { digitWords(it, phrases, numbers) }.orEmpty()
        if (before == null || typed == null || words.isEmpty()) {
            // 接着打的数字对不上了：收起上一位给的那一组，别留着换错数字。
            if (service.candidateState.value.isDigitPhraseList) service.candidateState.value = service.candidateState.value.copy(
                associationCandidates = emptyList(), digitPhrases = emptyList(), digitPhraseText = "")
            return finished
        }
        service.predictionManager.invalidatePendingPredictions()
        // 联想上下文对上光标前的文字：光标更新时才不会当成上下文变了，把短语清掉。
        service.predictionManager.replaceCommittedText(before)
        service.candidateState.value = service.candidateState.value.copy(associationCandidates = words,
            digitPhrases = words, digitPhraseText = typed, pendingEnglishText = "", isShowingRecentClipboard = false)
        return finished
    }

    /** 退格删掉一位后按剩下的数字重新给短语（[remaining] 是删后光标前的文字）；对不上就收起这一组。主线程调用。 */
    private fun refreshDigitPhrasesAfterDelete(remaining: String) {
        val typed = DigitPhrases.typedDigits(remaining)
        val numbers = if (NumberHistory.isEnabled(service)) NumberHistory.loaded() else null
        val phrases = if (DigitPhrases.isEnabled(service)) DigitPhrases.cachedPhrases() else emptyList()
        val words = typed?.let { digitWords(it, phrases, numbers) }.orEmpty()
        service.predictionManager.replaceCommittedText(remaining)
        service.candidateState.value = service.candidateState.value.copy(associationCandidates = words,
            digitPhrases = words, digitPhraseText = if (words.isEmpty()) "" else typed.orEmpty())
    }

    /** 点候选栏上打数字给的短语：把光标前那串数字换成它；光标前已经不是那串数字就什么都不做（不追加整条短语）。主线程调用。 */
    private fun selectDigitPhrase(state: CandidateState, index: Int): Boolean {
        val word = state.associationCandidates.getOrNull(index) ?: return false
        service.predictionManager.invalidatePendingPredictions()
        englishAutoSpace.disarm()
        // 光标前整串数字还是给短语时那一串（不只是结尾对得上：11 不能换掉 1 的短语）。
        val before = runCatching {
            service.currentInputConnection?.getTextBeforeCursor(DigitPhrases.CONTEXT_LENGTH, 0)?.toString()
        }.getOrNull()
        val replaced = DigitPhrases.typedDigits(before) == state.digitPhraseText &&
            service.replaceBeforeCursor(state.digitPhraseText, com.kingzcheung.xime.settings.PhraseLineBreaks.typed(word))
        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList(),
            digitPhrases = emptyList(), digitPhraseText = "")
        // 选了的就是整串，不再按打的那几位记；选的是记下的常用数字时多记一次。
        numberRun.reset()
        if (replaced && NumberHistory.isEnabled(service) && NumberHistory.loaded()?.contains(word) == true &&
            !com.kingzcheung.xime.settings.IncognitoState.active) NumberHistory.record(service, word)
        // 换掉的数字也是计算器正在记的那串，别让下一个运算符接到已经不在的数字上。
        if (replaced) service.calculatorEngine.clear()
        hardwareNavigation.clear()
        publishHardwareCandidateSelection()
        return replaced
    }

    /** 邮箱框里点候选栏的邮箱后缀：只补上还没打的那段；光标前已经对不上就什么都不做。主线程调用。 */
    private fun selectDirectFieldEmailSuffix(index: Int): Boolean {
        val state = service.candidateState.value
        val domain = state.associationCandidates.takeIf(EmailSuffixes::isSuggestionList)?.getOrNull(index) ?: return false
        val ic = service.currentInputConnection ?: return false
        val before = runCatching { ic.getTextBeforeCursor(EmailSuffixes.CONTEXT_LENGTH, 0)?.toString() }.getOrNull()
        service.predictionManager.invalidatePendingPredictions()
        service.candidateState.value = CandidateState()
        val rest = EmailSuffixes.remainder(before, domain)?.let(service.editorInputPolicy.field::typedLiteral)
        return !rest.isNullOrEmpty() && ic.commitText(rest, 1)
    }

    /**
     * 候选栏上是邮箱后缀、没在打字时，这个键是不是接着打后缀：数字都算；字母只在中文、日语以外的拼音类键盘算
     * （英文键盘的字母另有入口）。九键数字是编码，不算。按键队列里调用，只看状态不读输入框。
     */
    private fun typesEmailDomainKey(key: String): Boolean {
        if (key.length != 1) return false
        val c = key[0]
        val digit = c in '0'..'9'
        if (!digit && c !in 'a'..'z' && c !in 'A'..'Z') return false
        val candidates = service.candidateState.value
        if (!EmailSuffixes.isSuggestionList(candidates.associationCandidates)) return false
        if (candidates.isComposing || candidates.inputText.isNotEmpty() || service.t9PartialSegments.isNotEmpty()) return false
        val ui = service.uiState.value
        val english = ui.isAsciiMode || ui.inputProfile.typesLettersDirectly
        if (!digit && english) return false
        if (!english && isT9Schema(ui.currentSchemaId)) return false
        if (!english && ui.inputProfile.language != com.kingzcheung.xime.settings.InputLanguage.CHINESE) return false
        return english || ui.inputProfile.scheme != com.kingzcheung.xime.settings.InputScheme.ZHUYIN
    }

    /** 光标前接上 [text] 还对得上后缀就原样上屏并重新给后缀，返回 true；对不上返回 false，这个键照常处理。主线程调用。 */
    private fun commitEmailDomainKey(text: String): Boolean {
        val ic = service.currentInputConnection ?: return false
        val before = runCatching { ic.getTextBeforeCursor(EmailSuffixes.CONTEXT_LENGTH, 0)?.toString() }.getOrNull()
        if (!EmailSuffixes.continues(before, text)) return false
        service.predictionManager.invalidatePendingPredictions()
        englishAutoSpace.disarm()
        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList(), pendingEnglishText = "")
        if (!service.commitTextAndPredict(text, isPaste = false, allowPrediction = false).accepted) return true
        offerEmailSuffixes()
        return true
    }

    /** 普通输入框里点候选栏的邮箱后缀：把光标前「@ 已经打的」换成「@ 整个后缀」；光标前已经对不上就只收起。主线程调用。 */
    private fun selectEmailSuffix(domain: String): Boolean {
        service.predictionManager.invalidatePendingPredictions()
        englishAutoSpace.disarm()
        val before = runCatching {
            service.currentInputConnection?.getTextBeforeCursor(EmailSuffixes.CONTEXT_LENGTH, 0)?.toString()
        }.getOrNull()
        val replaced = EmailSuffixes.completion(before, domain)?.let { (typed, full) ->
            service.replaceBeforeCursor(typed, full)
        } == true
        // 数字键盘在 @ 后打的数字、点也进了计算器：换成后缀后别让下一个运算符接到它们上。
        if (replaced) service.calculatorEngine.clear()
        service.candidateState.value = service.candidateState.value.copy(pendingEnglishText = "", associationCandidates = emptyList())
        hardwareNavigation.clear()
        publishHardwareCandidateSelection()
        return replaced
    }

    private val keyEventFieldKinds = setOf(EditorFieldPolicy.Kind.NUMBER_PASSWORD, EditorFieldPolicy.Kind.NUMBER,
        EditorFieldPolicy.Kind.PHONE, EditorFieldPolicy.Kind.PASSWORD)

    /** 实体键盘打字中（有编码或候选）；按键队列外读界面状态，和 Esc 的判断一样。 */
    internal fun hardwareComposing(): Boolean {
        val state = service.candidateState.value
        return state.isComposing || state.inputText.isNotEmpty() || state.preeditText.isNotEmpty() ||
            service.t9PartialSegments.isNotEmpty()
    }

    internal fun handleHardwareCandidateKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && hasPendingCandidateCommit) {
            cancelRejectedCommit()
            return true
        }
        if (service.inputReadiness.ticket() == null) {
            // Editor navigation does not require a decoder. Esc must never become
            // an Android window-dismiss key, even while the engine is starting.
            if (isHardwareArrow(keyCode)) {
                service.schemaController.moveHardwareCursor(event)
                return true
            }
            return keyCode == KeyEvent.KEYCODE_ESCAPE
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val state = service.candidateState.value
            val composing = state.isComposing || state.inputText.isNotEmpty() || state.preeditText.isNotEmpty() ||
                service.t9PartialSegments.isNotEmpty()
            if (!composing) return false
        }
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "hardware_candidate_left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "hardware_candidate_right"
            KeyEvent.KEYCODE_DPAD_UP -> "hardware_candidate_up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "hardware_candidate_down"
            KeyEvent.KEYCODE_ESCAPE -> "hardware_candidate_cancel"
            KeyEvent.KEYCODE_SPACE -> "space"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            else -> if (hardwareCandidateDigitIndex(keyCode, event.isShiftPressed) != null)
                keyCodeToKey(keyCode, false) ?: return false else return false
        }
        handleKeyPress(key, event.isShiftPressed, hardwareEvent = KeyEvent(event))
        return true
    }

    internal fun releaseHardwareCandidateKey(keyCode: Int) {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> HardwareCandidateKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> HardwareCandidateKey.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> HardwareCandidateKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> HardwareCandidateKey.DOWN
            else -> return
        }
        postRimeJob { hardwareNavigation.releasePress(key) }
    }

    /** Touch arrow buttons navigate candidates; physical arrows always edit the text. */
    internal fun moveHardwareCandidate(direction: Int) {
        val code = if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        postRimeJob {
            if (!hasPendingCandidateCommit) {
                processHardwareCandidateKey(KeyEvent(KeyEvent.ACTION_DOWN, code), pageAtBoundary = true)
            }
            hardwareNavigation.releasePress(if (direction < 0) HardwareCandidateKey.LEFT else HardwareCandidateKey.RIGHT)
        }
    }

    internal fun hardwareCandidateHighlight(
        state: CandidateState,
        english: Boolean,
        predictionPending: Boolean = false,
    ): Int {
        return hardwareCandidateSelection(state, english, predictionPending) ?: 0
    }

    internal fun hardwareCandidateSelection(
        state: CandidateState, english: Boolean, predictionPending: Boolean = false,
    ): Int? {
        val snapshot = hardwareCandidateSnapshot(state, english, predictionPending)
        return hardwareSelectionRevision.intValue.let { hardwareNavigation.selectedIndex(snapshot) }
    }

    internal fun resetHardwareCandidateSelection() {
        postRimeJob {
            hardwareNavigation.clear()
            withEditor { publishHardwareCandidateSelection() }
        }
    }

    private fun publishHardwareCandidateSelection() {
        hardwareSelectionRevision.intValue++
    }

    private fun hardwareCandidateSnapshot(
        state: CandidateState,
        english: Boolean,
        predictionPending: Boolean,
    ) = HardwareCandidateSnapshot(service.uiState.value.inputSessionId, service.uiState.value.inputProfile,
        state, english, predictionPending, service.predictionManager.lastCommittedText,
        associationLimit = service.hardwareAssociationLimit(),
        expanded = !service.uiState.value.isCompact && service.keyboardViewModel.candidatePageExpanded.value,
        singleCharOnly = service.keyboardViewModel.singleCharFilter.value,
        windowStart = if (service.uiState.value.isCompact) hardwareNavigation.windowStart(state) else 0)

    /** First engine-page index the physical-keyboard toolbar shows; read in composition, so window moves redraw it. */
    internal fun hardwareWindowStart(state: CandidateState): Int = hardwareSelectionRevision.intValue.let {
        if (service.uiState.value.isCompact) hardwareNavigation.windowStart(state) else 0
    }

    @Volatile private var pendingCandidateCommit: PendingCandidateCommit? = null
    internal val hasPendingCandidateCommit: Boolean get() = pendingCandidateCommit != null

    internal fun discardPendingCandidateCommit() {
        pendingCandidateCommit = null
        service.uiState.value = service.uiState.value.copy(rejectedCommitText = null)
    }

    internal fun retryRejectedCommit() {
        val pending = pendingCandidateCommit ?: return
        if (!service.inputReadiness.accepts(pending.owner.admission) ||
            service.uiState.value.inputSessionId != pending.owner.editor) {
            discardPendingCandidateCommit()
            return
        }
        pending.owner.runInline {
            postRimeJob {
                if (pendingCandidateCommit === pending) deliverCandidateCommit(pending)
            }
        }
    }

    internal fun cancelRejectedCommit() {
        val pending = pendingCandidateCommit ?: return
        if (!service.inputReadiness.accepts(pending.owner.admission) ||
            service.uiState.value.inputSessionId != pending.owner.editor) {
            discardPendingCandidateCommit()
            return
        }
        pending.owner.runInline {
            postRimeJob {
                if (pendingCandidateCommit !== pending) return@postRimeJob
                clearInputStateForKeys()
                withEditor { discardPendingCandidateCommit() }
            }
        }
    }

    private fun commandOwner(admission: Long): InputCommandOwner {
        val editor = service.uiState.value.inputSessionId
        return InputCommandOwner(admission, editor) {
            if (!service.inputReadiness.accepts(admission) || service.uiState.value.inputSessionId != editor) {
                throw kotlinx.coroutines.CancellationException("Expired input command")
            }
        }
    }

    /** Called at the Main user-operation boundary, before work enters the input queue. */
    internal fun captureInputContext(): kotlin.coroutines.CoroutineContext {
        val inherited = InputCommandOwner.current.get()
        if (inherited != null) return inherited.context()
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val admission = service.inputReadiness.ticket()
            ?: throw kotlinx.coroutines.CancellationException("Input is not ready")
        return commandOwner(admission).context()
    }

    private suspend fun <T> withEditor(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.Main) {
            InputCommandOwner.requireOwner().requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            block()
        }

    private fun publishUi(block: suspend () -> Unit) {
        val owner = InputCommandOwner.requireOwner()
        service.uiEventChannel.trySend {
            if (service.inputReadiness.accepts(owner.admission) && service.uiState.value.inputSessionId == owner.editor) {
                KeyLatencyProbe.handed()
                withContext(owner.context()) { block() }
                KeyLatencyProbe.applied()
            }
        }
    }

    /**
     * 候选词变换（hotPath 插件能力）+ 发送 UI 更新。
     * 必须在 key-processing 线程调用：同步等插件至多 15ms；
     * 超时/失败/插件不干预（null）均回退原始候选（actions 为空 = 纯引擎语义）。
     */
    private fun sendTransformedResult(
        result: com.kingzcheung.xime.rime.RimeProcessResult,
        clearPreviousAssociation: Boolean = false,
        afterUpdate: (suspend () -> Unit)? = null,
    ) {
        if (glideBatch.get() == true) return
        // 日语罗马音的显示归一化收口在 updateUIWithResult（刷新路径同样生效），此处不再单独加工
        val transformed = service.candidateTransform.transformFor(result)
        val state = service.uiState.value
        val caretSnapshot = if (!result.isAsciiMode && result.inputText.isNotEmpty() &&
            service.hasInlinePinyinCaretInside() && com.kingzcheung.xime.rime.PinyinEditBuffer.supports(state.currentSchemaId))
            service.rimeEngine.readPinyinEditSnapshot() else null
        KeyLatencyProbe.posted(result.inputText.length)
        publishUi {
            if (!service.rimeEngine.isCandidateRevisionCurrent(result.engineRevision)) return@publishUi
            if (clearPreviousAssociation) service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
            service.sessionController.updateUIWithResult(
                transformed?.let { result.copy(candidates = it.candidates.toTypedArray()) } ?: result,
                transformed?.actions ?: emptyList()
            )
            if (caretSnapshot != null) service.showInlinePinyinCaret(caretSnapshot, state.inputSessionId, state.currentSchemaId)
            if (afterUpdate != null) afterUpdate()
        }
    }

    /** 英文选词后自动补的空格（默认关），只管紧接着的一个键，见 [EnglishAutoSpace]。 */
    internal val englishAutoSpace = EnglishAutoSpace()

    /**
     * 紧跟自动空格的键：空格不再多打，句末标点放到空格前面，右括号去掉空格；返回 true 表示这个键已处理完。
     * 其他键、或光标前已不是刚上屏的“词+空格”时返回 false，键照常处理、空格保留。
     */
    internal suspend fun consumeEnglishAutoSpace(key: String): Boolean {
        if (EnglishAutoSpace.isNeutral(key)) return false
        val armed = englishAutoSpace.take() ?: return false
        val replacement = EnglishAutoSpace.replacementFor(key) ?: return false
        return withEditor {
            if (service.uiState.value.inputTarget.isInternal) return@withEditor false
            val ic = service.currentInputConnection ?: return@withEditor false
            if (!ic.getSelectedText(0).isNullOrEmpty()) return@withEditor false
            val before = runCatching { ic.getTextBeforeCursor(armed.length, 0)?.toString() }.getOrNull()
            if (before != armed) return@withEditor false
            if (replacement.isEmpty()) return@withEditor true
            ic.beginBatchEdit()
            try {
                if (!ic.deleteSurroundingText(1, 0)) return@withEditor false
                // 宿主不收标点就把空格放回去，什么都不丢。
                if (!service.commitTextAndPredict(replacement, isPaste = false, allowPrediction = false).accepted) ic.commitText(" ", 1)
                // 开着「标点后自动加空格」时，换到标点后面的空格同样只管下一个键：连打 ... ?! 空格一直留在最后。
                else if (replacement.length == 2 && replacement[1] == ' ' && englishPunctuationSpaceEnabled())
                    englishAutoSpace.arm(replacement.substring(0, 1))
            } finally {
                ic.endBatchEdit()
            }
            service.candidateState.value = service.candidateState.value.copy(
                pendingEnglishText = "", associationCandidates = emptyList())
            true
        }
    }

    private fun englishPunctuationSpaceEnabled(): Boolean =
        SettingsPreferences.getPrefsPublic(service).getBoolean(EnglishAutoSpace.PUNCTUATION_PREF_KEY, false)

    /**
     * 英文标点后自动加空格（默认关）：英文、西班牙语屏幕键盘在字母后面打 , . ? ! : ; 时上屏“标点+空格”，
     * 补的空格按 [EnglishAutoSpace] 的规则只管下一个键。返回 true 表示已上屏；
     * 中文、日文、实体键盘、数字键盘、网址/邮箱/密码/数字框、终端、有选区或在组字时都返回 false，照常处理。
     */
    internal suspend fun commitEnglishPunctuationSpace(key: String): Boolean {
        if (key.length != 1 || !englishPunctuationSpaceEnabled()) return false
        return withEditor {
            val ui = service.uiState.value
            if (ui.inputTarget.isInternal || !(ui.isAsciiMode || ui.inputProfile.typesLettersDirectly ||
                    typesEnglishWords(ui.inputProfile, ui.isAsciiMode))) return@withEditor false
            if (service.textCommit.isJapaneseProfile()) return@withEditor false
            if (service.editorInputPolicy.field.scoped || service.isSecretEditor() || service.isTerminalEditor()) return@withEditor false
            if (service.keyboardViewModel.keyboardState.value is KeyboardLayoutState.Number) return@withEditor false
            val candidates = service.candidateState.value
            if (candidates.isComposing || candidates.inputText.isNotEmpty() || service.t9PartialSegments.isNotEmpty()) return@withEditor false
            val ic = service.currentInputConnection ?: return@withEditor false
            if (!ic.getSelectedText(0).isNullOrEmpty()) return@withEditor false
            val before = runCatching { ic.getTextBeforeCursor(1, 0) }.getOrNull()
            val after = runCatching { ic.getTextAfterCursor(1, 0) }.getOrNull()
            if (!EnglishAutoSpace.spacesAfterPunctuation(key, before, after)) return@withEditor false
            if (!service.commitTextAndPredict("$key ", isPaste = false, allowPrediction = false).accepted) return@withEditor false
            service.candidateState.value = service.candidateState.value.copy(
                pendingEnglishText = "", associationCandidates = emptyList())
            englishAutoSpace.arm(key)
            true
        }
    }

    /** 紧挨着的上一笔英文滑行（候选、大小写），连着划时连同它一起选；屏上不再紧跟它就作废。 */
    private var lastGlide: com.kingzcheung.xime.glide.PreviousGlide? = null

    /** 英文单词方案这一轮组字的首字母是「首字母大写」自动打成大写的（回车记词时还原，见 [spellingWithoutAutoCap]）。按键线程读写。 */
    private var wordsAutoCapped = false

    /** 英文单词方案选词后自动加空格（同「选词后自动加空格」开关，默认关，#311）：只在应用的输入框里、只跟在词后面。 */
    private fun wordsAutoSpaceAfter(text: String): Boolean {
        val ui = service.uiState.value
        return typesEnglishWords(ui.inputProfile, ui.isAsciiMode) && !ui.inputTarget.isInternal && takesWordsAutoSpace(text) &&
            SettingsPreferences.getPrefsPublic(service).getBoolean(EnglishAutoSpace.PREF_KEY, false)
    }

    /** 「首字母大写」：英文和西、德、法、意、俄语键盘都按这一个开关（日语英数、密码、终端等除外）。 */
    private fun capitalizeEnglishHere(): Boolean {
        val state = service.uiState.value
        if (state.inputTarget.isInternal || service.editorInputPolicy.field.scoped || service.isSecretEditor() ||
            service.isTerminalEditor() || service.textCommit.isJapaneseProfile()) return false
        return englishSentenceStart(service, service.editorConnection(),
            wordStart = service.candidateState.value.pendingEnglishText.isEmpty())
    }

    private fun glideCase(case: (String) -> String, start: Boolean): (String) -> String =
        if (start) { word -> case(word).replaceFirstChar(Char::uppercaseChar) } else case

    /**
     * 英文滑行整词上屏：与逐字母英文输入同一提交边界。前面紧挨文字时补空格；
     * 按光标前的词选这一笔的词，紧挨着上一笔滑行时前后两个词一起选，必要时先改掉上一个词（I'm done）。
     * 上屏后记为当前英文词，其余候选沿用英文候选的回删替换；整对候选连上一个词一起替换。
     */
    internal fun commitGlideWord(candidates: List<com.kingzcheung.xime.glide.GlideCandidate>, case: (String) -> String) {
        if (candidates.isEmpty() || hasPendingCandidateCommit) return
        postRimeJob {
            withEditor {
                if (!service.uiState.value.isAsciiMode) return@withEditor
                service.predictionManager.invalidatePendingPredictions()
                val state = service.candidateState.value
                val textBefore = service.editorConnection()?.getTextBeforeCursor(64, 0)?.toString().orEmpty()
                val replaceSupported = service.supportsEnglishCandidateReplace()
                val previous = previousGlide(state, textBefore, replaceSupported)
                val wordCase = glideCase(case, previous == null && capitalizeEnglishHere())
                fun planFor(previous: com.kingzcheung.xime.glide.PreviousGlide?) = com.kingzcheung.xime.glide.planGlideCommit(
                    candidates, wordCase, previous,
                    com.kingzcheung.xime.glide.glideContextWord(if (previous != null) textBefore.dropLast(previous.text.length) else textBefore),
                    com.kingzcheung.xime.glide.GlideLexicon.loadedBigrams(), com.kingzcheung.xime.glide.GlideLexicon.loadedContractions())
                var plan = planFor(previous)
                val head = if (previous == null) null else plan.replacePrevious?.let { replacement ->
                    if (service.replaceBeforeCursor(previous.text, replacement)) replacement else null
                } ?: previous?.text
                // 换上一个词没成功（宿主拒绝）就只选这一笔，不留整对候选。
                if (previous != null && plan.replacePrevious != null && head == previous.text) plan = planFor(null)
                val word = plan.word
                val before = service.editorConnection()?.getTextBeforeCursor(1, 0)?.lastOrNull()
                val text = com.kingzcheung.xime.glide.glideCommitText(word, before)
                if (!service.commitTextAndPredict(text, isPaste = false, allowPrediction = false).accepted) return@withEditor
                lastGlide = com.kingzcheung.xime.glide.PreviousGlide(word, candidates.take(com.kingzcheung.xime.glide.PAIR_TOP), wordCase)
                service.candidateState.value = state.copy(
                    inputText = "", preeditText = "", isComposing = false,
                    candidates = emptyList(), candidateComments = emptyList(), candidateActions = emptyList(),
                    pendingEnglishText = if (plan.pair && head != null) head + text else word, glidedWord = word,
                    associationCandidates = if (service.isSecretEditor()) emptyList() else plan.alternatives,
                    englishReplaceSupported = replaceSupported,
                    hasNextPage = false, hasPrevPage = false, isShowingRecentClipboard = false,
                )
            }
        }
    }

    /** 紧挨着光标的上一笔滑行；屏上已经不紧跟它（或宿主不能回改）时为空。 */
    private fun previousGlide(state: CandidateState, textBefore: String, replaceSupported: Boolean) = lastGlide?.takeIf {
        replaceSupported && state.glidedWord.isNotEmpty() && it.text == state.glidedWord &&
            state.pendingEnglishText.endsWith(state.glidedWord) && textBefore.endsWith(state.glidedWord)
    }

    /**
     * 英文滑行实时预览：手指还没抬起时，按和 [commitGlideWord] 同一套上下文规则算出“现在抬手会上屏什么”。
     * 一笔滑行只在开头读一次光标前的文字，返回的函数不再碰输入框，可在后台线程调用。
     */
    internal fun glidePreviewer(): ((List<com.kingzcheung.xime.glide.GlideCandidate>, (String) -> String) -> com.kingzcheung.xime.ui.keyboard.GlideEnglishPreview)? {
        if (!service.uiState.value.isAsciiMode) return null
        val state = service.candidateState.value
        val textBefore = runCatching { service.editorConnection()?.getTextBeforeCursor(64, 0)?.toString() }.getOrNull().orEmpty()
        val previous = previousGlide(state, textBefore, service.supportsEnglishCandidateReplace())
        val before = com.kingzcheung.xime.glide.glideContextWord(if (previous != null) textBefore.dropLast(previous.text.length) else textBefore)
        val sentenceStart = previous == null && capitalizeEnglishHere()
        return { candidates, case ->
            val plan = com.kingzcheung.xime.glide.planGlideCommit(candidates, glideCase(case, sentenceStart), previous, before,
                com.kingzcheung.xime.glide.GlideLexicon.loadedBigrams(), com.kingzcheung.xime.glide.GlideLexicon.loadedContractions())
            com.kingzcheung.xime.ui.keyboard.GlideEnglishPreview(
                if (plan.replacePrevious != null) plan.preview
                else com.kingzcheung.xime.glide.glideCommitText(plan.word, textBefore.lastOrNull()),
                listOf(plan.word) + plan.alternatives)
        }
    }

    /**
     * 中文滑行：一个词的编码按点按同一路径逐键送进 Rime，已打的拼音、分段选词、邻键纠错和候选刷新都不变。
     * 接在未上屏的编码后面时先补音节分隔符（xi 后面划 an 是 xi'an，不会并成 xian）。
     */
    internal fun typeGlideCode(code: String, alternatives: List<String>, previewToken: Long?) {
        if (code.isEmpty() || hasPendingCandidateCommit) {
            previewToken?.let { service.finishGlidePreview(it, false) }
            return
        }
        prepareGlideAlternatives(code, alternatives)
        // The editor's snapshot predates this glide; queued edits still finish in FIFO order.
        service.keyboardCallbacks?.onDismissPreeditEditor?.invoke()
        val trackGlide = chineseGlideDeleteEnabled()
        var beforeGlide: ChineseGlideDeleteHistory.State? = null
        var typedTail: ChineseGlideDeleteHistory.TypedTail? = null
        var typedSyllables: List<String>? = null
        if (trackGlide) postRimeJob("glide_delete_start") {
            beforeGlide = chineseGlideState()
            typedTail = beforeGlide?.let { chineseGlideDeletes.typedTail(it) }
            typedSyllables = typedTail?.let { chineseCodeSyllables(it.code, service.rimeEngine.readQueuedResult(), allowIncomplete = true) }
        }
        val input = service.candidateState.value.inputText
        submittingGlide = previewToken != null
        try {
            if (input.isNotEmpty() && input.last() != '\'') handleKeyPress("'", false)
            code.forEach { handleKeyPress(it.toString(), false) }
        } finally {
            submittingGlide = false
        }
        // The FIFO barrier reads the final engine state after all of the glide's normal key events.
        val glideSession = service.uiState.value.inputSessionId
        val glideSchema = service.uiState.value.currentSchemaId
        postRimeJob("glide_finish") {
            val result = service.rimeEngine.readQueuedResult()
            val caretSnapshot = service.rimeEngine.readPinyinEditSnapshot()
            if (trackGlide) {
                typedTail?.takeIf { typedSyllables != null }?.let {
                    chineseGlideDeletes.record(it.before, beforeGlide, it.code, typedSyllables)
                }
                chineseGlideDeletes.record(beforeGlide, chineseGlideState(), code, chineseCodeSyllables(code, result))
            }
            if (previewToken == null) {
                withEditor {
                    if (result != null && service.rimeEngine.isCandidateRevisionCurrent(result.engineRevision))
                        service.showInlinePinyinCaret(caretSnapshot, glideSession, glideSchema)
                }
                return@postRimeJob
            }
            try {
                val transformed = result?.let { service.candidateTransform.transformFor(it) }
                withEditor {
                    if (result != null && service.rimeEngine.isCandidateRevisionCurrent(result.engineRevision)) {
                        // 这一笔的键是成批送的，不经 sendTransformedResult：双拼打第一个键时留到出结果才清的
                        // 上一次联想（deferAssociationClear）要在这里清，否则上屏后接着划，旧联想词还挂在候选栏。
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                        service.sessionController.updateUIWithResult(
                            transformed?.let { result.copy(candidates = it.candidates.toTypedArray()) } ?: result,
                            transformed?.actions ?: emptyList(),
                            updateInputBoxComposition = service.isGlidePreviewHeld(previewToken),
                        )
                        service.finishGlidePreview(previewToken, true)
                        service.showInlinePinyinCaret(caretSnapshot, glideSession, glideSchema)
                    } else service.finishGlidePreview(previewToken, false)
                }
            } finally {
                withContext(Dispatchers.Main) { service.finishGlidePreview(previewToken, false) }
            }
        }
    }

    /**
     * 打这一笔之前，在独立会话里把其后几个编码各预览一次，记下各自的第一个候选（[GlideAlternatives]）；
     * 打完后候选栏在首选之后给出。组合里已经选过字（部分上屏）时不给，免得换编码时动到已选的部分。
     */
    private fun prepareGlideAlternatives(code: String, alternatives: List<String>) {
        val holder = service.candidateTransform.glideAlternatives
        val codes = alternatives.filter { it.isNotEmpty() && it != code }.distinct().take(GlideAlternatives.MAX)
        if (codes.isEmpty()) { holder.clear(); return }
        val schema = service.uiState.value.currentSchemaId
        val session = service.uiState.value.inputSessionId
        postRimeJob("glide_alternatives") {
            holder.clear()
            val snapshot = service.rimeEngine.readPinyinEditSnapshot()
            val input = snapshot.getOrNull(0).orEmpty()
            val caret = snapshot.getOrNull(1)?.toIntOrNull() ?: input.length
            if ((snapshot.getOrNull(2)?.toIntOrNull() ?: 0) > 0) return@postRimeJob
            val revision = service.rimeEngine.glidePreviewRevision()
            val choices = codes.mapNotNull { alternative ->
                val preview = runCatching { service.rimeEngine.glidePreview(alternative, revision) }.getOrNull() ?: return@mapNotNull null
                val text = preview.candidates.firstOrNull()?.text?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val (next, nextCaret) = GlideAlternatives.inserted(input, caret, alternative)
                if (preview.inputText != next) return@mapNotNull null
                GlideAlternatives.Choice(text, next, nextCaret)
            }
            if (choices.isEmpty()) return@postRimeJob
            val (typed, typedCaret) = GlideAlternatives.inserted(input, caret, code)
            holder.entry = GlideAlternatives.Entry(typed, typedCaret, schema, session, choices)
        }
    }

    /** 点了滑行备选：把这一笔的编码换成它，组合留着。换不了（组合已经变了）时不上屏，什么也不做。 */
    private fun applyGlideAlternative(input: String, snapshot: CandidateState) {
        val holder = service.candidateTransform.glideAlternatives
        val before = holder.entry ?: return
        val state = service.uiState.value
        if (before.schema != state.currentSchemaId || before.session != state.inputSessionId) return
        val choice = holder.choose(input, snapshot.candidates.firstOrNull()) ?: return
        if (!service.rimeEngine.applyPinyinEdit(before.input, choice.input, choice.caret, expectedSchema = before.schema)) {
            holder.entry = before
            return
        }
        service.rimeEngine.readQueuedResult()?.let { sendTransformedResult(it) }
    }

    /**
     * 分号是编码键的方案没在打字时点分号键：按全角／半角设置出标点（全角是「；」）。
     * 键面也跟着写（semicolonKeyLabel）；实体键盘的分号空闲时本来就由 Rime 出「；」。
     */
    private fun semicolonPunctuation(): String = punctuationWidth(";", service.textCommit.isFullWidthPunctuation())

    /** 当前方案把分号当编码键：微软双拼的 ing，或自建双拼把某个韵母放在分号上（[DoublePinyinScheme.usesSemicolon]）。 */
    internal fun semicolonIsCode(): Boolean = service.uiState.value.let {
        !it.isAsciiMode && com.kingzcheung.xime.settings.DoublePinyinSchemes.find(it.currentSchemaId)?.usesSemicolon == true
    }

    /**
     * 导入的第三方方案（声笔等）把 ; ' , . / 这类符号写进 speller/alphabet 当编码键（#87）：这些键改成把半角字符交给 Rime，
     * 由方案决定是编码、顶屏还是出标点；空格两侧逗号、句号键打出的「，」「。」也按 , . 算。
     * 只管自定义编码方案（[com.kingzcheung.xime.settings.InputScheme.EXTERNAL]）；内置方案、字母表里没有的符号返回 null，照旧按标点上屏。
     * 字母表里的数字（声笔 sbxm 末尾带 1234567890，#106）也一样，但只在打字中：方案用数字编码还是选词（sbxm 的
     * alternative_select_keys）由 Rime 定；没在打字时数字照旧直接上屏。九键等数字本来就是编码的键盘不经过这里。
     */
    internal fun schemeCodeKey(text: String): String? {
        val state = service.uiState.value
        if (text.length != 1 || state.isAsciiMode ||
            state.inputProfile.scheme != com.kingzcheung.xime.settings.InputScheme.EXTERNAL) return null
        val c = when (text[0]) { '，' -> ','; '。' -> '.'; else -> text[0] }
        if (c.code !in 0x21..0x7E || c.isLetter()) return null
        if (c.isDigit() && (!service.candidateState.value.isComposing || state.inputProfile.layout.kind !in
                setOf(com.kingzcheung.xime.settings.LayoutKind.ALPHABETIC, com.kingzcheung.xime.settings.LayoutKind.MERGED))) return null
        return c.toString().takeIf { service.rimeEngine.spellerAlphabetHas(state.currentSchemaId, c) || schemeLeadKey(c) }
    }

    /**
     * 导入方案自己的 recognizer 规则以这个字符开头（`` ^` `` 部件组字、`^V` 计算，#118），且还没在打字：交给 Rime 起头，
     * 和桌面 Rime 一样；打字中照旧。内置方案不经过这里。
     */
    private fun schemeLeadKey(c: Char): Boolean {
        val state = service.uiState.value
        return state.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.EXTERNAL &&
            !service.candidateState.value.isComposing &&
            c in com.kingzcheung.xime.settings.SchemaManager.recognizerLeadChars(service, state.currentSchemaId)
    }

    /**
     * 雾凇 26 键打着 Unicode 码（大写 U 加十六进制）时，角标、长按打的数字接进编码交给 Rime，
     * 不再把 U 连同数字原样上屏。数字行的数字本来就进 Rime；九键、双拼、英文和数字面板不经过这里。
     */
    internal fun unicodeCodeDigit(text: String): Boolean {
        val state = service.uiState.value
        return text.length == 1 && text[0] in '0'..'9' && !state.isAsciiMode &&
            isRimeIceCapitalSchema(state.currentSchemaId) && isUnicodeCodeInput(service.candidateState.value.inputText)
    }

    /** 导入方案的大写字母交给 Rime（#118）：字母表里有这个大写，或方案的 recognizer 规则以它起头（没在打字时）。 */
    private fun externalCapitalInRime(letter: Char): Boolean {
        val state = service.uiState.value
        if (state.inputProfile.scheme != com.kingzcheung.xime.settings.InputScheme.EXTERNAL) return false
        val upper = letter.uppercaseChar()
        return service.rimeEngine.spellerAlphabetHas(state.currentSchemaId, upper) || schemeLeadKey(upper)
    }

    internal fun handleKeyPress(key: String, isShifted: Boolean, hardwarePageDirection: Int = 0,
        languageSwitchMode: com.kingzcheung.xime.settings.LanguageSwitchMode? = null,
        hardwareEvent: KeyEvent? = null, mergedKey: Boolean = false) {
        if (hasPendingCandidateCommit) {
            service.noteInputDropped("key_pending_commit")
            return
        }
        val inherited = InputCommandOwner.current.get()
        if (inherited != null) {
            inherited.requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            routeKeyPress(key, isShifted, hardwarePageDirection, languageSwitchMode, hardwareEvent, mergedKey)
            return
        }
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "Only a direct Main input entry may create command ownership"
        }
        val admission = service.inputReadiness.ticket() ?: return service.noteInputDropped("key_not_ready")
        // 实体键盘 Tab：手写板上还没确认的字先排队上屏，再处理 Tab（否则 Tab 换了输入框，这些字就丢了）
        if (hardwareEvent?.keyCode == KeyEvent.KEYCODE_TAB) service.keyboardViewModel.confirmPendingHandwriting?.invoke()
        commandOwner(admission).runInline { routeKeyPress(key, isShifted, hardwarePageDirection, languageSwitchMode, hardwareEvent, mergedKey) }
    }

    private fun routeKeyPress(key: String, isShifted: Boolean, hardwarePageDirection: Int,
        languageSwitchMode: com.kingzcheung.xime.settings.LanguageSwitchMode?, hardwareEvent: KeyEvent?, mergedKey: Boolean) {
        // 空键无任何按键语义，且下游 Rime 路由按 key[0] 取码（key.lowercase()[0]），
        // 空串会越界崩溃（2026-09-14 真机实证：滑动手势 commit 值为空时触发）。
        if (key.isEmpty()) return
        val admission = service.inputReadiness.ticket() ?: return
        // Capture what Space meant at touch time; a later Next tap must not change this queued commit.
        val spaceSnapshot = service.candidateState.value.takeIf {
            hardwareEvent == null && key == "space" &&
                (it.candidates.isNotEmpty() || it.focusedT9AssociationIndex != null) &&
                it.usesT9CandidateNavigation(service.uiState.value.inputProfile)
        }
        // A language key does not edit text. Keep an outstanding voice final while the
        // language observer decides whether this is the same native/English pair.
        if (key != "ime_switch") service.voiceRecognitionHandler.abandonPendingOnManualInput()
        if (service.uiState.value.toolPanelInputFocused) {
            val candState = service.candidateState.value
            val hasComposing = candState.isComposing || candState.inputText.isNotEmpty()
            when (key) {
                "enter" -> {
                    if (hasComposing) {
                        // 组合态：先把拼音提交进面板输入框，再触发生成。剪贴板搜索提交拼音首字母，
                        // 与边打边筛同一口径（"di'zhi" → dz）。
                        // 编码以屏上显示的为准（九键高亮换了就是高亮候选的拼音；九键、双字母键不交原始键码）。
                        val profile = service.uiState.value.inputProfile
                        val shown = candState.shownPreedit(profile)
                        val input = if (service.uiState.value.clipboardSearchVisible)
                            com.kingzcheung.xime.clipboard.ClipboardSearch.composingInitials(shown,
                                com.kingzcheung.xime.clipboard.AndroidPinyinInitials::initialOf)
                        else rawCompositionCommitText(candState.inputText, shown,
                            isT9Schema(service.uiState.value.currentSchemaId), usesMergedLetterKeys(profile.layout))
                        service.mainHandler.post {
                            if (!service.inputReadiness.accepts(admission)) return@post
                            ToolPanelEditTextHolder.editText?.let { et ->
                                val start = et.selectionStart.coerceAtLeast(0)
                                et.text?.replace(start, et.selectionEnd.coerceAtLeast(start), input)
                                try { et.setSelection(start + input.length) } catch (_: Exception) {}
                            }
                            service.rimeEngine.endComposition(isT9Schema(service.uiState.value.currentSchemaId))
                            service.candidateState.value = service.candidateState.value.copy(
                                inputText = "",
                                preeditText = "",
                                pendingEnglishText = "",
                                candidates = emptyList(),
                                candidateComments = emptyList(),
                                associationCandidates = emptyList(),
                                isComposing = false,
                                candidateActions = emptyList()
                            )
                        }
                    }
                    // 排在上面写入拼音的 post 之后：生成要带上最后这段还没上屏的编码（审计 F08）。
                    if (service.uiState.value.clipboardSearchVisible) service.mainHandler.post { service.submitClipboardSearch() }
                    else service.mainHandler.post {
                        if (service.inputReadiness.accepts(admission)) service.triggerToolPanelGenerate()
                    }
                    return
                }
                "delete" -> {
                    if (hasComposing) {
                        // 组合态：退格走 Rime，更新候选栏。删空时清掉组合后再读结果，旧结果过期会被丢掉，最后一个字母删不掉（11-03）。
                        sendTransformedResult(service.rimeEngine.tryDeleteCompositionAndGetResult())
                    } else {
                        deleteInToolPanelField()
                    }
                    return
                }
                "space" -> {
                    if (hardwareEvent == null) {
                        if (hasComposing && candState.candidates.isNotEmpty()) {
                            val snapshot = spaceSnapshot ?: candState
                            val index = snapshot.spaceCandidateIndex(service.uiState.value.inputProfile)
                            postRimeJob { selectCandidateAsync(index, snapshot = snapshot) }
                        } else {
                            ToolPanelEditTextHolder.editText?.let { et ->
                                val start = et.selectionStart.coerceAtLeast(0)
                                et.text?.insert(start, service.textCommit.keyboardLiteral(" "))
                                try { et.setSelection(start + 1) } catch (_: Exception) {}
                            }
                        }
                        return
                    }
                }
                else -> {
                    // The clipboard search field is one line: a hardware Tab (or other control
                    // character) has nothing to insert there.
                    if (key.length == 1 && key[0].isISOControl() && service.uiState.value.clipboardSearchVisible) return
                    if (key.length == 1) {
                        val isLetter = key.matches(Regex("[a-zA-Z]"))
                        val isChineseMode = !service.uiState.value.isAsciiMode
                        // 微软双拼的分号：打字中和字母一样进 Rime（ing），没在打字时照旧按标点上屏。
                        val semicolonCode = key == ";" && semicolonIsCode()
                        if (semicolonCode && !hasComposing && hardwareEvent == null) {
                            service.textCommit.commitLiteralText(semicolonPunctuation(), preserveWidth = true)
                            return
                        }
                        if ((isLetter || semicolonCode && hasComposing) && isChineseMode) {
                            // 中文模式：字母进 Rime 拼音组合，候选栏选词后经 commitText 重定向进面板输入框
                            val keyCode = if (mergedKey) service.rimeEngine.mergedLetterKeyCode(key[0]) else key.lowercase()[0].code
                            val result = service.rimeEngine.processKeyAndGetResult(keyCode, 0)
                            if (result.processed) {
                                sendTransformedResult(result)
                            }
                            return
                        }
                        val char = if (isShifted) key.uppercase() else key
                        ToolPanelEditTextHolder.editText?.let { et ->
                            val start = et.selectionStart.coerceAtLeast(0)
                            et.text?.insert(start, char)
                            try { et.setSelection(start + char.length) } catch (_: Exception) {}
                        }
                        return
                    }
                }
            }
        }
        // Enter edits this draft; saving is exclusively an explicit button action.
        if (service.uiState.value.showQuickSendForm) {
            when (key) {
                "enter" -> {
                    val editor = QuickSendFormEditTextHolder.editText ?: return
                    val wasCode = service.uiState.value.quickSendCodeFocused
                    editor.requestFocus()
                    // The single-line shortcut code uses Enter to return to the text editor.
                    if (wasCode) return
                }
                "delete" -> {
                    val candState = service.candidateState.value
                    val isComposing = candState.isComposing || candState.inputText.isNotEmpty()
                    if (isComposing) {
                        // Rime 有组合态 → 转发退格到 Rime 清空候选字母/联想词（同上，删空后的结果在清掉组合后读）
                        sendTransformedResult(service.rimeEngine.tryDeleteCompositionAndGetResult())
                    } else {
                        // 无组合态 → 按焦点路由删除表单内（文本框/触发编码框）已上屏文字
                        service.deleteInQuickSendForm()
                    }
                    return
                }
            }
        }
        // 长按只允许一个待处理重复；每次独立点击仍按原始顺序执行。
        if (key == "delete") {
            handleDeleteKey()
            return
        }
        // 只记按键类别，不记具体字符。
        val latencyKind = when {
            key == "space" -> "space"
            key == "enter" -> "enter"
            key.length == 1 && key[0].isLetter() -> "letter"
            key.length == 1 && key[0].isDigit() -> "digit"
            else -> "key"
        }
        if (latencyKind == "letter" && hardwareEvent == null) KeyLatencyProbe.fired(service.uiState.value.inputProfile.layout.id)
        val inputContext = InputCommandOwner.requireOwner().context()
        val commandContext = if (submittingGlide) inputContext + glideBatch.asContextElement(true) else inputContext
        service.inputCommands.submit(commandContext, kind = latencyKind) command@{
            KeyLatencyProbe.started()
            InputCommandOwner.requireOwner().requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            if (!service.inputReadiness.accepts(admission) || hasPendingCandidateCommit) return@command
            if (usesDirectFieldInput && routeDirectFieldKey(key, isShifted, hardwareEvent)) return@command
            if (service.uiState.value.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.EXTERNAL &&
                !service.uiState.value.isAsciiMode &&
                !service.rimeEngine.spellerAlphabetKnown(service.uiState.value.currentSchemaId)) service.rimeEngine.warmSpellerAlphabet()
            val panelKeyboard = service.keyboardViewModel.keyboardState.value.let {
                it is KeyboardLayoutState.Number || it is KeyboardLayoutState.CommonSymbol }
            val schemeCode = if (panelKeyboard) null else schemeCodeKey(key)
            // 分号是编码键的方案（微软双拼）：没在打字时点屏幕上的分号键仍按标点上屏，和以前一样；打字中往下送进 Rime 组成 ing。
            if (hardwareEvent == null && key == ";" && semicolonIsCode() && service.t9PartialSegments.isEmpty() &&
                service.rimeEngine.readQueuedComposition()?.input.isNullOrEmpty()) {
                service.textCommit.literalSubmission(semicolonPunctuation(), preserveWidth = true)?.invoke()
                return@command
            }
            if (englishAutoSpace.isArmed && consumeEnglishAutoSpace(key)) return@command
            if (hardwareEvent != null) {
                // Idle Tab belongs to the editor: single-line fields move focus (Shift+Tab backwards),
                // multi-line ones insert a tab. Only an unfinished composition keeps Tab in the IME.
                if (hardwareEvent.keyCode == KeyEvent.KEYCODE_TAB && service.t9PartialSegments.isEmpty() &&
                    service.rimeEngine.readQueuedComposition()?.input.isNullOrEmpty()) {
                    withEditor {
                        service.predictionManager.invalidatePendingPredictions()
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = "", associationCandidates = emptyList())
                        service.currentInputConnection?.let { connection ->
                            connection.sendKeyEvent(hardwareEvent)
                            connection.sendKeyEvent(KeyEvent.changeAction(hardwareEvent, KeyEvent.ACTION_UP))
                        }
                    }
                    return@command
                }
                if (hardwareEvent.keyCode == KeyEvent.KEYCODE_TAB) {
                    // 组字时 Tab 换到下一个候选、Shift+Tab 换回上一个，和 Windows 版同一规则（用户 10-21）；
                    // 走候选栏左右按钮那条路（不移动拼音里的光标）。没在组字时上面已把 Tab 交给编辑框跳输入框。
                    val direction = if (hardwareEvent.isShiftPressed) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                    processHardwareCandidateKey(KeyEvent(KeyEvent.ACTION_DOWN, direction), pageAtBoundary = true)
                    hardwareNavigation.releasePress(if (hardwareEvent.isShiftPressed) HardwareCandidateKey.LEFT else HardwareCandidateKey.RIGHT)
                    return@command
                }
                if (processHardwareCandidateKey(hardwareEvent)) return@command
                if (hardwareCandidateDigitIndex(hardwareEvent.keyCode, hardwareEvent.isShiftPressed) != null) {
                    // DefaultInput here means no numbered candidate owns this digit.
                    // Never send it back into the active T9 decoder as a new spelling.
                    commitHardwareLiteral(key)
                    return@command
                }
                if (hardwareSymbolIsLiteral(key, hardwareEvent.isShiftPressed,
                        numpad = hardwareEvent.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS,
                        chinesePunctuation = !service.uiState.value.isAsciiMode &&
                            service.uiState.value.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE)) {
                    commitHardwareLiteral(key)
                    return@command
                }
                // 打拼音时不按 Shift 的 ; / \ 等：先把字母原样上屏（不上屏首选，Gboard 规则，用户 2026-10-24），
                // 再按空闲时的标点规则出符号。翻页键另有入口，拼音分隔符 ' 照旧。
                // 分号是编码键的方案（微软双拼）分号照样送进 Rime：打字中是 ing，没在打字时由 Rime 出标点。
                if (hardwarePageDirection == 0 && key.length == 1 && key != "'" && !(key == ";" && semicolonIsCode()) &&
                    schemeCode == null && isLiteralPunctuation(key) &&
                    !service.uiState.value.isAsciiMode &&
                    service.uiState.value.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
                    (service.rimeEngine.readQueuedComposition()?.input?.isNotEmpty() == true ||
                        service.t9PartialSegments.isNotEmpty()) && !commitHardwareLiteral("")) return@command
            } else {
                hardwareNavigation.clear()
            }
            if (hardwarePageDirection != 0 &&
                (service.rimeEngine.compositionActiveForDeletion() != false || service.t9PartialSegments.isNotEmpty())) {
                // Consume even at either boundary or when decoding has no candidates yet.
                // Never turn a reserved paging key into punctuation in an unfinished composition.
                if (service.keyboardViewModel.candidatePageExpanded.value) {
                    withEditor { service.expandedPageScroll(hardwarePageDirection) }
                } else {
                    pageHardwareCandidates(service.candidateState.value, hardwarePageDirection)
                    withEditor { service.updateUI() }
                }
                withEditor { publishHardwareCandidateSelection() }
                return@command
            }
            val english = service.uiState.value.let {
                it.isAsciiMode || it.inputProfile.typesLettersDirectly
            }
            // 西班牙、德、法、俄语键盘上 ñ、ä、é、й 这些也是字母：同样逐字上屏、参与补全。
            val letterLanguage = service.uiState.value.let {
                if (it.isAsciiMode) null else com.kingzcheung.xime.settings.LetterLanguage.of(it.inputProfile.language)
            }
            // 韩语：字母按两벌식拼成音节，光标前的音节随打随换。
            if (letterLanguage == com.kingzcheung.xime.settings.LetterLanguage.KOREAN && routeHangulKey(key, isShifted)) return@command
            if (english && isDirectLetter(key, letterLanguage?.extraLetters.orEmpty())) {
                val next = withEditor {
                    // Clear and commit in the same Main visit, including when the
                    // previous letter already has completions on screen.
                    service.predictionManager.invalidatePendingPredictions()
                    // 候选栏上是邮箱后缀（刚打了 @）：接着打的字母是后缀的开头，下面只留对得上的。
                    val emailDomainTyping = EmailSuffixes.isSuggestionList(service.candidateState.value.associationCandidates)
                    service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    val current = service.candidateState.value
                    // 滑行出词后直接点字母：光标仍紧跟那个词时自动补空格；空格、删除或标点之后不补。
                    // 连着划两个词时当前英文词是整对（I'm done），光标仍紧跟这一笔的词就算。
                    val afterGlide = current.glidedWord.isNotEmpty() && current.pendingEnglishText.endsWith(current.glidedWord) &&
                        service.currentInputConnection?.getTextBeforeCursor(current.glidedWord.length, 0)
                            ?.toString() == current.glidedWord
                    // Japanese 英数 follows 全角／半角, so full-width letters can be typed directly.
                    val fullWidthLetter = service.textCommit.isJapaneseProfile() && service.textCommit.isFullWidthPunctuation()
                    // 西、德、法、意、俄语键盘和英文同一个「首字母大写」开关（以前只有英文会自动大写）；韩文没有大小写。
                    val autoCaps = hardwareEvent == null && !isShifted && capitalizeEnglishHere()
                    val next = commitEnglishLetter(current, key, isShifted || autoCaps,
                        service::supportsEnglishCandidateReplace, afterGlide = afterGlide) {
                        val letter = if (fullWidthLetter) fullWidthLetters(it) else it
                        service.commitTextAndPredict(letter, isPaste = false, allowPrediction = false).accepted
                    }
                    if (next != null) service.candidateState.value = next
                    // 打邮箱后缀：只给开头对得上的后缀，不出英文补全；都对不上了照常出英文补全。
                    // 按光标前的整段后缀筛（打了点以后 qq.c 的 c 才是一个新词）。
                    val domains = if (next != null && emailDomainTyping && next.englishReplaceSupported && !service.isSecretEditor())
                        EmailSuffixes.suggestions(runCatching {
                            service.currentInputConnection?.getTextBeforeCursor(EmailSuffixes.CONTEXT_LENGTH, 0)
                        }.getOrNull()) else emptyList()
                    if (next != null && domains.isNotEmpty()) {
                        service.candidateState.value = next.copy(associationCandidates = domains)
                        return@withEditor null
                    }
                    next
                }
                if (next != null && !service.isSecretEditor() && next.englishReplaceSupported) {
                    val pending = next.pendingEnglishText
                    service.serviceScope.launch(InputCommandOwner.requireOwner().context()) {
                        val words = service.predictionManager.getEnglishAssociations(pending)
                        withEditor {
                            val current = service.candidateState.value
                            if (current.pendingEnglishText == pending && service.uiState.value.let {
                                    it.isAsciiMode || it.inputProfile.typesLettersDirectly }) {
                                service.candidateState.value = current.copy(associationCandidates = words)
                            }
                        }
                    }
                }
                return@command
            }
            // 给着邮箱后缀时接着打的数字（中文键盘下还有字母）：对得上某个后缀就原样上屏、筛后缀，不进拼音。
            if (hardwareEvent == null && schemeCode == null && !mergedKey && typesEmailDomainKey(key) &&
                withEditor { commitEmailDomainKey(if (isShifted) key.uppercase() else key) }) return@command
            // 下面会先清掉联想（含邮箱后缀）；数字键盘走计算器那条路时要知道按键前是不是正给着后缀。
            val priorCandidates = service.candidateState.value
            val emailSuffixesShown = EmailSuffixes.isSuggestionList(priorCandidates.associationCandidates)
            // Keep the old frame until the first double-pinyin result can replace it in one UI update.
            val deferAssociationClear = hardwareEvent == null && !isShifted && !mergedKey && !service.uiState.value.isAsciiMode &&
                service.uiState.value.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN &&
                key.length == 1 && key[0] in 'a'..'z' && !priorCandidates.isComposing && priorCandidates.inputText.isEmpty() &&
                priorCandidates.associationCandidates.isNotEmpty() && !emailSuffixesShown &&
                !priorCandidates.isShowingRecentClipboard && !priorCandidates.isDigitPhraseList
            // Most composing keys have nothing to clear. Do not make every key wait
            // for a Main round trip before it can even reach the decoder.
            if (key.length == 1) {
                val hadPendingPrediction = service.predictionManager.hasPendingPrediction
                // Also invalidate not-yet-started prediction callbacks. Checking only
                // isPending would let a previous commit post a prediction after this key.
                service.predictionManager.invalidatePendingPredictions()
                if (!deferAssociationClear && (hadPendingPrediction || service.candidateState.value.associationCandidates.isNotEmpty())) {
                    withEditor {
                        service.predictionManager.invalidatePendingPredictions()
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    }
                }
            }
            // Literal English input has no decoder work and must not wait for its lock.
            // 邻键纠错只对触屏有意义：物理键盘按键是精确的，不做邻键纠错。
            val geometry = letterNeighbors
            service.rimeEngine.setNeighborMap(
                if (hardwareEvent == null && geometry.first == service.uiState.value.currentSchemaId) geometry.second else "")
            // 设置页的候选开关（中英混输、繁体输出、时间日期、表情、符号名、希腊字母、生僻字）：设置一变已由
            // XimeInputMethodService 的设置监听立刻写进引擎；Rime 选项随会话重建而清零，按键前再补一次。
            // 只在变了时写，免得每键触发选项通知；九键数字键也走这里。
            if (key.length == 1 && key[0].isLetterOrDigit()) service.sessionController.applyCandidateOptions()
            if (schemeCode != null) {
                // 第三方方案的符号编码键（#87）：先交给 Rime；方案没处理再按空闲时的标点规则上屏。
                val result = service.rimeEngine.processQueuedKeyAndGetResult(schemeCode[0].code, 0)
                if (result.processed) {
                    if (result.committedText.isNotEmpty()) withEditor { service.commitText(result.committedText) }
                    sendTransformedResult(result)
                } else service.textCommit.literalSubmission(key,
                    hardware = hardwareEvent != null)?.invoke()
                return@command
            }
            if (service.japaneseInputController.handleKey(key, isShifted)) return@command
            val state = service.uiState.value
            val candState = service.candidateState.value
            var needsUIUpdate = false
            var pendingResult: com.kingzcheung.xime.rime.RimeProcessResult? = null
            var committedText: String? = null

            if (!state.isAsciiMode && state.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.ZHUYIN &&
                routeZhuyinKey(key, hardwareEvent)) return@command
            
            when (key) {
                "clear_composition" -> {
                    // 只清输入态（预编辑/候选/联想/partial 累积/计算器/左栏），不动已上屏文本。
                    // 清理逻辑见 clearInputStateForKeys()（与 clear_all 输入态分支共用）。
                    clearInputStateForKeys()
                    needsUIUpdate = true
                }
                "clear_all" -> {
                    // 上滑清空 = 多次退格快捷方式（对标主流输入法）：输入态只清输入态，空闲态清空全部已上屏。
                    // 引擎繁忙时不把未知状态当成空闲，更不能清正文。
                    if (service.rimeEngine.compositionActiveForDeletion() == null) return@command
                    // 「打字中清空整个输入框」（默认关）：打字中也直接清整个输入框，没上屏的拼音丢掉，撤回只放回输入框原来的字。
                    val clearPrefs = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(service)
                    val wholeField = clearPrefs.getBoolean(
                        com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CLEAR_ALL_WHILE_COMPOSING_KEY, false)
                    // 「只清空光标前的内容」（默认关）：清输入框时光标后的字留着。
                    val beforeOnly = clearPrefs.getBoolean(
                        com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CLEAR_BEFORE_CURSOR_ONLY_KEY, false)
                    // 「退格直接删字」同时管滑动清空（原「滑动清空时跳过联想」11-08 并入）。
                    val skipAssociation = SettingsPreferences.isDeleteSkipsAssociation(service)
                    val scope = ClearAllScope.of(hasInputState(candState, includeAssociation = !skipAssociation), wholeField)
                    // 清的是焦点所在的框：键盘自己的输入框（新增固定等）有焦点时清它，不清后面应用的框。
                    service.lastClearedTarget = service.uiState.value.inputTarget
                    // 清空比之前的左右滑删字新，撤回放回的是这次清空。
                    service.lastSwipeDelete = null
                    if (!scope.field) {
                        service.lastClearedDocument = null
                        // 输入态：只清输入态（等价于 clear_composition），并记录 lastClearedText 供下滑撤回。
                        // 需在 clearInputStateForKeys() 之前记录（该函数会清空 preeditText/inputText）。
                        val pendingEnglish = candState.pendingEnglishText
                        if (pendingEnglish.isNotEmpty()) {
                            // 直接上屏模式：英文编码已逐字落盘，"清输入态"需回删屏上对应字符；
                            // 校验光标前文本一致才删除并记录撤回，否则只清状态不动已上屏文本。
                            val removed = withEditor {
                                val ic = service.editorConnection() ?: return@withEditor false
                                val before = runCatching {
                                    ic.getTextBeforeCursor(pendingEnglish.length, 0)?.toString()
                                }.getOrNull()
                                before == pendingEnglish && ic.deleteSurroundingText(pendingEnglish.length, 0)
                            }
                            if (removed) service.lastClearedText = pendingEnglish
                        } else {
                            // 撤回重放用 inputText（原始键入串）：preeditText 现为带回显分隔符的
                            // 展示串（如 ni'hao），上屏必须无分隔符版本。T9 时 inputText 为合成显示态，
                            // 与 preeditText 同值，行为不变。
                            service.lastClearedText = candState.inputText
                        }
                        clearInputStateForKeys()
                    } else {
                        // 直接上屏的英文已经在输入框里，跟着下面整框一起删、一起撤回，这里不单独回删。
                        // 输入框本来是空的（只有拼音）时，撤回照原来一样放回拼音。
                        service.predictionManager.invalidatePendingPredictions()
                        val composedText = if (scope.composition && candState.pendingEnglishText.isEmpty()) candState.inputText else ""
                        if (scope.composition) clearInputStateForKeys()
                        // 空闲态：清空整个输入框（光标前、选区、光标后），记下来供下滑撤回原位恢复。
                        service.calculatorEngine.clear()
                        updateCalculatorCandidates()
                        // 清空 partial 累积，避免残留词被 buildT9DisplayState 拼进下一轮 preedit。
                        service.t9PartialSegments.clear()
                        endQueuedComposition()
                        service.candidateState.value = service.candidateState.value.copy(
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            pendingEnglishText = "",
                            inputText = "",
                            isComposing = false,
                            isShowingRecentClipboard = false,
                            candidateActions = emptyList()
                        )
                        var rejected: String? = null
                        withEditor {
                            service.lastClearedText = ""
                            service.lastClearedDocument = null
                            rejected = rejectedCommitBefore(clearing = true)
                            service.editorConnection()?.let { ic ->
                                if (!service.uiState.value.inputTarget.isInternal) service.endComposingInputBox()
                                val limit = XimeInputMethodService.SAFE_TEXT_LIMIT
                                val document = ic.readDocumentForClear(limit) ?: return@let
                                service.lastClearedDocument = ic.clearDocument(document, limit,
                                    stillCurrent = { service.editorConnection() === ic }, beforeOnly = beforeOnly)
                            }
                            if (service.lastClearedDocument == null) service.lastClearedText = composedText
                        }
                        rejected?.let(::unlearnRejectedCommit)
                    }
                    needsUIUpdate = true
                }
                "undo_clear" -> {
                    // 下滑撤回 = 撤销"上滑清空"，仅空闲态有效（输入态恢复会插入错误位置），
                    // 判定与 clear_all 共用 hasInputState()。
                    // 只放回清掉它的那个框（应用的框 / 新增固定等键盘自己的框）。
                    // 左右滑删字之后也能撤回（别人 11-05 的需求）：它比清空新，先放回它；滑完改过输入框就不放。
                    val swiped = service.lastSwipeDelete.takeUnless { hasInputState(candState) }
                    if (swiped != null && withEditor { swiped.undo() }) {
                        service.lastSwipeDelete = null
                    } else if (!hasInputState(candState) && service.lastClearedTarget == service.uiState.value.inputTarget) {
                        val text = service.lastClearedText
                        val document = service.lastClearedDocument
                        if (document != null) withEditor {
                            // 整框清空的撤回：原位放回、光标回原处；清空后输入框被改过就不放（审计 F06）。
                            val ic = service.editorConnection() ?: return@withEditor
                            if (ic.restoreDocument(document, XimeInputMethodService.SAFE_TEXT_LIMIT)) service.lastClearedDocument = null
                        } else if (text.isNotEmpty()) {
                            service.lastClearedText = ""
                            withEditor {
                                val ic = service.editorConnection()
                                if (ic != null) {
                                    // newCursorPosition=1：光标停在撤回内容末尾；
                                    // 传 text.length 会被 clamp 到整段文本末尾。
                                    ic.commitText(text, 1)
                                }
                            }
                        }
                    }
                    needsUIUpdate = true
                }
                "enter" -> {
                    service.predictionManager.clearLetterWordContext()
                    service.calculatorEngine.clear()
                    val japaneseResult = service.rimeEngine.processJapaneseEnterIfComposing()
                    if (japaneseResult != null) {
                        // 原生 Return 按日语方案提交假名读音；commit 不能经过可覆盖的 UI channel。
                        if (japaneseResult.committedText.isNotEmpty()) {
                            withEditor { service.commitText(japaneseResult.committedText) }
                        }
                        if (japaneseResult.processed || japaneseResult.committedText.isNotEmpty()) {
                            sendTransformedResult(japaneseResult)
                        }
                        // 未处理时保留输入；成功时按结果刷新，不能再 clearComposition 或重复取 commit。
                        return@command
                    }
                    val engineInput = service.rimeEngine.getInput()
                    // UI publication may lag the FIFO. Enter consumes unfinished input;
                    // only a subsequent, idle Enter may invoke the editor action.
                    // 西班牙语的字母已逐字上屏，没有可提交的输入：回车照常换行或执行动作。
                    if (engineInput.isNotEmpty() || candState.isComposing ||
                        (!state.isAsciiMode && !state.inputProfile.typesLettersDirectly &&
                            candState.pendingEnglishText.isNotEmpty())) {
                        // T9 和 14 键提交屏上的解码拼音（T9 含 partial commit 累积），其他用 RIME input。
                        val isT9 = isT9Schema(state.currentSchemaId)
                        val input = rawCompositionCommitText(engineInput, candState.shownPreedit(state.inputProfile), isT9,
                            usesMergedLetterKeys(state.inputProfile.layout))
                        if (input.isNotEmpty()) {
                            val accepted = withEditor { service.submitText(input).accepted }
                            if (!accepted) return@command
                            // English typed by hand (Obsidian) is offered again next time, dictionary or not.
                            if (!isT9 && typesEnglishCapitals(state.currentSchemaId) &&
                                !com.kingzcheung.xime.settings.IncognitoState.active) {
                                com.kingzcheung.xime.settings.EnglishWordMemory.remember(
                                    com.kingzcheung.xime.settings.SchemaManager.getRimeDir(service),
                                    if (wordsAutoCapped && typesEnglishWords(state.inputProfile, state.isAsciiMode))
                                        spellingWithoutAutoCap(input) else input)
                            }
                        }
                        // 同步清空，避免异步 postRimeJob 延迟导致后续 backspace 拿到旧状态。
                        if (isT9) service.t9PartialSegments.clear()
                        endQueuedComposition()
                        withEditor { service.endComposingInputBox() }
                        needsUIUpdate = true
                    } else {
                        service.rimeEngine.clearComposition()
                        withEditor {
                            if (state.showQuickSendForm) service.submitText("\n") else performIdleEnter()
                        }
                    }
                    withEditor {
                        service.candidateState.value = service.candidateState.value.copy(
                            inputText = "",
                            preeditText = "",
                            pendingEnglishText = "",
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            isComposing = false
                        )
                        if (isT9Schema(state.currentSchemaId)) {
                            service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                            service.uiState.value = service.uiState.value.copy(
                                t9RightCandidateSelectedCount = 0,
                                t9SelectedCandidatePinyin = ""
                            )
                        }
                    }
                }
                "space" -> {
                    if (spaceSnapshot != null) {
                        if (!spaceSnapshot.isComposing) withEditor {
                            if (spaceSnapshot.hasSameSelectionSource(service.candidateState.value) &&
                                spaceSnapshot.associationCandidates == service.candidateState.value.associationCandidates) {
                                selectAssociation(spaceSnapshot.focusedT9AssociationIndex ?: return@withEditor)
                            }
                        } else if (!withEditor { commitHeldCandidateEnglish(spaceSnapshot, spaceSnapshot.highlightedCandidateIndex) }) {
                            selectCandidateAsync(spaceSnapshot.highlightedCandidateIndex, snapshot = spaceSnapshot)
                        }
                        return@command
                    }
                    val pendingEnglish = candState.pendingEnglishText

                    if (pendingEnglish.isNotEmpty()) {
                        // 直接上屏模式：词已逐字落盘，此处只提交空格并结束本轮英文输入，随后给出下一词联想。
                        withEditor {
                            service.commitText(" ")
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                glidedWord = "",
                                associationCandidates = emptyList()
                            )
                        }
                        triggerLetterNextWordAssociations(pendingEnglish)
                    } else if (candState.isComposing) {
                        if (candState.candidates.isNotEmpty()) {
                            val index = candState.spaceCandidateIndex(state.inputProfile)
                            if (hardwareEvent != null || !withEditor { commitHeldCandidateEnglish(candState, index) }) {
                                selectCandidateAsync(index, snapshot = candState)
                            }
                        } else {
                            val input = candState.inputText
                            if (input.isNotEmpty()) {
                                withEditor {
                                    service.commitText(input)
                                    service.candidateState.value = service.candidateState.value.copy(
                                        inputText = "",
                                        preeditText = "",
                                        isComposing = false,
                                        pendingEnglishText = "",
                                        candidates = emptyList(),
                                        candidateComments = emptyList(),
                                        associationCandidates = emptyList(),
                                        candidateActions = emptyList()
                                    )
                                }
                                endQueuedComposition()
                                // T9模式：清空partialCommit累积文本，避免下一轮输入
                                // preedit中残留上一轮的提交内容（如"看"→下一轮"看jihua"）
                                if (isT9Schema(state.currentSchemaId)) {
                                    withEditor {
                                        service.t9PartialSegments.clear()
                                        service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                                        service.uiState.value = service.uiState.value.copy(
                                            t9RightCandidateSelectedCount = 0,
                                            t9SelectedCandidatePinyin = ""
                                        )
                                    }
                                }
                                needsUIUpdate = true
                            }
                        }
                    } else if (!state.isAsciiMode && !state.inputProfile.typesLettersDirectly &&
                        hardwareEvent == null && SettingsPreferences.isSpaceCommitAssociationEnabled(service) &&
                        candState.associationCandidates.isNotEmpty() && !candState.isDeletionRecovery &&
                        // 打数字给的短语只能点选：数字后面按空格还是空格。
                        !candState.isDigitPhraseList &&
                        // 邮箱后缀只能点选：@ 后面按空格还是空格。
                        !EmailSuffixes.isSuggestionList(candState.associationCandidates)
                    ) {
                        // 空格上屏联想候选（中文模式 + 开关开启 + 联想候选存在）：上屏第一个联想词。
                        // 仅中文联想——英文模式此分支不生效，空格保持上屏空格字符；
                        // 连续联想模式：commitText 会自动触发下一轮推理（一直上屏一直推理）；
                        // 单次联想模式：先置抑制标志，上屏引发的那轮推理被跳过并清空联想候选。
                        val association = candState.associationCandidates.first()
                        withEditor {
                            if (SettingsPreferences.isSingleAssociationMode(service)) {
                                service.predictionManager.suppressNextPredictionOnce()
                            }
                            service.commitText(association)
                        }
                    } else {
                        val keepLetterNextWords = (state.isAsciiMode || state.inputProfile.typesLettersDirectly) &&
                            candState.pendingEnglishText.isEmpty() && candState.associationCandidates.isNotEmpty() &&
                            !candState.isDigitPhraseList && !EmailSuffixes.isSuggestionList(candState.associationCandidates)
                        withEditor {
                            service.commitText(service.textCommit.keyboardLiteral(" "))
                            if (keepLetterNextWords) {
                                keepLetterNextWordContext()
                                service.candidateState.value = service.candidateState.value.copy(
                                    associationCandidates = candState.associationCandidates
                                )
                            }
                        }
                    }
                }
                "word_separator" -> {
                    if (candState.isComposing || candState.inputText.isNotEmpty()) {
                        val result = service.rimeEngine.processQueuedKeyAndGetResult(0x27, 0)
                        if (result.processed) {
                            sendTransformedResult(result)
                        } else {
                            needsUIUpdate = true
                        }
                    } else {
                        needsUIUpdate = true
                    }
                }
                "shift" -> {
                }
                "mode_change" -> {
                }
                "ime_switch" -> {
                    service.predictionManager.clearLetterWordContext()
                    // Preserve the existing immediate ASCII preview for the default behavior.
                    // A configured cycle/pair may select another native language and cannot invert ASCII.
                    val switchOptions = com.kingzcheung.xime.settings.LanguageSwitchPreferences.forRequest(
                        com.kingzcheung.xime.settings.LanguageSwitchPreferences.read(service), languageSwitchMode)
                    if (switchOptions.mode ==
                        com.kingzcheung.xime.settings.LanguageSwitchMode.CURRENT_ENGLISH) {
                        val state = service.uiState.value
                        val schemaId = service.rimeEngine.getCurrentSchema()
                        val nativeLanguageEnabled = com.kingzcheung.xime.settings.InputModes.languageOf(
                            schemaId, state.schemas) in com.kingzcheung.xime.settings.LanguagePreferences.enabled(service)
                        val optimisticTarget = if (nativeLanguageEnabled) !state.isAsciiMode else true
                        withEditor {
                            service.uiState.value = service.uiState.value.copy(isAsciiMode = optimisticTarget)
                            service.keyboardViewModel.dispatch(
                                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(optimisticTarget, schemaId)
                            )
                        }
                    }
                    // 在 key-processing 线程上执行切换：toggleAsciiMode 阻塞等待 rimeLock
                    // （部署/维护持锁时排队，完成后自动切换），不在主线程阻塞避免 ANR。
                    val t0 = System.nanoTime()
                    FileLogger.i(XimeInputMethodService.TAG, "ime_switch dispatched, ui ascii=${service.uiState.value.isAsciiMode}, thread=${Thread.currentThread().name}")
                    if (!service.schemaController.switchInputMethod(useLanguageKeyPreference = true,
                            languageSwitchMode = languageSwitchMode)) {
                        // 失败后继续显示引擎的实际状态。
                        withEditor {
                            val actualAscii = service.rimeEngine.isAsciiMode()
                            service.uiState.value = service.uiState.value.copy(isAsciiMode = actualAscii)
                            service.keyboardViewModel.dispatch(
                                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(actualAscii, service.rimeEngine.getCurrentSchema())
                            )
                        }
                    }
                    FileLogger.i(XimeInputMethodService.TAG, "ime_switch handled, total ${(System.nanoTime() - t0) / 1_000_000}ms (queue+rimeLock+main)")
                }
                "abc" -> {
                    service.calculatorEngine.clear()
                    updateCalculatorCandidates()
                }
                "number", "common_symbol" -> {
                    // Number/CommonSymbol 内部切换由 KeyboardView 的 key handler 处理
                }
                "emoji" -> {
                    service.predictionManager.clearLetterWordContext()
                    withEditor {
                        service.commitText("😊")
                    }
                }
                else -> {
                    val isNumberKeyboard = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                    val isCommonSymbolKeyboard = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.CommonSymbol

                    val routeResult = com.kingzcheung.xime.calculator.routeCalculatorKey(
                        key = key,
                        isNumberKeyboard = isNumberKeyboard,
                        isCommonSymbolKeyboard = isCommonSymbolKeyboard,
                        calculatorEngine = service.calculatorEngine,
                    )
                    if (routeResult is com.kingzcheung.xime.calculator.CalculatorRouteResult.Handled) {
                        service.predictionManager.clearLetterWordContext()
                        // 给着邮箱后缀时接着打的数字、点、减号是后缀的一部分（数字键盘以前不给后缀：@ 走这里直接上屏）。
                        // 这时候选栏已经在上面清掉了，按按键前记下的算。
                        val emailDomain = emailSuffixesShown
                        val literal = withEditor {
                            service.textCommit.keyboardLiteral(routeResult.commitText, numberPanel = isNumberKeyboard)
                                .also { service.commitText(it) }
                        }
                        if (isNumberKeyboard) updateCalculatorCandidates()
                        // 数字键盘、常用符号面板打的 @：和主键盘一样给邮箱后缀（默认开），接着打的数字、点重新筛。
                        if (literal == "@" || literal == "＠" ||
                            emailDomain && literal.isNotEmpty() && literal.all { it == '.' || it == '-' || it in '0'..'9' })
                            offerEmailSuffixesAfterAt()
                        // 数字键盘打的数字：对得上「我的短语」编码的短语放到候选栏（默认关）。
                        if (isNumberKeyboard && key.length == 1 && key[0] in '0'..'9') offerDigitPhrases()
                        needsUIUpdate = true
                        return@command
                    }

                    val pendingEnglish = candState.pendingEnglishText
                    
                    // 非计算器键清除计算器状态
                    if (!key.matches(Regex("[0-9]")) && key !in listOf("+", "-", "*", "/", ".")) {
                        if (service.calculatorEngine.isActive() || service.calculatorEngine.getCandidate() != null) {
                            service.calculatorEngine.clear()
                            updateCalculatorCandidates()
                        }
                    }
                    
                    // 计算器模式：追踪数字、运算符和小数点
                    if (key.matches(Regex("[0-9]")) || key in listOf("+", "-", "*", "/", ".")) {
                        if (key.matches(Regex("[0-9]")) || key == ".") {
                            service.calculatorEngine.handleDigit(key)
                        } else {
                            service.calculatorEngine.handleOperator(key)
                        }
                        updateCalculatorCandidates()
                    }
                    
                    // 数字选词拦截（插件候选变换存在时）：数字键不进入引擎——rime 会自行选
                    // 引擎候选并返回 committedText，绕过插件候选；映射为对应位置显示候选的
                    // 点击（selectCandidateAsync 按 candidateActions 分流上屏）。
                    // actions 为空（无变换）时保持原生数字选词行为不变。
                    if (!state.isAsciiMode && candState.isComposing &&
                        key.length == 1 && key[0] in '1'..'9' &&
                        !(isRimeIceCapitalSchema(state.currentSchemaId) && isUnicodeCodeInput(candState.inputText))
                    ) {
                        val actions = service.candidateState.value.candidateActions
                        val digitIndex = key[0] - '1'
                        if (actions.isNotEmpty() && digitIndex < service.candidateState.value.candidates.size) {
                            selectCandidateAsync(digitIndex)
                            return@command
                        }
                    }

                    // 所有按键统一经过 Rime 引擎
                    // 字母键不进入此分支（即使 pendingEnglish 非空），需要继续积累编码
                    // 英文模式（isAsciiMode）下非字母键（QWERTY 上滑的数字/符号、数字/符号面板）
                    // 直接上屏，不进入 Rime 引擎与 pendingEnglish 累积（上滑字符即输即上）。
                    if ((state.isAsciiMode || pendingEnglish.isNotEmpty()) && !key.matches(Regex("[a-zA-Z]"))) {
                        service.predictionManager.clearLetterWordContext()
                        val finalKey = if (isShifted && key.length == 1 && key[0].isLetter()) key.uppercase() else key
                        withEditor {
                            // 直接上屏模式：pendingEnglish 对应字符已逐字落盘，只提交增量键值。
                            service.commitText(finalKey)
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else {
                        val isChinese = !state.isAsciiMode
                        val char = key
                        val japaneseCase = JapaneseTyping.usesKanaCase(state.currentSchemaId, state.isAsciiMode)
                        val keyCode = if (japaneseCase) JapaneseTyping.keyCode(key, isShifted) else if (mergedKey) service.rimeEngine.mergedLetterKeyCode(key[0]) else key.lowercase()[0].code
                        val mask = if (isShifted && !japaneseCase) KeyEvent.META_SHIFT_ON else 0
                        val isLetter = key.matches(Regex("[a-zA-Z]"))
                        val isShiftedChinese = isShifted && isChinese && isLetter && !japaneseCase

                        // 非 ASCII 可打印字符（全角符号/中文标点）：直接上屏，不进入 Rime 引擎。
                        // Rime processKey 只接受标准键码，全角键码（如 U+FF0F）无法识别会被静默
                        // 丢弃，导致中文模式下符号面板点击全角字符无输出（与 Trime onText 行为一致）。
                        if (char.isNotEmpty() && char.any { it.code > 0x7E }) {
                            committedText = char
                            needsUIUpdate = true
                        } else if (isShifted && !isLetter) {
                            if (char.length == 1) {
                                val charCode = char[0].code
                                val processed = service.rimeEngine.processKey(charCode, 0)
                                if (processed) {
                                    val result = service.rimeEngine.getProcessResult(processed)
                                    // commitText 不走 CONFLATED channel：channel 会覆盖丢弃未消费事件，
                                    // 快速打字时中间的 commitText 会被吞（吃键）。
                                    if (result.committedText.isNotEmpty()) {
                                        withEditor { service.commitText(result.committedText) }
                                    }
                                    sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                } else {
                                    committedText = char
                                    needsUIUpdate = true
                                }
                            } else {
                                committedText = char
                                needsUIUpdate = true
                            }
                        } else {
                            // 注：T9 数字键不经过此处（T9KeyboardLayout 直接调
                            // controller.onDigitPressed → applyComposition）。
                            // 大写字母（Shift、Caps Lock 或 Shift 拖线中途在字母区松手）在雾凇系 26 键方案里
                            // 参与组字：字母表含 A–Z，大写串进当前输入（zuogeDNAjiance → 做个DNA检测，
                            // Transformer、KFC 由英文词库和自动大写给出），已打的拼音不被顶上屏。
                            val capitalsInRime = isShiftedChinese && !mergedKey && service.t9PartialSegments.isEmpty() &&
                                (typesEnglishCapitals(state.currentSchemaId) || externalCapitalInRime(char[0]))
                            // 其他方案（双拼、14 键合并键等）没有大写字母编码：已打的字母按原样连同大写字母上屏，
                            // 不丢弃拼音（与桌面端“整串按原样处理”一致）。
                            if (isShiftedChinese && !capitalsInRime && (service.rimeEngine.readQueuedComposition()?.input?.isNotEmpty() == true ||
                                    service.t9PartialSegments.isNotEmpty())) {
                                commitHardwareLiteral(char)
                                return@command
                            }
                            // 打着带数字的 Unicode 码（U2、U26）接着打 a–f 以外的字母：不是在打码，先把它原样上屏，
                            // 字母照常起新的编码（U2 kanle → U2看了）。先看屏上的编码，不是 U 起头就不读引擎。
                            if (isChinese && isLetter && !japaneseCase && !mergedKey && char[0] !in 'a'..'f' &&
                                candState.inputText.firstOrNull() == 'U' && isRimeIceCapitalSchema(state.currentSchemaId) &&
                                service.rimeEngine.readQueuedComposition()?.input?.let(::isUnicodeCodeWithDigit) == true) {
                                if (!commitHardwareLiteral("")) return@command
                            }
                            // 英文单词方案开着「首字母大写」：句首打的第一个字母按大写进组字区，候选跟着首字母大写（Hello），
                            // 和英文直接输入同一规则（#311）；实体键盘、手动 Shift 不在此列，打到一半不再判断。
                            val wordsFirstLetter = isLetter && !mergedKey && typesEnglishWords(state.inputProfile, state.isAsciiMode) &&
                                service.rimeEngine.readQueuedComposition()?.input.isNullOrEmpty()
                            val wordsSentenceCap = wordsFirstLetter && hardwareEvent == null && !isShifted &&
                                SettingsPreferences.getPrefsPublic(service).getBoolean(ENGLISH_SENTENCE_CAPS, false) &&
                                withEditor { capitalizeEnglishHere() }
                            if (wordsFirstLetter) wordsAutoCapped = wordsSentenceCap
                            val result = if (capitalsInRime || wordsSentenceCap) service.rimeEngine.processQueuedKeyAndGetResult(char.uppercase()[0].code, 0)
                                else service.rimeEngine.processQueuedKeyAndGetResult(keyCode, mask)
                            if (result.processed) {
                                if (isShiftedChinese && !capitalsInRime && result.committedText != char) {
                                    service.rimeEngine.clearComposition()
                                    committedText = char
                                    needsUIUpdate = true
                                } else {
                                    val committed = result.committedText
                                    if (state.isAsciiMode && committed.isNotEmpty() && result.inputText.isEmpty() && result.candidates.isEmpty()) {
                                        val current = candState.pendingEnglishText
                                        val newPending = current + committed
                                        service.candidateState.value = service.candidateState.value.copy(pendingEnglishText = newPending)
                                        // 直接上屏模式：只提交 Rime 返回的增量字符（如智能引号转换结果），
                                        // 整段 pending 对应的文本已逐字上屏，不可重复提交。
                                        withEditor {
                                            service.commitText(committed)
                                        }
                                        sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                    } else {
                                        if (committed.isNotEmpty()) {
                                            withEditor { service.commitText(committed) }
                                        }
                                        sendTransformedResult(result, clearPreviousAssociation = deferAssociationClear) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                    }
                                }
                            } else {
                                val isAscii = state.isAsciiMode
                                if (!candState.isComposing || isShiftedChinese) {
                                                    if (isAscii) {
                                                        val charToCommit = if (isShifted) char.uppercase() else char.lowercase()
                                                        val currentPending = candState.pendingEnglishText
                                                        val newPending = currentPending + charToCommit
                                                        // 英文直接上屏模式：字符不经 composing region，即输即落盘；
                                                        // pendingEnglishText 仅作编码记录（供联想与选中候选后回删替换校验）。
                                                        withEditor {
                                                            service.commitText(charToCommit)
                                                        }
                                                        service.candidateState.value = service.candidateState.value.copy(
                                                            pendingEnglishText = newPending,
                                                            associationCandidates = emptyList(),
                                                            englishReplaceSupported = service.supportsEnglishCandidateReplace()
                                                        )
                                                        needsUIUpdate = true
                                    } else {
                                        committedText = char
                                        needsUIUpdate = true
                                    }
                                } else {
                                    val candidateText = if (service.rimeEngine.selectCandidate(0)) {
                                        service.rimeEngine.commit()
                                    } else {
                                        ""
                                    }
                                    committedText = candidateText + char
                                    needsUIUpdate = true
                                }
                            }
                        }
                    }
                }
            }
            
            if (needsUIUpdate) {
                val result = pendingResult
                val textToCommit = committedText
                if (result != null) {
                    if (textToCommit != null) {
                        withEditor { service.commitText(textToCommit) }
                    }
                    sendTransformedResult(result) {
                        if (service.calculatorEngine.isActive()) {
                            updateCalculatorCandidates()
                        }
                    }
                } else {
                    val capturedInputText = service.rimeEngine.getInput()
                    val capturedCandidates = service.rimeEngine.getCandidatesWithComments()
                    val capturedIsAscii = service.rimeEngine.isAsciiMode()
                    val capturedHasNext = service.rimeEngine.hasNextPage()
                    val capturedHasPrev = service.rimeEngine.hasPrevPage()
                    // 候选词变换（hotPath 插件能力）：内联刷新路径（无 RimeProcessResult），
                    // key-processing 线程同步调用；ascii 场景不变换（调用点语义与 transformFor 一致）
                    val transformed = if (capturedInputText.isNotEmpty() && !capturedIsAscii) {
                        service.candidateTransform.transform(
                            capturedInputText, "", capturedCandidates.toList(), capturedIsAscii, firstPage = !capturedHasPrev
                        )
                    } else {
                        null
                    }
                    val displayCandidates: List<com.kingzcheung.xime.rime.RimeCandidate> =
                        transformed?.candidates ?: capturedCandidates.toList()
                    if (textToCommit != null) {
                        withEditor { service.commitText(textToCommit) }
                    }
                    publishUi {
                        val pendingEnglish = service.candidateState.value.pendingEnglishText
                        val (filteredTexts, filteredComments) = if (capturedIsAscii) {
                            val filtered = capturedCandidates.filterNot { candidate ->
                                candidate.text.any { it.code in 0x4E00..0x9FFF }
                            }
                            filtered.map { it.text } to filtered.map { it.comment }
                        } else {
                            displayCandidates.map { it.text } to displayCandidates.map { it.comment }
                        }
                        // 秘密输入框（密码/终端）禁英文联想：联想会泄漏输入前缀，
                        // 回删替换也会破坏受限宿主的输入。NO_SUGGESTIONS 只是宿主
                        // 不要内联补全，候选栏联想仍提供（与退格/applyComposition 路径同口径）。
                        val secret = service.isSecretEditor()
                        service.candidateState.value = service.candidateState.value.copy(
                            inputText = capturedInputText,
                            candidates = filteredTexts,
                            candidateComments = filteredComments,
                            isComposing = capturedInputText.isNotEmpty(),
                            associationCandidates = if (deferAssociationClear || secret || ((capturedIsAscii || !service.isChineseMode) && pendingEnglish.isEmpty())) emptyList() else service.candidateState.value.associationCandidates,
                            isShowingRecentClipboard = false,
                            hasNextPage = capturedHasNext,
                            hasPrevPage = capturedHasPrev,
                            candidateActions = transformed?.actions ?: emptyList()
                        )
                        if (capturedIsAscii != service.uiState.value.isAsciiMode) {
                            FileLogger.i(XimeInputMethodService.TAG, "keyRouter UI refresh: ascii ${service.uiState.value.isAsciiMode}->$capturedIsAscii")
                        }
                        service.uiState.value = service.uiState.value.copy(isAsciiMode = capturedIsAscii)
                        if (pendingEnglish.isNotEmpty() && !secret && service.supportsEnglishCandidateReplace()) {
                            service.serviceScope.launch(InputCommandOwner.requireOwner().context()) {
                                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                                withEditor {
                                    val current = service.candidateState.value
                                    // 在途联想过期校验：pendingEnglish 已变/已清 → 丢弃迟到回填
                                    if (current.pendingEnglishText == pendingEnglish) {
                                        service.candidateState.value = current.copy(associationCandidates = candidates)
                                    }
                                }
                            }
                        }
                        if (service.calculatorEngine.isActive()) {
                            updateCalculatorCandidates()
                        }
                    }
                }
            }
        }
    }

    /** Returns false when the key should continue through ordinary input handling. */
    private suspend fun processHardwareCandidateKey(event: KeyEvent, pageAtBoundary: Boolean = false): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> HardwareCandidateKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> HardwareCandidateKey.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> HardwareCandidateKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> HardwareCandidateKey.DOWN
            KeyEvent.KEYCODE_ESCAPE -> HardwareCandidateKey.CANCEL
            KeyEvent.KEYCODE_SPACE -> HardwareCandidateKey.SPACE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> HardwareCandidateKey.ENTER
            else -> if (hardwareCandidateDigitIndex(event.keyCode, event.isShiftPressed) != null)
                HardwareCandidateKey.DIGIT else return false
        }
        hardwareNavigation.startPress(key, repeat = event.repeatCount > 0)

        // This read waits for the engine lock and never consumes commit text. In
        // particular, a rapid letter followed by an arrow cannot see an old empty UI.
        val composition = service.rimeEngine.readQueuedComposition()
        if (composition != null &&
            service.candidateState.value.engineRevision != composition.engineRevision) {
            val transformed = service.candidateTransform.transform(composition.input, composition.preedit,
                composition.candidates.toList(), composition.isAsciiMode, firstPage = !composition.hasPrevPage)
            val injections = if (isT9Schema(service.uiState.value.currentSchemaId)) {
                service.candidateTransform.transformForT9(com.kingzcheung.xime.rime.RimeProcessResult(
                    true, "", composition.input, composition.preedit, composition.candidates,
                    composition.isAsciiMode, composition.hasNextPage, composition.hasPrevPage,
                    engineRevision = composition.engineRevision,
                )).orEmpty()
            } else emptyList()
            withEditor {
                if (service.rimeEngine.isCandidateRevisionCurrent(composition.engineRevision)) {
                    service.sessionController.applyComposition(
                        transformed?.let { composition.copy(candidates = it.candidates.toTypedArray()) } ?: composition,
                        transformed?.actions ?: emptyList(), injections,
                    )
                }
            }
        }
        val snapshot = withEditor {
            val ui = service.uiState.value
            hardwareCandidateSnapshot(service.candidateState.value,
                ui.isAsciiMode || ui.inputProfile.typesLettersDirectly,
                service.predictionManager.hasPendingPrediction)
        }
        val engineHasInput = composition
            ?.takeIf { service.rimeEngine.isCandidateRevisionCurrent(it.engineRevision) }
            ?.let { it.input.isNotEmpty() || service.t9PartialSegments.isNotEmpty() }
        val digitIndex = hardwareCandidateDigitIndex(event.keyCode, event.isShiftPressed)
        // While pinyin is being composed, physical arrows move through the candidates (as on
        // Samsung Keyboard and Gboard) instead of committing the raw letters and moving the caret.
        val composingArrow = key in listOf(HardwareCandidateKey.LEFT, HardwareCandidateKey.RIGHT,
            HardwareCandidateKey.UP, HardwareCandidateKey.DOWN) &&
            !snapshot.english && snapshot.state.pendingEnglishText.isEmpty() &&
            (engineHasInput == true || snapshot.state.isComposing) && snapshot.words.isNotEmpty()
        if (!pageAtBoundary && composingArrow && (key == HardwareCandidateKey.LEFT || key == HardwareCandidateKey.RIGHT)) {
            if (!isT9Schema(service.uiState.value.currentSchemaId)) {
                // 逐个字母移动，到两端停住；Rime 自带的左右键按音节跳、到头还会绕回另一端。
                val result = service.rimeEngine.moveCompositionCaret(if (key == HardwareCandidateKey.LEFT) -1 else 1)
                if (result != null) {
                    // 拼音条标出光标位置，与屏幕键盘编辑拼音时看到的一致；光标回到末尾时不标。
                    val snap = service.rimeEngine.readPinyinEditSnapshot()
                    val marked = markPreeditCaret(result.preeditText, snap.getOrNull(0).orEmpty(),
                        snap.getOrNull(1)?.toIntOrNull() ?: -1, snap.getOrNull(2)?.toIntOrNull() ?: 0)
                    sendTransformedResult(if (marked != null) result.copy(preeditText = marked) else result)
                }
            }
            hardwareNavigation.retainPress(key)
            return true
        }
        val decision = if (pageAtBoundary || composingArrow) hardwareNavigation.decide(key, snapshot, engineHasInput,
            pageAtBoundary = true, digitIndex = digitIndex)
        // 空格不读工具栏高亮，原因见 physicalCandidateDecision 的说明。
        else physicalCandidateDecision(key, snapshot, engineHasInput, digitIndex)
        if (com.kingzcheung.xime.BuildConfig.DEBUG) {
            Log.d("HardwareNavigation", "key=$key decision=$decision engineInput=$engineHasInput " +
                "words=${snapshot.words.size} compact=${service.uiState.value.isCompact} " +
                "revision=${snapshot.state.engineRevision}")
        }
        if (decision != HardwareCandidateDecision.Host) hardwareNavigation.retainPress(key)
        when (decision) {
            HardwareCandidateDecision.MoveCursor -> {
                if (commitHardwareLiteral("")) {
                    withEditor { service.schemaController.moveHardwareCursor(event) }
                }
            }
            HardwareCandidateDecision.LiteralSpace -> commitHardwareLiteral(" ")
            HardwareCandidateDecision.CommitTyped -> commitHardwareLiteral("")
            is HardwareCandidateDecision.Highlight -> withEditor { publishHardwareCandidateSelection() }
            is HardwareCandidateDecision.Page -> {
                if (snapshot.expanded) {
                    withEditor {
                        service.expandedPageScroll(decision.direction)
                        publishHardwareCandidateSelection()
                    }
                } else {
                    pageHardwareCandidates(snapshot.state, decision.direction)
                    withEditor {
                        publishHardwareCandidateSelection()
                        service.updateUI()
                    }
                }
            }
            is HardwareCandidateDecision.Confirm -> {
                if (decision.association) {
                    withEditor {
                        val current = hardwareCandidateSnapshot(service.candidateState.value, snapshot.english,
                            service.predictionManager.hasPendingPrediction)
                        if (snapshot.sameSource(current)) {
                            selectAssociation(decision.index)
                        }
                    }
                } else if (snapshot.expandedGlobalIndices != null) {
                    selectCandidateGlobalAsync(snapshot.expandedGlobalIndices[decision.index], snapshot.state)
                    withEditor { publishHardwareCandidateSelection() }
                } else {
                    selectCandidateAsync(snapshot.windowStart + decision.index, snapshot = snapshot.state)
                    withEditor { publishHardwareCandidateSelection() }
                }
            }
            HardwareCandidateDecision.Cancel -> {
                // Japanese conversion is separate from Rime composition. Cancel it
                // before clearing input; already committed English text is untouched.
                service.japaneseInputController.handleKey("clear_composition")
                clearInputStateForKeys()
                withEditor {
                    publishHardwareCandidateSelection()
                    service.maybeCollapseCandidatePage()
                }
            }
            // A touch candidate-arrow with no candidates has nothing to navigate.
            HardwareCandidateDecision.Host -> Unit
            HardwareCandidateDecision.Consume -> Unit
            HardwareCandidateDecision.DefaultInput -> {
                if (!snapshot.state.isComposing && snapshot.state.inputText.isEmpty()) {
                    withEditor {
                        service.predictionManager.invalidatePendingPredictions()
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                        hardwareNavigation.clear()
                        publishHardwareCandidateSelection()
                    }
                }
                return false
            }
        }
        return true
    }

    /** Caps Lock while composing commits the letters as typed; the system still toggles the lock. */
    internal fun commitCompositionAsTyped() {
        postRimeJob {
            if (hasPendingCandidateCommit) return@postRimeJob
            if (service.rimeEngine.readQueuedComposition()?.input?.isNotEmpty() == true ||
                service.t9PartialSegments.isNotEmpty()) commitHardwareLiteral("")
        }
    }

    /** On the compact toolbar, page through its windows before turning the engine page. */
    private fun pageHardwareCandidates(shown: CandidateState, direction: Int) {
        if (service.uiState.value.isCompact && hardwareNavigation.moveWindow(shown, direction)) return
        val paged = if (direction < 0) service.rimeEngine.hasPrevPage() && service.rimeEngine.pageUp()
            else service.rimeEngine.hasNextPage() && service.rimeEngine.pageDown()
        if (paged) hardwareNavigation.enginePaged(shown, direction)
    }

    /** 韩语两벌식组字；只在主线程（[withEditor]）里改，别处只看它在不在拼（省掉每次退格切到主线程）。 */
    private val hangul = HangulComposer()

    /**
     * 韩语：字母交给 [HangulComposer]，输入框里光标前那个音节跟着换（替换光标前的字，不用组合区）。
     * 光标前已经不是刚拼的那个音节（点了别处、宿主改了文字）就从这个字母重新拼；不能替换的输入框逐个字母上屏。
     * 不是韩文字母的键结束组字、走原来的路，返回 false。
     */
    private suspend fun routeHangulKey(key: String, isShifted: Boolean): Boolean {
        val typed = key.singleOrNull()?.let { if (isShifted) HangulComposer.SHIFTED[it] ?: it else it }
        if (typed == null || !HangulComposer.isJamo(typed)) {
            if (hangul.isActive) withEditor { hangul.reset() }
            return false
        }
        withEditor {
            service.predictionManager.invalidatePendingPredictions()
            service.candidateState.value = service.candidateState.value.copy(
                associationCandidates = emptyList(), pendingEnglishText = "")
            val previous = hangul.composing
            val done = hangul.input(typed)
            if (previous.isNotEmpty() && service.replaceBeforeCursor(previous, done + hangul.composing)) return@withEditor
            hangul.reset()
            hangul.input(typed)
            if (!service.commitTextAndPredict(hangul.composing, isPaste = false, allowPrediction = false).accepted) hangul.reset()
        }
        return true
    }

    /** 韩语退格：正在拼音节时去掉最后一个字母（각 → 가）；没在拼或光标已离开时交给普通退格。 */
    private suspend fun deleteHangulLetter(): Boolean = hangul.isActive && withEditor {
        val previous = hangul.composing
        if (previous.isEmpty()) return@withEditor false
        hangul.backspace()
        service.replaceBeforeCursor(previous, hangul.composing).also { if (!it) hangul.reset() }
    }

    /**
     * 注音（大千）：键帽上的注音符号换成方案里的大千键码交给 Rime（[ZhuyinKeys]），空格跟在音节后面是第一声。
     * 声调只跟在音节后面：没打字时照原样上屏这个符号，刚打过声调再按声调不收（Rime 会把它当成上屏键）。
     * 大千里数字和 , . - / ; 也是注音键，屏幕上别处送来的这些符号照符号上屏，不当拼写；实体键盘照大千键位打。
     * 处理了返回 true；音节后已有声调的空格、回车、删除和数字键盘等走原来的路。
     */
    private suspend fun routeZhuyinKey(key: String, hardwareEvent: KeyEvent?): Boolean {
        val input = service.rimeEngine.readQueuedComposition()?.input.orEmpty()
        val code = when {
            key == "space" -> if (ZhuyinKeys.takesTone(input)) ' ' else return false
            hardwareEvent == null && key.length == 1 && key[0].code in 0x21..0x7E -> {
                if (service.keyboardViewModel.keyboardState.value is KeyboardLayoutState.Number) return false
                commitHardwareLiteral(service.textCommit.keyboardLiteral(key))
                return true
            }
            else -> ZhuyinKeys.code(key) ?: return false
        }
        if (ZhuyinKeys.isTone(code) && !ZhuyinKeys.takesTone(input)) {
            if (input.isEmpty()) withEditor { service.commitText(key) }
            return true
        }
        val result = service.rimeEngine.processQueuedKeyAndGetResult(code.code, 0)
        if (result.processed) {
            if (result.committedText.isNotEmpty()) withEditor { service.commitText(result.committedText) }
            sendTransformedResult(result)
        } else if (input.isEmpty()) {
            withEditor { service.commitText(key) }
        }
        return true
    }

    /** Finish unfinished raw input without choosing a word; clear only after acceptance. */
    private suspend fun commitHardwareLiteral(suffix: String): Boolean {
        val state = service.candidateState.value
        val composition = service.rimeEngine.readQueuedComposition()
        val hasComposition = composition?.input?.isNotEmpty() == true || state.isComposing ||
            state.inputText.isNotEmpty() || service.t9PartialSegments.isNotEmpty()
        if (hasComposition && composition == null) return false
        val t9 = isT9Schema(service.uiState.value.currentSchemaId)
        // Read the current engine result, not a previous UI frame: rapid typing
        // followed by a shifted symbol must not drop the last letters.
        val preedit = if (t9) com.kingzcheung.xime.rime.buildT9DisplayState(
            service.t9PartialSegments.map { it.text }, composition?.preedit.orEmpty(),
            composition?.input.orEmpty(), emptyList(), emptyList()).displayText
        else composition?.preedit.orEmpty()
        val raw = if (hasComposition) rawCompositionCommitText(composition?.input.orEmpty(), preedit, t9,
            usesMergedLetterKeys(service.uiState.value.inputProfile.layout)) else ""
        val text = raw + suffix
        if (text.isNotEmpty() && !withEditor { service.commitTextAndPredict(text, false, allowPrediction = false).accepted }) return false
        if (text.isNotEmpty()) withEditor {
            if (!service.uiState.value.inputTarget.isInternal)
                service.finishComposingInputBoxKeepingText()
        }
        service.japaneseInputController.handleKey("clear_composition")
        clearInputStateForKeys()
        withEditor {
            hardwareNavigation.clear()
            publishHardwareCandidateSelection()
            service.maybeCollapseCandidatePage()
        }
        return true
    }

    private fun triggerLetterNextWordAssociations(completedWord: String) {
        val cleaned = completedWord.trim()
        if (cleaned.isEmpty() || service.isSecretEditor() || usesDirectFieldInput) {
            service.predictionManager.clearLetterWordContext()
            return
        }
        val state = service.uiState.value
        if (!state.isAsciiMode && !state.inputProfile.typesLettersDirectly) {
            service.predictionManager.clearLetterWordContext()
            return
        }
        val previous = service.predictionManager.lastCompletedLetterWord
        if (previous.isNotEmpty()) {
            service.predictionManager.recordLetterWordPair(previous, cleaned)
        }
        service.predictionManager.lastCompletedLetterWord = cleaned
        val owner = runCatching { InputCommandOwner.requireOwner() }.getOrNull()
        val context = owner?.context() ?: kotlin.coroutines.EmptyCoroutineContext
        service.serviceScope.launch(context) {
            val nextWords = service.predictionManager.getNextWordAssociations(cleaned)
            if (owner != null) withEditor { showLetterNextWords(nextWords) } else showLetterNextWords(nextWords)
        }
    }

    private fun showLetterNextWords(nextWords: List<String>) {
        val current = service.candidateState.value
        if (nextWords.isEmpty() || current.pendingEnglishText.isNotEmpty() || current.isComposing ||
            !service.uiState.value.let { it.isAsciiMode || it.inputProfile.typesLettersDirectly }) return
        keepLetterNextWordContext()
        service.candidateState.value = current.copy(associationCandidates = nextWords)
    }

    /** 联想上下文对上光标前的文字：光标更新时才不会当成上下文变了，把下一词联想清掉（同邮箱后缀）。 */
    private fun keepLetterNextWordContext() {
        runCatching { service.currentInputConnection?.getTextBeforeCursor(25, 0)?.toString() }.getOrNull()
            ?.let(service.predictionManager::replaceCommittedText)
    }

    /** Main-thread commit shared by touch and physical keys; rejection retains the selection. */
    internal fun selectAssociation(index: Int): Boolean {
        if (usesDirectFieldInput) return selectDirectFieldEmailSuffix(index)
        val state = service.candidateState.value
        if (state.isDigitPhraseList) return selectDigitPhrase(state, index)
        if (EmailSuffixes.isSuggestionList(state.associationCandidates))
            associationWordAt(state, index)?.takeIf { it in EmailSuffixes.DOMAINS }?.let { return selectEmailSuffix(it) }
        service.predictionManager.invalidatePendingPredictions()
        englishAutoSpace.disarm()
        // 邮箱后缀：上屏后不补空格，也不接着联想。
        val emailSuffix = EmailSuffixes.isSuggestionList(state.associationCandidates)
        val isLetterMode = service.uiState.value.let {
            (it.isAsciiMode || it.inputProfile.typesLettersDirectly) && !it.inputTarget.isInternal
        }
        // 英文/字母语言选词后自动加空格（默认关）：只跟在英文/字母语言候选词后面，中文联想、邮箱后缀不加。
        val autoSpace = !emailSuffix && isLetterMode &&
            SettingsPreferences.getPrefsPublic(service).getBoolean(EnglishAutoSpace.PREF_KEY, false)
        val pickedWord = associationWordAt(state, index)
        val accepted = commitAssociationSelection(state, index,
            commit = { text ->
                val before = if (isLetterMode && !emailSuffix && state.pendingEnglishText.isEmpty()) {
                    runCatching { service.currentInputConnection?.getTextBeforeCursor(1, 0)?.firstOrNull() }.getOrNull()
                } else null
                val prefixSpace = if (before != null && before.isLetterOrDigit()) " " else ""
                val trailingSpace = if (isLetterMode && !emailSuffix && state.pendingEnglishText.isEmpty() && autoSpace) " " else ""
                service.commitTextAndPredict(prefixSpace + text + trailingSpace, isPaste = false,
                    allowPrediction = !emailSuffix && !SettingsPreferences.isSingleAssociationMode(service)).accepted
            },
            replace = { prefix, completion ->
                if (service.uiState.value.inputTarget.isInternal) service.replaceBeforeCursor(prefix, completion)
                else service.currentInputConnection?.commitEnglishCompletion(prefix, completion) == true
            },
            suffix = if (state.pendingEnglishText.isNotEmpty() && autoSpace) " " else "")
        if (!accepted) return false
        pickedWord?.let(com.kingzcheung.xime.stats.TypingStats::onAssociationPicked)
        if (autoSpace) pickedWord?.let(englishAutoSpace::arm)
        service.candidateState.value = service.candidateState.value.copy(
            pendingEnglishText = "", glidedWord = "", associationCandidates = emptyList())
        hardwareNavigation.clear()
        publishHardwareCandidateSelection()
        if (!emailSuffix && pickedWord != null && isLetterMode && !SettingsPreferences.isSingleAssociationMode(service)) {
            triggerLetterNextWordAssociations(pickedWord)
        }
        return true
    }

    /** Backspace in the tool panel / clipboard search field: the selection, or the character before the caret. */
    private fun deleteInToolPanelField() {
        ToolPanelEditTextHolder.editText?.let { et ->
            val text = et.text ?: return@let
            val range = internalBackspaceRange(text, et.selectionStart, et.selectionEnd) ?: return@let
            text.delete(range.first, range.last + 1)
            try { et.setSelection(range.first) } catch (_: Exception) {}
        }
    }

    /**
     * 「删字后给同音字」（默认关）：这一下退格要删掉的字和删后光标前的文字（主线程调用）。开关关着、英文、密码框、
     * 实体键盘紧凑模式、有选区（退格删的是选区）时不读输入框，返回 null。
     */
    private fun characterAboutToBeDeleted(): Pair<String, String>? {
        val state = service.uiState.value
        if (!SettingsPreferences.isDeleteRecovery(service) || state.isAsciiMode || !service.isChineseMode ||
            state.isCompact || state.inputTarget.isInternal || service.isSecretEditor()) return null
        val ic = service.currentInputConnection ?: return null
        if (runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)) return null
        // 多读两格：删掉的是代理对时，删后剩下的仍有联想上下文那么长（25）。
        val before = runCatching { ic.getTextBeforeCursor(27, 0)?.toString() }.getOrNull()
        if (before.isNullOrEmpty()) return null
        val remaining = before.dropLastCharacter()
        return before.substring(remaining.length) to remaining
    }

    /**
     * 「删掉刚上屏的词不学习」（默认关）：这一下退格（或上滑清空）把 Rime 刚上屏的那段整个删掉时，返回要减去学习的词。
     * 开关关着、没有在跟踪时不读输入框。主线程调用。
     */
    private fun rejectedCommitBefore(clearing: Boolean): String? {
        val tracker = service.rimeEngine.rejectedCommit
        val needed = tracker.neededLength()
        if (needed == 0) return null
        val ic = service.currentInputConnection
        if (!SettingsPreferences.isUnlearnRejectedCommit(service) || service.uiState.value.inputTarget.isInternal || ic == null) {
            tracker.clear()
            return null
        }
        val before = runCatching { ic.getTextBeforeCursor(needed, 0) }.getOrNull()
        if (clearing) return tracker.beforeClear(before)
        val selected = runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(true)
        return tracker.beforeBackspace(before, selected)
    }

    /** 在引擎线程上从用户词库减去刚被删掉的那一次上屏。 */
    private fun unlearnRejectedCommit(word: String) {
        val taken = service.rimeEngine.unlearn("$word\t1")
        Log.d(XimeInputMethodService.TAG, "rejected commit unlearned: $taken")
    }

    /** Each tap keeps its FIFO position; a hold allows only one outstanding repeat. */
    internal fun handleDeleteKey() {
        // Keep this physical action at its original position, including D-D-A and D-A-D.
        val hold = com.kingzcheung.xime.keyboard.RepeatInput.current.get()
        if (hold != null && !hold.acquire()) return
        val queuedAt = if (KeyLatencyProbe.enabled) android.os.SystemClock.uptimeMillis() else 0L
        postRimeJob("delete") {
            val startedAt = if (queuedAt != 0L) android.os.SystemClock.uptimeMillis() else 0L
            try { if (!hasPendingCandidateCommit && hold?.isActive != false) processDeleteKey(hold) }
            finally {
                hold?.completed()
                // 退格排队等了多久（前面有补算、解码时会变长）、自己算了多久；只在打开延迟探针时记。
                if (queuedAt != 0L) android.util.Log.d(KeyLatencyProbe.TAG, "delete queue=${startedAt - queuedAt} " +
                    "run=${android.os.SystemClock.uptimeMillis() - startedAt} repeat=${hold != null}")
            }
        }
    }

    /** 单次退格处理（service.keyProcessingDispatcher 上执行）。 */
    internal suspend fun processDeleteKey(hold: com.kingzcheung.xime.keyboard.RepeatInput?) {
        val owner = service.uiState.value.inputSessionId
        // 退格照常删掉自动补的空格；之后再打标点、空格不再按自动空格处理。
        englishAutoSpace.disarm()
        // 快捷发送表单显示：退格按焦点路由到表单内 EditText（与单击退格同一路径），不进入 Rime
        if (service.uiState.value.showQuickSendForm) {
            withEditor {
                if (owner != service.uiState.value.inputSessionId) return@withEditor
                service.deleteInQuickSendForm()
            }
            return
        }
        // 工具面板/剪贴板搜索框：九键的退格不经 routeKeyPress，没有拼音时由九键控制器直接转到这里。
        // 删框里的字，不能落到后面应用的输入框（用户 10-30：剪贴板搜索只能输入，不能删除）。
        if (service.uiState.value.toolPanelInputFocused) {
            withEditor {
                if (owner != service.uiState.value.inputSessionId) return@withEditor
                deleteInToolPanelField()
            }
            return
        }
        if (usesDirectFieldInput) {
            withEditor {
                service.predictionManager.invalidatePendingPredictions()
                service.candidateState.value = CandidateState()
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            return
        }
        if (service.japaneseInputController.deleteKana()) {
            hold?.allowsDeletion(imeContent = true)
            return
        }
        if (deleteHangulLetter()) {
            hold?.allowsDeletion(imeContent = true)
            return
        }
        // 退格改变输入上下文：使在途的联想预测结果失效，防止过期结果迟到回填
        // associationCandidates，导致长按退格删除时候选栏在"联想词↔空"之间闪动。
        // 先作废再读候选：联想恰好在两步之间送达时，要么算“在等”，要么已经看得到，不会落成删正文。
        val predictionWasPending = service.predictionManager.invalidatePendingPredictions()
        val candState = service.candidateState.value
        val target = deletionTarget(
            engineComposing = service.rimeEngine.compositionActiveForDeletion(),
            uiComposing = candState.isComposing || candState.inputText.isNotEmpty() || candState.preeditText.isNotEmpty(),
            partialSegments = service.t9PartialSegments.isNotEmpty(),
            pendingEnglish = candState.pendingEnglishText.isNotEmpty(),
            // 删字后放回的那一组、打数字给的短语不挡退格：接着删照常删字（见 DeletionRecovery、DigitPhrases）。
            candidatesVisible = (candState.associationCandidates.isNotEmpty() && !candState.isDeletionRecovery &&
                !candState.isDigitPhraseList) ||
                candState.isShowingRecentClipboard,
            predictionPending = predictionWasPending,
        ).skippingAssociation(SettingsPreferences.isDeleteSkipsAssociation(service), candState.isShowingRecentClipboard)
        if (target == DeletionTarget.WAIT) return
        if (hold?.allowsDeletion(target.consumesImeContent) == false) return
        if (target == DeletionTarget.COMPOSITION && chineseGlideDeleteEnabled()) {
            val current = chineseGlideState()
            val lastGlide = chineseGlideDeletes.last(current)
            if (current != null && lastGlide != null && service.rimeEngine.isCandidateRevisionCurrent(current.revision) &&
                service.rimeEngine.applyPinyinEdit(current.input, lastGlide.remainingInput, lastGlide.removeStart,
                    current.confirmed, current.confirmedText, expectedSchema = current.schema)) {
                chineseGlideDeletes.removed(chineseGlideState())
                service.rimeEngine.readQueuedResult()?.let { sendTransformedResult(it) }
                return
            }
        }
        // 计算器模式：追踪退格
        val calculatorWasActive = service.calculatorEngine.isActive()
        service.calculatorEngine.handleDelete()
        // Only replace candidates owned by the calculator. Clearing ordinary pinyin
        // candidates here momentarily empties the list and collapses its expanded page.
        if (calculatorWasActive) updateCalculatorCandidates()

        // 数字/符号键盘：直接发送系统退格，不经过 Rime
        // 防止 T9 残留状态被 Rime 退格修改导致 UI 不一致
        val layoutState = service.keyboardViewModel.keyboardState.value
        if ((layoutState is KeyboardLayoutState.Number || layoutState is KeyboardLayoutState.Symbol) &&
            target == DeletionTarget.DOCUMENT) {
            service.predictionManager.clearLetterWordContext()
            withEditor {
                if (owner != service.uiState.value.inputSessionId) return@withEditor
                val digitsBefore = if (candState.isDigitPhraseList) digitPhraseContext() else null
                // 删过的那串数字不按打的记（接着打时从输入框里重新读整串）。
                numberRun.reset()
                service.sendDeleteKey()
                // 打数字给的短语：按删后剩下的数字重新给。
                if (digitsBefore != null) refreshDigitPhrasesAfterDelete(digitsBefore.dropLastCharacter())
                else if (candState.isDigitPhraseList) service.candidateState.value = service.candidateState.value.copy(
                    associationCandidates = emptyList(), digitPhrases = emptyList(), digitPhraseText = "")
            }
        } else when {
            // 1. 英文待处理文本：逐个删除字符，重新加载联想
            target == DeletionTarget.ENGLISH -> {
                val newPending = candState.pendingEnglishText.dropLast(1)
                if (newPending.isNotEmpty()) {
                    withEditor {
                        if (owner != service.uiState.value.inputSessionId) return@withEditor
                        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = newPending,
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            candidateActions = emptyList()
                        )
                    }
                    if (!service.isSecretEditor() && service.supportsEnglishCandidateReplace()) {
                        service.serviceScope.launch(InputCommandOwner.requireOwner().context()) {
                            val candidates = service.predictionManager.getEnglishAssociations(newPending, PredictionManager.MAX_ASSOCIATION_COUNT)
                            withEditor {
                                if (owner != service.uiState.value.inputSessionId) return@withEditor
                                if (service.candidateState.value.pendingEnglishText == newPending) {
                                    service.candidateState.value = service.candidateState.value.copy(associationCandidates = candidates)
                                }
                            }
                        }
                    }
                } else {
                    service.predictionManager.clearLetterWordContext()
                    withEditor {
                        if (owner != service.uiState.value.inputSessionId) return@withEditor
                        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = "",
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            isShowingRecentClipboard = false,
                            candidateActions = emptyList()
                        )
                    }
                    // 待确认英文已删空：候选展开页若开着则收起（无内容可展示）
                    service.maybeCollapseCandidatePage()
                }
            }

            // 2. Rime 编码中：让 Rime 处理退格，更新候选
            target == DeletionTarget.COMPOSITION -> {
                val result = service.rimeEngine.deleteCompositionAndGetResult()
                if (owner != service.uiState.value.inputSessionId) return
                if (result.inputText.isEmpty()) {
                    // T9 部分提交：剩余编码删完后，已上屏/ composing 的部分候选词无法用
                    // RIME 退格删除，会一直卡在候选栏。这里撤销最近一次部分提交：
                    // 清空自己持有的 composing 区域并从累积列表移除，不删除宿主正文。
                    if (service.t9PartialSegments.isNotEmpty()) {
                        // 已撤销段只存在于输入法状态（候选栏/输入框 composing 的显示），
                        // 从未上屏到正文：候选栏模式不能按段文本长度删宿主文本，
                        // 否则会误删光标前的正文（2026-09-25 真机实证）。
                        val removed = service.t9PartialSegments.rollbackPartialSegments(1)
                        withEditor {
                            if (owner != service.uiState.value.inputSessionId) return@withEditor
                            if (TerminalEditor.inputTextLocation(service)
                                == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
                                service.endComposingInputBox()
                            }
                            if (removed.isNotEmpty()) {
                                service.uiState.value = service.uiState.value.copy(
                                    t9RightCandidateSelectedCount =
                                        (service.uiState.value.t9RightCandidateSelectedCount - removed.size)
                                            .coerceAtLeast(0L),
                                    t9SelectedCandidatePinyin = ""
                                )
                            }
                        }
                        // undo 联动：撤销段时回滚用户词典调频（按段，不按字符）。
                        // Undo changes composition only; no user dictionary write.
                    }
                }
                sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
            }

            // 3. 联想词或剪贴板：仅清空候选栏，不回删已上屏字符
            target == DeletionTarget.CANDIDATES -> {
                service.candidateState.value = service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    associationCandidates = emptyList(),
                    isShowingRecentClipboard = false
                )
                // 候选展开页：联想/剪贴板候选清空后无内容，收起
                service.maybeCollapseCandidatePage()
            }

            // 4. 无候选也无编码：直接回删已上屏文本
            else -> {
                service.predictionManager.clearLetterWordContext()
                service.predictionManager.deleteLastChar()

                var deletedBefore: Pair<String, String>? = null
                var digitsBefore: String? = null
                val rejected = withEditor {

                    if (owner != service.uiState.value.inputSessionId) return@withEditor null
                    val word = rejectedCommitBefore(clearing = false)
                    deletedBefore = characterAboutToBeDeleted()
                    if (candState.isDigitPhraseList) digitsBefore = digitPhraseContext()
                    numberRun.reset()
                    service.sendDeleteKey()
                    word
                }
                rejected?.let(::unlearnRejectedCommit)

                val recovery = deletedBefore?.let { (deleted, remaining) ->
                    val run = DeletionRecovery.nextRun(
                        candState.takeIf { it.isDeletionRecovery }?.deletionRecovery?.firstOrNull(), deleted) ?: return@let null
                    val homophones = if (service.traditionalChineseOutput()) emptyList()
                        else DeletionHomophones.homophones(service, deleted)
                    // 删后的上下文以输入框为准，选区回报时不会把这一组当成上下文变了而清掉。
                    service.predictionManager.replaceCommittedText(remaining)
                    DeletionRecovery.words(run, homophones)
                }.orEmpty()
                service.candidateState.value = service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    associationCandidates = recovery,
                    deletionRecovery = recovery,
                    digitPhrases = emptyList(),
                    digitPhraseText = "",
                    isShowingRecentClipboard = false
                )
                // 打数字给的短语：删字后没有放回的那一组时，按删后剩下的数字重新给。
                if (recovery.isEmpty()) digitsBefore?.let { withEditor { refreshDigitPhrasesAfterDelete(it.dropLastCharacter()) } }
                // 候选展开页：无候选无编码，收起
                service.maybeCollapseCandidatePage()
            }
        }
    }

    /**
     * Posts a rime operation to the shared input FIFO for sequential execution.
     * Ensures no interleaving with key processing.
     */
    internal fun postRimeJob(kind: String = "job", block: suspend CoroutineScope.() -> Unit) {
        val owner = InputCommandOwner.current.get() ?: run {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                "A background input job cannot acquire the current editor"
            }
            commandOwner(service.inputReadiness.ticket() ?: return)
        }
        service.inputCommands.submit(owner.context(), kind = kind) {
            owner.requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
            block()
        }
    }

    suspend fun selectCandidateAsync(index: Int, expandedCandidate: RimeCandidate? = null, snapshot: CandidateState = service.candidateState.value) {
        InputCommandOwner.requireOwner().requireCurrent(service.inputReadiness, service.uiState.value.inputSessionId)
        if (usesDirectFieldInput) return
        if (hasPendingCandidateCommit) return
        val displayed = service.candidateState.value
        if (!snapshot.hasSameSelectionSource(displayed) && !snapshot.stillOffers(displayed, index, expandedCandidate)) {
            // 只记发生次数，用来统计点击因候选已变化而作废的频率；不记候选内容。
            FileLogger.w("InputLatency", "candidate tap dropped: source changed")
            return
        }
        if (service.japaneseInputController.commit(index)) return
        if (!service.rimeEngine.isCandidateRevisionCurrent(snapshot.engineRevision)) {
            FileLogger.w("InputLatency", "candidate tap dropped: revision changed")
            return
        }
        // 展开页点选（expandedCandidate 非空）：取词/注释以展开页显示的同一份
        // 全量列表为准（所见即所得）。index 是全量索引，不得用于索引引擎当前页的
        // candidates/candidateComments——部分选拼音（SELECTION 态）或候选超页时
        // 两列表错位，全局索引套在当前页上会取错词（如点长词只上屏另一短词）。
        // 插件候选 actions 按页内索引记录，展开页索引同样不适用，直接跳过检测。
        val pendingAction = if (expandedCandidate == null) {
            snapshot.candidateActions.getOrNull(index)
        } else {
            null
        }
        pendingAction?.glideInput?.let { input ->
            applyGlideAlternative(input, snapshot)
            return
        }
        if (pendingAction != null && pendingAction.isPluginCandidate) {
            // 防御：中英/方案切换后引擎组合已清空但 candidateState 残留旧候选+actions，
            // 此时点选必须回落原生路径（引擎侧 selectCandidate 失败自动防呆，与旧行为一致），
            // 否则残留插件候选会绕过引擎校验直接上屏。
            val engineHasComposition = service.rimeEngine.getInput().isNotEmpty() ||
                service.rimeEngine.getCandidates().isNotEmpty()
            if (engineHasComposition) {
                if (pendingAction.isPrivate) commitPrivateText(pendingAction.commitText, isT9Schema(service.uiState.value.currentSchemaId))
                else commitSelectedText(pendingAction.commitText)
                return
            }
        }

        val selectedCandidate = expandedCandidate?.text
            ?: if (index < snapshot.candidates.size) {
                snapshot.candidates[index]
            } else null

        val isT9 = isT9Schema(service.uiState.value.currentSchemaId)
        val candidatePinyin = if (isT9) {
            expandedCandidate?.comment
                ?: if (index < snapshot.candidateComments.size) {
                    snapshot.candidateComments[index]
                } else {
                    null
                }
        } else {
            null
        }

        // 在 RIME 真正 select/commit 之前，先同步通知 T9 控制器消费数字。
        // 控制器返回 true 表示输入序列已被该候选词完整消费，服务层应视为 full commit。
        // 传入候选文本用于 C++ (comment, text) 双条件精确定位（注释歧义防错码）。
        val candidateTextLength = selectedCandidate?.length ?: 0
        val fullyConsumed = if (isT9) {
            service.keyboardCallbacks?.onT9RightCandidateWillBeSelected?.invoke(candidatePinyin, selectedCandidate, candidateTextLength, snapshot.engineRevision) ?: return
        } else {
            false
        }

        // T9：跳过 service.rimeEngine.selectCandidate，消费已由 T9 处理器（t9_processor）
        // 独立完成。selectCandidate 会遗留 [confirmed, phony] 残留 composition 状态，
        // 导致后续 forceSendToRime 的 setInput 无法正常重建候选项（对齐 main 分支）。
        // 非 T9 才调用 selectCandidate，并用 resolveRimeCandidateIndex 修正候选 index，
        // 避免 UI 候选被过滤/重排后上屏错词。
        val selection = if (isT9) {
            null
        } else {
            val rimeIndex = if (pendingAction != null && pendingAction.engineIndex >= 0) {
                // 变换映射记录的引擎候选索引（比按显示文本回查更准）
                pendingAction.engineIndex
            } else if (selectedCandidate != null) {
                resolveRimeCandidateIndex(index, selectedCandidate, service.rimeEngine.getCandidates().toList())
            } else {
                index
            }
            service.rimeEngine.selectCandidateAtRevision(rimeIndex, snapshot.engineRevision) ?: return
        }
        com.kingzcheung.xime.stats.TypingStats.onCandidatePicked(index,
            selectedCandidate?.let { snapshot.correctedCandidates[it] }.orEmpty())

        // T9：跳过 service.rimeEngine.commit()，用用户点选的 selectedCandidate 作为权威上屏文本。
        val committedText = selection?.committedText.orEmpty()
        // T9 模式下 fullyConsumed 是判断 full/partial commit 的唯一权威：
        // 当控制器明确 partial commit 时，即使 RIME commit() 返回非空文本
        // （RIME 内部做了 partial commit），也不应走 full commit 路径。
        val isFullCommit = if (isT9) {
            fullyConsumed && selectedCandidate != null
        } else {
            committedText.isNotEmpty()
        }
        if (isFullCommit) {
            // T9 full commit：以用户点选的候选词文本为权威上屏文本；
            // 非 T9 仍用 RIME committedText（可能含简繁转换等处理）。
            val textToMerge = if (isT9 && selectedCandidate != null) {
                selectedCandidate
            } else if (committedText.isNotEmpty()) {
                committedText
            } else {
                selectedCandidate!!
            }
            // T9 full commit：partial commit 累积文本（未单独上屏，只存在于
            // service.t9PartialSegments 的 composing 区）与本次上屏文本是独立词，必须拼接，
            // 不能去重（mergePartialCommitText 仅用于显示，会吞掉重复的词）。
            // 但若点击的是 partial 词本身（RIME 无菜单、候选栏显示的就是 partial 词，
            // 无拼音注释 candidatePinyin==null），则只提交累积的 partial 文本，避免重复。
            val partialTexts = service.t9PartialSegments.map { it.text }
            val fullCommitText = if (isT9 && partialTexts.isNotEmpty()) {
                if (candidatePinyin == null) {
                    partialTexts.joinToString("")
                } else {
                    partialTexts.joinToString("") + textToMerge
                }
            } else {
                textToMerge
            }
            // 英文单词方案开着「选词后自动加空格」：点候选或按空格上屏的词后面跟一个空格，接着的键按 [EnglishAutoSpace] 处理（#311）。
            val autoSpace = !isT9 && wordsAutoSpaceAfter(fullCommitText)
            val delivered = deliverCandidateCommit(PendingCandidateCommit(
                InputCommandOwner.requireOwner(), if (autoSpace) "$fullCommitText " else fullCommitText, isT9
            ))
            if (autoSpace && delivered.accepted) englishAutoSpace.arm(fullCommitText)
        } else {
            withEditor {
                if (isT9) {
                    // partial commit：累积候选文本与拼音，供合并显示与 full commit 调频拼接。
                    if (selectedCandidate != null) {
                        service.t9PartialSegments.add(T9PartialSegment(selectedCandidate, candidatePinyin ?: ""))
                    }
                    // 保留状态字段，供 UI 层感知右侧选词事件
                    service.uiState.value = service.uiState.value.copy(
                        t9RightCandidateSelectedCount = service.uiState.value.t9RightCandidateSelectedCount + 1,
                        t9SelectedCandidatePinyin = candidatePinyin ?: ""
                    )
                } else {
                    service.updateUI()
                }
            }
            // Rebuild remaining code on the worker, within this selection's FIFO slot.
            if (isT9) service.keyboardCallbacks?.onT9ForceSendToRime?.invoke()
        }
    }

    /**
     * 插件候选直接上屏（所见即所得）：不经引擎 select/commit。
     * 先上屏并清空候选状态（与 enter 提交分支同序），再清引擎组合态。
     */
    internal suspend fun commitSelectedText(text: String) {
        deliverCandidateCommit(PendingCandidateCommit(
            InputCommandOwner.requireOwner(), text, false
        ))
    }

    /**
     * 通讯录的人名、号码上屏：不进 Rime 词库（不经引擎选词）、不记联想（不记上下文，下一次上屏也不和它配对）、
     * 不进打字统计、不发插件事件，上屏后不出联想。
     */
    internal suspend fun commitPrivateText(text: String, t9: Boolean) {
        deliverCandidateCommit(PendingCandidateCommit(InputCommandOwner.requireOwner(), text, t9, learnSelection = false,
            isPrivate = true))
    }

    /** A rejected commit retains its original owner and text. Retry is an explicit user action. */
    internal suspend fun commitPreparedText(text: String, t9: Boolean, caret: CommitCaret = CommitCaret.NONE,
        isPrivate: Boolean = false): TextCommitResult =
        deliverCandidateCommit(PendingCandidateCommit(InputCommandOwner.requireOwner(), text, t9, learnSelection = false,
            caret = caret, isPrivate = isPrivate))

    private suspend fun deliverCandidateCommit(pending: PendingCandidateCommit): TextCommitResult =
        pending.deliver(
            submit = { text -> withEditor {
                if (pending.isPrivate) return@withEditor service.submitPrivateText(text, pending.caret)
                val previousText = service.predictionManager.lastCommittedText
                val outcome = service.submitText(text, pending.caret)
                if (outcome.accepted) {
                    if (pending.learnSelection && previousText.isNotEmpty() && service.isChineseMode &&
                        SettingsPreferences.isSmartPredictionEnabled(service) && AssociationManager.isInitialized()) {
                        service.predictionManager.recordInputPair(previousText.last().toString(), text)
                    }
                }
                outcome
            } },
            rejected = { withEditor {
                pendingCandidateCommit = it
                service.uiState.value = service.uiState.value.copy(rejectedCommitText = it.text)
                FileLogger.w("InputLatency", "candidate commit not accepted: retained for retry")
            } },
            accepted = {
                // Learning failure is diagnostic; it must never undo accepted text.
                if (it.t9 && it.learnSelection && !service.rimeEngine.t9MemorizeSelection(it.text)) {
                    FileLogger.w("ImeKeyRouter", "Selected T9 phrase was committed but learning failed (missing captured code or dictionary write failure)")
                }
                if (it.t9) service.rimeEngine.clearQueuedT9Composition() else service.rimeEngine.clearQueuedComposition()
                withEditor {
                    discardPendingCandidateCommit()
                    service.keyboardViewModel.setCandidatePageExpanded(false)
                    service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                    service.t9PartialSegments.clear()
                    service.candidateState.value = service.candidateState.value.copy(
                        inputText = "", preeditText = "", pendingEnglishText = "",
                        candidates = emptyList(), candidateComments = emptyList(),
                        candidateActions = emptyList(), expandedCandidates = emptyList(),
                        expandedCandidatesLoaded = false, isComposing = false,
                        hasNextPage = false, hasPrevPage = false, isShowingRecentClipboard = false,
                    )
                    service.uiState.value = service.uiState.value.copy(
                        t9RightCandidateSelectedCount = 0, t9SelectedCandidatePinyin = ""
                    )
                }
            },
        )
    
    /**
     * 输入态判定（clear_all 与 undo_clear 共用）。
     *
     * 输入态 = 存在未上屏内容：RIME 组合（candidateState 派生字段）或英文输入
     *（pendingEnglishText 非空——英文按键不经 RIME，故 inputText/preeditText/isComposing 恒空，
     * 漏判会让 clear_all 把 composing 区文本与 pendingEnglishText 重复拼接）
     * 或 T9 partial 累积。
     * 不能用 RIME getInput()——tryLocked 锁竞争时静默返回空，会误判空闲态。
     */
    private fun hasInputState(candState: CandidateState, includeAssociation: Boolean = true): Boolean =
        candState.isComposing ||
            candState.inputText.isNotEmpty() ||
            candState.preeditText.isNotEmpty() ||
            candState.pendingEnglishText.isNotEmpty() ||
            service.t9PartialSegments.isNotEmpty() ||
            service.rimeEngine.compositionActiveForDeletion() != false ||
            (includeAssociation && candState.associationCandidates.isNotEmpty() && !candState.isDeletionRecovery && !candState.isDigitPhraseList) ||
            candState.isShowingRecentClipboard ||
            (includeAssociation && service.predictionManager.hasPendingPrediction)

    /** Ends the composition in the input FIFO, T9's own digit state included (see [com.kingzcheung.xime.rime.RimeEngine.endQueuedComposition]). */
    internal fun endQueuedComposition() =
        service.rimeEngine.endQueuedComposition(isT9Schema(service.uiState.value.currentSchemaId))

    /**
     * 拼音写在输入框里时，用户把光标点到了编码以外（11-08 红米 K70 反馈：光标挪到中间按删除，删的是末尾）。
     * 原来组词照旧，下一下删除删的是末尾编码的字母，再写回编码时光标被拉回末尾。
     * 这里结束组词：编码字母原样留在输入框里成为普通文字（不替用户删掉看得见的字），候选和引擎清空，
     * 之后的按键作用在新光标处。排进输入队列，和按键保持先后。
     */
    internal fun finishCompositionAfterCaretLeft() {
        postRimeJob("caret_left_composition") { clearInputStateForKeys(keepInlineText = true) }
    }

    /**
     * 清空输入态（预编辑/候选/联想/partial 累积/计算器），不动已上屏文本。
     * 供 clear_composition 与 clear_all 输入态分支共用。
     *
     * 显式清 preeditText（与提交路径"残留根治"一致），不依赖 updateUI 自愈；
     * 输入框模式清 composing 区；T9 方案重置左侧候选区（其他键盘无左栏，跳过）。
     */
    private suspend fun clearInputStateForKeys(keepInlineText: Boolean = false) {
        service.predictionManager.invalidatePendingPredictions()
        service.predictionManager.clearLetterWordContext()
        service.calculatorEngine.clear()
        updateCalculatorCandidates()
        service.t9PartialSegments.clear()
        endQueuedComposition()
        service.candidateState.value = service.candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            inputText = "",
            preeditText = "",
            isComposing = false,
            isShowingRecentClipboard = false,
            candidateActions = emptyList(),
            expandedCandidates = emptyList(),
            expandedCandidatesLoaded = false,
            hasNextPage = false,
            hasPrevPage = false,
            candidateFocus = null
        )
        if (TerminalEditor.inputTextLocation(service) ==
            SettingsPreferences.INPUT_TEXT_INPUT_BOX
        ) {
            withEditor { if (keepInlineText) service.finishComposingInputBoxKeepingText() else service.endComposingInputBox() }
        }
        if (isT9Schema(service.uiState.value.currentSchemaId)) {
            withEditor {
                service.keyboardCallbacks?.onT9CompositionCleared?.invoke()
                service.uiState.value = service.uiState.value.copy(
                    t9RightCandidateSelectedCount = 0,
                    t9SelectedCandidatePinyin = ""
                )
            }
        }
    }

    /**
     * 更新计算器候选栏显示
     * 显示两个候选：
     * - index 0: 计算结果（如 "2"），点击直接替换为结果
     * - index 1: 带公式的结果（如 "1+1=2"），点击显示公式和结果
     */
    internal fun updateCalculatorCandidates() {
        val candidate = service.calculatorEngine.getCandidate()
        val result = service.calculatorEngine.getResult()
        service.candidateState.value = if (candidate != null && result.isNotEmpty()) {
            service.candidateState.value.copy(
                candidates = listOf(result, candidate),
                candidateComments = emptyList(),
                calculatorCandidates = listOf(result, candidate),
            )
        } else {
            // 如果计算器之前有显示但现在已清除，也要清空候选栏
            if (service.candidateState.value.candidates.isNotEmpty() && !service.calculatorEngine.isActive()) {
                service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    calculatorCandidates = emptyList(),
                )
            } else {
                service.candidateState.value
            }
        }
    }

    /** Main-thread display-only action. No engine mutation, buffer consumption or host commit. */
    internal fun highlightNextCandidate(snapshot: CandidateState) = highlightCandidate(snapshot, forward = true)

    internal fun highlightPreviousCandidate(snapshot: CandidateState) = highlightCandidate(snapshot, forward = false)

    private fun highlightCandidate(snapshot: CandidateState, forward: Boolean) {
        if (service.inputReadiness.ticket() == null || hasPendingCandidateCommit) return
        val current = service.candidateState.value
        if (!snapshot.hasSameSelectionSource(current) || snapshot.associationCandidates != current.associationCandidates ||
            (snapshot.isComposing && !service.rimeEngine.isCandidateRevisionCurrent(snapshot.engineRevision))) return
        val profile = service.uiState.value.inputProfile
        service.candidateState.value = if (forward) current.advanceT9Candidate(profile)
            else current.retreatT9Candidate(profile)
        service.sessionController.refreshInputBoxComposition()
    }

    internal fun selectCandidate(index: Int) {
        if (usesDirectFieldInput && !service.candidateState.value.isShowingRecentClipboard) return
        service.composeViewRef?.let { service.feedbackManager.performKeyPressEffect(view = it) }

        // 计算器模式（仅在数字/常用符号键盘下生效，防止状态残留导致其他键盘候选词点击异常）
        val layoutState = service.keyboardViewModel.keyboardState.value
        val isCalculatorKeyboard = layoutState is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                || layoutState is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.CommonSymbol
        if (service.calculatorEngine.isActive() && isCalculatorKeyboard) {
            val result = service.calculatorEngine.getResult()
            val expression = service.calculatorEngine.getExpression()
            val formulaResult = service.calculatorEngine.getFormulaResult()
            if (result.isNotEmpty() && expression.isNotEmpty()) {
                // index 0: 纯结果（如 "2"）
                // index 1: 公式结果（如 "1+1=2"）
                val textToCommit = when (index) {
                    0 -> result
                    1 -> formulaResult
                    else -> return
                }
                val displayed = service.candidateState.value.candidates
                if (displayed == listOf(result, service.calculatorEngine.getCandidate()) &&
                    service.editorConnection()?.commitCalculatorCandidate(expression, textToCommit) == true) {
                    service.calculatorEngine.clear()
                    service.candidateState.value = CandidateState()
                }
            }
            return
        }
        
        val recentClipboard = service.recentClipboardItemsState.value
        val clipboardChoice = if (service.candidateState.value.isShowingRecentClipboard)
            com.kingzcheung.xime.clipboard.ClipboardLinkChoices.expandItems(recentClipboard).getOrNull(index) else null
        if (clipboardChoice != null) {
            // 干净链接、BV 号也算用掉了那一条：按原文标记，下次不再出现在候选栏。
            service.textCommit.selectClipboardItem(clipboardChoice.text, source = recentClipboard[clipboardChoice.source].text)
            service.candidateState.value = service.candidateState.value.copy(
                isShowingRecentClipboard = false,
                candidates = emptyList(),
                candidateComments = emptyList()
            )
        } else {
            val snapshot = service.candidateState.value
            if (commitHeldCandidateEnglish(snapshot, index)) return
            postRimeJob("select") {
                selectCandidateAsync(index, snapshot = snapshot)
            }
        }
    }

    /** 中文键盘且不在临时英文状态。物理键盘下 isChineseMode 依赖屏幕键盘上报，改看输入方案的语言。 */
    private fun chineseKeyboardActive(): Boolean = service.uiState.value.let {
        !it.isAsciiMode && it.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE
    }

    /** Read the Shift/drag state on release, before the gesture clears it. Resolve the displayed item's source. */
    private fun commitHeldCandidateEnglish(snapshot: CandidateState, index: Int, global: Boolean = false): Boolean {
        val screenShiftHeld = service.keyboardViewModel.shiftMode.value == com.kingzcheung.xime.viewmodel.ShiftMode.HELD
        if (!com.kingzcheung.xime.gloss.CandidateGlossary.swapHeld(screenShiftHeld) || !chineseKeyboardActive()) return false
        val english = snapshot.englishForHeldSelection(index, global) ?: return false
        val expanded = if (global) snapshot.expandedCandidates[index] else null
        val t9 = isT9Schema(service.uiState.value.currentSchemaId)
        postRimeJob("select") {
            if (snapshot.stillOffers(service.candidateState.value, index, expanded)) commitPreparedText(english, t9)
        }
        com.kingzcheung.xime.gloss.CandidateGlossary.t9NextHeld.value = false
        if (screenShiftHeld) service.keyboardViewModel.onCharacterTyped()
        return true
    }

    /**
     * 物理键盘按住 Shift 加数字或空格选候选（释义词典已加载、中文正在组词时）：有英文上屏英文，
     * 没有照常上中文。按编码处理完后的候选来定位，与不按 Shift 时选的是同一条。返回是否接管了这个键。
     */
    internal fun selectHardwareCandidateWithShift(keyCode: Int): Boolean {
        val before = service.candidateState.value
        if (!takesOverShiftedCandidateKey(keyCode, shiftPressed = true, chinese = chineseKeyboardActive(),
                glossLoaded = com.kingzcheung.xime.gloss.CandidateGlossary.isLoaded,
                composingWithCandidates = before.isComposing && before.candidates.isNotEmpty())) return false
        postRimeJob("select") {
            val (state, index) = withEditor {
                val current = service.candidateState.value
                current to hardwareWindowStart(current) + shiftedCandidateOffset(keyCode) {
                    hardwareCandidateHighlight(current, english = false,
                        predictionPending = service.predictionManager.hasPendingPrediction)
                }!!
            }
            val word = state.candidates.getOrNull(index) ?: return@postRimeJob
            val english = shiftSelectionEnglish(word, com.kingzcheung.xime.gloss.CandidateGlossary::englishFor)
            if (english != null) commitPreparedText(english, isT9Schema(service.uiState.value.currentSchemaId))
            else selectCandidateAsync(index, snapshot = state)
        }
        return true
    }

    /**
     * 长按删除 = 忘掉这条候选的学习记录（所有方案一致）：词库里本来有的词回到原位，
     * 自造词消失，整句忘掉句里学过的词；没有学习记录时只提示，排序不变。
     * 显示索引经 resolveRimeCandidateIndex 映射回引擎原始索引（插件候选
     * 注入会使两者错位，与 selectCandidateAsync 同口径），之后刷新候选。
     */
    internal fun deleteCandidate(displayIndex: Int) {
        val snapshot = service.candidateState.value
        postRimeJob {
            if (!snapshot.hasSameSelectionSource(service.candidateState.value)) return@postRimeJob
            val text = snapshot.candidates.getOrNull(displayIndex)
            if (text.isNullOrEmpty()) return@postRimeJob
            if (forgetEnglishWord(text)) return@postRimeJob
            val engineIndex = resolveRimeCandidateIndex(
                displayIndex, text, service.rimeEngine.getCandidates().toList()
            )
            val result = service.rimeEngine.forgetCandidateAtRevision(engineIndex, snapshot.engineRevision)
            FileLogger.i(
                XimeInputMethodService.TAG,
                "ForgetCandidate: text='$text' display=$displayIndex engine=$engineIndex result=$result"
            )
            if (result != 0) {
                withEditor {
                    service.updateUI()
                }
                showForgetResult(text, result)
            }
        }
    }
    
    internal fun pageDown() {
        postRimeJob {
            service.rimeEngine.pageDown()
            withEditor {
                service.updateUI()
            }
        }
    }
    
    internal fun pageUp() {
        postRimeJob {
            service.rimeEngine.pageUp()
            withEditor {
                service.updateUI()
            }
        }
    }

    /**
     * 候选展开页点选：按跨页全局索引选词（select_candidate，区别于候选栏的
     * 当前页内索引 select_candidate_on_current_page）。
     *
     * 关键：rime 的 select 只是把候选放进内部 commit 缓冲，宿主必须再调
     * [RimeEngine.commit]（get_commit）拉取文本并自行上屏——只刷新 composition
     * 拿不到上屏内容（编码已消费、字却丢失，即此前"展开页点选无法上屏"的根因）。
     */
    internal fun selectCandidateGlobal(globalIndex: Int) {
        // 点选震动反馈与候选栏同源（performKeyPressEffect），保证手感一致
        service.composeViewRef?.let { service.feedbackManager.performKeyPressEffect(view = it) }
        // 入队前先按全局索引取全量候选：调用线程读到的 expandedCandidates 正是
        // UI 渲染的同一份列表；入队后再取可能被编码刷新清空/重建而扑空
        val snapshot = service.candidateState.value
        if (commitHeldCandidateEnglish(snapshot, globalIndex, global = true)) return
        postRimeJob("select") {
            selectCandidateGlobalAsync(globalIndex, snapshot)
        }
    }

    private suspend fun selectCandidateGlobalAsync(globalIndex: Int, snapshot: CandidateState) {
        if (usesDirectFieldInput) return
            val expandedCandidate = snapshot.expandedCandidates.getOrNull(globalIndex) ?: return
            if (!snapshot.stillOffers(service.candidateState.value, globalIndex, expandedCandidate)) return
            if (service.japaneseInputController.commit(globalIndex)) return
            if (!service.rimeEngine.isCandidateRevisionCurrent(snapshot.engineRevision)) return
            // T9 方案的选词消费由 t9_processor 独立完成，直接调引擎 select 会
            // 遗留 [confirmed, phony] 残留组合态（见 selectCandidateAsync 注释），
            // 降级走候选栏同款路径；取词/注释必须用展开页同一份全量候选——
            // 全局索引与引擎当前页列表错位时会取错词/丢字
            if (isT9Schema(service.uiState.value.currentSchemaId)) {
                selectCandidateAsync(globalIndex, expandedCandidate, snapshot)
                return
            }
            val selection = service.rimeEngine.selectCandidateAtRevision(globalIndex, snapshot.engineRevision, global = true) ?: return
            com.kingzcheung.xime.stats.TypingStats.onCandidatePicked(globalIndex,
                snapshot.correctedCandidates[expandedCandidate.text].orEmpty())
            val committedText = selection.committedText
            if (committedText.isNotEmpty()) {
                val autoSpace = wordsAutoSpaceAfter(committedText)
                val delivered = deliverCandidateCommit(PendingCandidateCommit(
                    InputCommandOwner.requireOwner(), if (autoSpace) "$committedText " else committedText, false
                ))
                if (autoSpace && delivered.accepted) englishAutoSpace.arm(committedText)
            } else {
                // 引擎未产生 commit（选中后继续组句的多段场景）：刷新组合态
                withEditor {
                    service.updateUI()
                }
            }
    }

    /**
     * 长按首选整句、点了其中第 [target] 个词：只重选这个词。前面的词照原样选定（九键走九键选词，
     * 其它方案在引擎里一次做完），光标放到这个词后面，候选只剩这个词的其它写法；选好后后面的字照常组句。
     */
    internal fun reselectSentenceWord(words: List<String>, target: Int) {
        val snapshot = service.candidateState.value
        if (words.size < 2 || target !in words.indices || snapshot.candidates.firstOrNull() != words.joinToString("")) return
        postRimeJob("reselect") { reselectSentenceWordAsync(words, target, snapshot) }
    }

    private suspend fun reselectSentenceWordAsync(words: List<String>, target: Int, snapshot: CandidateState) {
        if (usesDirectFieldInput || hasPendingCandidateCommit) return
        if (!snapshot.hasSameSelectionSource(service.candidateState.value) ||
            !service.rimeEngine.isCandidateRevisionCurrent(snapshot.engineRevision)) return
        val schema = service.uiState.value.currentSchemaId
        if (!isT9Schema(schema)) {
            val result = service.rimeEngine.reselectSentenceWord(words, target, snapshot.engineRevision) ?: return
            withEditor { service.sessionController.updateUIWithResult(result) }
            return
        }
        // 九键：前面的词逐个走和点候选一样的九键选词（消耗数字、记进已选的词），再挪光标。
        for (i in 0 until target) {
            val word = words[i]
            val revision = service.rimeEngine.currentCandidateRevision()
            val pinyin = service.rimeEngine.candidateReading(word)?.takeIf { it.isNotBlank() } ?: break
            val full = service.keyboardCallbacks?.onT9RightCandidateWillBeSelected?.invoke(pinyin, word, word.length, revision)
                ?: return
            if (full) {
                // 不会发生（后面还有要重选的词）；万一数字已全部用完，照点候选的规矩整段上屏，不丢字。
                deliverCandidateCommit(PendingCandidateCommit(InputCommandOwner.requireOwner(),
                    service.t9PartialSegments.joinToString("") { it.text } + word, true))
                return
            }
            withEditor {
                service.t9PartialSegments.add(T9PartialSegment(word, pinyin))
                service.uiState.value = service.uiState.value.copy(
                    t9RightCandidateSelectedCount = service.uiState.value.t9RightCandidateSelectedCount + 1,
                    t9SelectedCandidatePinyin = pinyin)
            }
            service.keyboardCallbacks?.onT9ForceSendToRime?.invoke()
        }
        val doublePinyin = com.kingzcheung.xime.settings.InputProfiles.describe(schema).scheme ==
            com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN
        val result = service.rimeEngine.placeT9CaretAfterWord(words[target], doublePinyin)
        withEditor {
            service.keyboardCallbacks?.onT9RefreshAfterPreeditEdit?.invoke()
            if (result != null) service.sessionController.updateUIWithResult(result)
        }
    }

    /** 展开页使用同一原生候选版本与全局索引执行反馈，再重读候选。 */
    internal fun deleteCandidateGlobal(globalIndex: Int) {
        val snapshot = service.candidateState.value
        postRimeJob {
            if (!snapshot.hasSameSelectionSource(service.candidateState.value)) return@postRimeJob
            val text = snapshot.expandedCandidates.getOrNull(globalIndex)?.text
            if (text.isNullOrEmpty()) return@postRimeJob
            if (forgetEnglishWord(text)) return@postRimeJob
            val result = service.rimeEngine.forgetCandidateAtRevision(globalIndex, snapshot.engineRevision, global = true)
            FileLogger.i(
                "ImeKeyRouter",
                "forgetCandidateGlobal: text='$text' index=$globalIndex result=$result"
            )
            if (result == 0) return@postRimeJob
            withEditor {
                service.updateUI()
            }
            showForgetResult(text, result)
        }
    }

    /**
     * 长按「固定」：这个词在当前编码下排第一，固定多个按先后排；「取消固定」回到原位。
     * 按文字固定，存 cyime_pins.txt（与 Windows 版同一格式）；不在引擎候选里的词（插件注入）固定不了，只提示。
     */
    internal fun pinCandidate(text: String, pinned: Boolean) {
        val snapshot = service.candidateState.value
        postRimeJob {
            if (!snapshot.hasSameSelectionSource(service.candidateState.value)) return@postRimeJob
            val done = service.rimeEngine.setCandidatePinnedAtRevision(text, pinned, snapshot.engineRevision)
            FileLogger.i(XimeInputMethodService.TAG, "PinCandidate: text='$text' pinned=$pinned result=$done")
            if (done) {
                withEditor { service.updateUI() }
            } else if (pinned) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(service, "「$text」不能固定", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** A remembered hand-typed English word (lua/cyime_en_memory.lua) has no Rime record: forget it from its file. */
    private suspend fun forgetEnglishWord(text: String): Boolean {
        if (!typesEnglishCapitals(service.uiState.value.currentSchemaId)) return false
        val rimeDir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(service)
        if (!com.kingzcheung.xime.settings.EnglishWordMemory.forget(rimeDir, text)) return false
        // Translate the same input again so the forgotten word leaves the menu.
        service.rimeEngine.setInput(service.rimeEngine.getInput())
        withEditor { service.updateUI() }
        showForgetResult(text, 3)
        return true
    }

    /**
     * 忘掉学习的结果提示：用户看不到词库内部状态，只有这句话说明发生了什么。
     * result 同 forgetCandidate：1 忘掉后仍是词库里的词、2 没有学习记录、3 忘掉后不再出现（自造词）。
     */
    private fun showForgetResult(text: String, result: Int) {
        val message = when (result) {
            1 -> "已忘掉「$text」的学习记录，回到词库原来的位置"
            3 -> "已删除自造的「$text」，以后再用会重新记住"
            else -> "「$text」没有学习记录，不会删掉，排序不变"
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(service, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

}

/** 物理键盘 Shift 加数字（1…9、0）或空格对应的候选位置，相对工具栏当前窗口；空格取高亮项，其它键为 null。 */
internal fun shiftedCandidateOffset(keyCode: Int, highlight: () -> Int): Int? =
    if (keyCode == KeyEvent.KEYCODE_SPACE) highlight() else hardwareCandidateDigitIndex(keyCode, shifted = false)

/**
 * 物理键盘带 Shift 的键是否由“按住 Shift 选候选”接管：只在中文组词、有候选时。Shift+空格总是接管
 * （否则它被当成“输入一个空格”：先上中文首选、再多补一个空格）；Shift+数字只在释义词典生效时接管，
 * 平时仍输入 ! @ # 等符号。没按 Shift 的键一律不接管，行为不变。
 */
internal fun takesOverShiftedCandidateKey(
    keyCode: Int, shiftPressed: Boolean, chinese: Boolean, glossLoaded: Boolean, composingWithCandidates: Boolean,
): Boolean = shiftPressed && chinese && composingWithCandidates &&
    (keyCode == KeyEvent.KEYCODE_SPACE || glossLoaded && hardwareCandidateDigitIndex(keyCode, shifted = false) != null)

/** 按住 Shift 选中的候选要上屏的英文；null 表示照常上屏中文。两种情况都只上屏这个词本身，不附加空格。 */
internal fun shiftSelectionEnglish(candidate: String, englishFor: (String) -> String?): String? = englishFor(candidate)
