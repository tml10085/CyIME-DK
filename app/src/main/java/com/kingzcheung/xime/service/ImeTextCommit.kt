package com.kingzcheung.xime.service

import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kingzcheung.xime.platform.AndroidPlatform

/**
 * 文本上屏与剪贴板提交。
 *
 * 承载 commitImage（图片上屏）、剪贴板候选提交（selectClipboardItem/commitClipboardText/
 * deleteClipboardChars）与语音撤销/搜索动作（performUndo/performSearch）。
 * 共享状态通过 service 引用访问。
 */
internal class ImeTextCommit(private val service: XimeInputMethodService) {
    internal suspend fun pasteClipboardImage(image: com.kingzcheung.xime.clipboard.ClipboardImage): Boolean? {
        val connection = service.currentInputConnection ?: return null
        val editor = service.currentInputEditorInfo ?: return null
        val session = service.uiState.value.inputSessionId
        if (service.uiState.value.inputTarget.isInternal) return null
        service.dismissPredictionsForPaste()
        val cached = service.clipboardManager.images.prepare(image)
        if (service.uiState.value.inputSessionId != session || service.currentInputConnection !== connection) return null
        val uri = Uri.parse(cached.uri)
        runCatching { service.grantUriPermission(editor.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val content = InputContentInfo(uri, android.content.ClipDescription("图片", arrayOf(cached.mimeType)), null)
        val accepted = ImagePasteProtocol.commit(connection, editor, content)
        if (accepted) {
            service.clipboardManager.images.dismissPreview(image.uri)
            service.clipboardManager.images.dismissPreview(cached.uri)
        }
        else {
            service.clipboardManager.copyImageUriToSystemClipboard(cached)
            service.clipboardManager.images.reportPasteFailure(cached, editor.packageName)
        }
        return accepted
    }
    internal fun performUndo() {
        val currentTextBeforeCursor = service.currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val currentLength = currentTextBeforeCursor.length
        
        val charsToDelete = currentLength - service.voiceRecognitionHandler.textLengthBeforeVoiceInput
        
        if (charsToDelete > 0) {
            for (i in 0 until charsToDelete) {
                service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        }
        
        service.voiceRecognitionHandler.textBeforeVoiceInput = ""
        service.voiceRecognitionHandler.textLengthBeforeVoiceInput = 0
    }
    
    internal fun performSearch() {
        service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    internal fun commitImage(imagePath: String, mimeType: String = "image/jpeg"): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                FileLogger.e(XimeInputMethodService.TAG, "Image file not found: $imagePath")
                return false
            }

            // 按扩展名修正真实 MIME 类型（PNG/GIF/WebP 表情不应声明为 image/jpeg）
            val actualMimeType = when (imageFile.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> mimeType
            }

            // 宿主未声明支持图片 MIME 时 commitContent 必然失败，
            // 提前返回 false，由调用方降级为复制到剪贴板
            val supportedMimeTypes = service.currentInputEditorInfo?.contentMimeTypes
            if (!supportsMimeType(supportedMimeTypes, actualMimeType)) {
                Log.i(XimeInputMethodService.TAG, "Host does not support image commit (contentMimeTypes=${supportedMimeTypes?.contentToString()}), falling back to clipboard")
                return false
            }

            val cacheDir = File(service.cacheDir, "emoji_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            val cacheFile = File(cacheDir, imageFile.name)
            FileInputStream(imageFile).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val uri = getContentUriForImage(cacheFile, actualMimeType) ?: return false
            
            val inputContentInfo = InputContentInfo(
                uri,
                android.content.ClipDescription("emoji_image", arrayOf(actualMimeType)),
                null
            )
            
            val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            
            service.currentInputConnection?.commitContent(inputContentInfo, flags, null) ?: false
            
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to commit image", e)
            false
        }
    }

    /** 判断宿主声明的 contentMimeTypes 是否支持指定 MIME 类型（支持通配符匹配）。 */
    private fun supportsMimeType(declaredMimeTypes: Array<String>?, mimeType: String): Boolean {
        if (declaredMimeTypes.isNullOrEmpty()) return false
        return declaredMimeTypes.any { declared ->
            declared == "*/*" ||
                declared.equals(mimeType, ignoreCase = true) ||
                (declared.endsWith("/*") && mimeType.startsWith(declared.removeSuffix("/*"), ignoreCase = true))
        }
    }
    

    /** [source] 是这一项来自的剪贴板原文（候选栏的干净链接、BV 号来自带链接的那条）；默认就是 [text]。 */
    internal fun selectClipboardItem(text: String, source: String = text) {
        if (service.candidateState.value.isComposing) {
            service.keyRouter.postRimeJob {
                service.keyRouter.endQueuedComposition()
                withContext(Dispatchers.Main) {
                    service.updateUI()
                }
            }
        }
        // 标记为已消费：候选栏/剪贴板点选上屏后不再重复出现在候选栏
        service.clipboardManager.markConsumed(source)
        if (com.kingzcheung.xime.clipboard.clipboardPasteMode(source, service.clipboardManager.quickSendItems.value +
                service.clipboardManager.clipboardItems.value.filter { it.sensitive }) ==
            com.kingzcheung.xime.clipboard.ClipboardPasteMode.PRIVATE) {
            // 固定内容多是账号、密码等个人信息，记下的密码更是：照常上屏，但不发给插件提交事件，也不进联想上下文。
            service.dismissPredictionsForPaste()
            service.commitTextSilently(text, isPaste = true, isSensitive = true)
            return
        }
        // 保留带 is_paste 的提交事件，不触发打字联想或学习。
        service.commitPastedText(text)
        // 已通过 InputConnection 完成粘贴，不再回写系统剪贴板触发一次新的复制通知。
    }

    internal fun commitClipboardText(text: String) {
        service.commitPastedText(text)
    }

    /**
     * 键盘滑动数字/符号和选字直接上屏，必须与普通按键走同一队列。
     * 直接借用粘贴会只替换宿主 composing 区，却留下 Rime 编码；下一键会把旧字母再写回来。
     * 先结束当前候选，再提交字面内容；手势预览不走此入口。
     */
    internal fun isFullWidthPunctuation(): Boolean {
        // 全角/半角只作用于中文与日文：英文（ASCII）模式和西班牙、德、法、俄、韩语始终输出半角标点。
        // 日文的英数模式除外：全角英数、全角空格是日文输入的常用写法，跟随用户的选择。
        if (service.uiState.value.isAsciiMode && !isJapaneseProfile()) return false
        if (service.uiState.value.inputProfile.language.usesHalfWidthPunctuation) return false
        return punctuationWidthChoice()
    }

    /** 用户的全角/半角选择本身，与当前是否在英文态无关；菜单与键面角标都按它显示。 */
    internal fun punctuationWidthChoice(): Boolean =
        com.kingzcheung.xime.settings.SettingsPreferences.punctuationFullWidth(service,
            !service.rimeEngine.getOption("ascii_punct"))

    internal fun isJapaneseProfile(): Boolean =
        service.uiState.value.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.JAPANESE

    /** [hardware] 实体键盘符号保持完整的全角/半角转换；屏幕中文键盘的符号原样上屏。 */
    internal fun keyboardLiteral(text: String, numberPanel: Boolean = false, preserveWidth: Boolean = false,
        hardware: Boolean = false): String {
        if (service.keyRouter.usesDirectFieldInput) return service.editorInputPolicy.field.typedLiteral(text)
        val state = service.uiState.value
        val chineseKeyboard = !hardware && !state.isAsciiMode &&
            state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE
        return keyboardLiteralWidth(text, isFullWidthPunctuation(),
            state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.JAPANESE,
            numberPanel, preserveWidth, chineseKeyboard)
    }

    internal fun commitLiteralText(text: String, preserveWidth: Boolean = false, hardware: Boolean = false) {
        val submit = literalSubmission(text, preserveWidth, hardware) ?: return
        val t9Queue = if (isT9Schema(service.uiState.value.currentSchemaId) && !service.uiState.value.isAsciiMode) {
            service.keyboardCallbacks?.onT9RunLiteralInput
        } else null
        if (t9Queue != null) t9Queue(submit) else service.keyRouter.postRimeJob { submit() }
    }

    /**
     * 上屏一个标点的那一步（含成对符号、英文自动空格、先把打着的字母原样上屏）；[commitLiteralText] 把它排进按键队列。
     * 已经在按键队列里的命令直接调用它，保持和后面按键的先后（微软双拼没在打字时点的分号键）。
     */
    internal fun literalSubmission(text: String, preserveWidth: Boolean = false, hardware: Boolean = false): (suspend () -> Unit)? {
        if (text.isEmpty()) return null
        val literal = keyboardLiteral(text, preserveWidth = preserveWidth, hardware = hardware)
        // 成对符号只管屏幕键盘；实体键盘的符号多数由引擎处理，只配一部分反而不一致。
        // 终端不认光标回退：补上的结尾会留在光标前面。
        val (paired, caret) = if (!hardware && !service.isTerminalEditor() &&
            com.kingzcheung.xime.settings.SettingsPreferences.isPairedSymbolsEnabled(service))
            PairedSymbols.commitFor(literal) else literal to CommitCaret.NONE
        service.voiceRecognitionHandler.abandonPendingOnManualInput()
        val owner = service.uiState.value.inputSessionId
        // 「标点上屏首选」（默认关）只管屏幕键盘上的断句标点；实体键盘的逗号句号另有翻页规则，不变。
        val candidateFirst = !hardware && isSentencePunctuation(literal) &&
            com.kingzcheung.xime.settings.SettingsPreferences.isPunctuationCommitsCandidateEnabled(service)
        val submit: suspend () -> Unit = literal@ {
            if (owner != service.uiState.value.inputSessionId) return@literal
            if (service.keyRouter.usesDirectFieldInput) {
                service.keyRouter.commitDirectFieldLiteral(literal)
                return@literal
            }
            // 候选栏上是邮箱后缀（刚打了 @）：接着打的点、数字是后缀的一部分。中文键盘的句号按半角 . 上屏，不补空格。
            val emailDomain = !hardware && EmailSuffixes.isSuggestionList(service.candidateState.value.associationCandidates)
            val output = if (emailDomain && literal in EmailSuffixes.DOTS) "." else paired
            // 角标、符号面板打的标点同样跟英文选词后自动补的空格换位置（默认关）。
            if (service.keyRouter.englishAutoSpace.isArmed && service.keyRouter.consumeEnglishAutoSpace(literal)) return@literal
            // 英文标点后自动加空格（默认关）：只管屏幕键盘，成对符号不在其中。
            if (!hardware && !emailDomain && paired == literal && service.keyRouter.commitEnglishPunctuationSpace(literal)) return@literal
            val engine = service.rimeEngine
            if (isT9Schema(service.uiState.value.currentSchemaId)) {
                // 前驱九键已结束，但它的候选刷新在 Main 队列；先让这一帧同步落地。
                // onT9RefreshComposition 不再二次 post，屏上候选与本队列的引擎输入因此同源。
                withContext(Dispatchers.Main) { Unit }
            }
            if (candidateFirst && !emailDomain && owner == service.uiState.value.inputSessionId &&
                commitCandidateAndPunctuation(paired, caret)) return@literal
            if (owner != service.uiState.value.inputSessionId) return@literal
            val committed = commitCompositionAndLiteral(output, asTyped = true, caret = caret)
            // 打 @（中文符号面板是全角 ＠）时给邮箱后缀（默认开），给着后缀时接着打的点、数字重新筛；
            // 邮箱框走上面的直接上屏，在那里给。
            if (committed && (literal == "@" || literal == "＠" ||
                    emailDomain && output.all { it == '.' || it == '-' || it in '0'..'9' }))
                service.keyRouter.offerEmailSuffixesAfterAt()
            // 数字行、角标打的数字：对得上「我的短语」编码的短语放到候选栏（默认关）。
            if (committed && !hardware && literal.lastOrNull()?.let { it in '0'..'9' || it in '０'..'９' } == true)
                service.keyRouter.offerDigitPhrases()
        }
        return submit
    }

    /** One editor transaction for prefix + literal, reused when leaving a typing panel.
     * [asTyped]: symbols, panels and the language key keep the letters, not the first candidate. */
    internal suspend fun commitCompositionAndLiteral(literal: String = "", asTyped: Boolean = false,
        caret: CommitCaret = CommitCaret.NONE): Boolean {
        InputCommandOwner.requireOwner().validate()
        if (service.keyRouter.usesDirectFieldInput) return service.keyRouter.commitDirectFieldLiteral(literal)
        if (service.keyRouter.hasPendingCandidateCommit) return false
        val engine = service.rimeEngine
        val state = service.uiState.value
        val candidates = service.candidateState.value
        val input = engine.getInput()
        val t9 = isT9Schema(engine.getCurrentSchema())
        val partial = if (t9) service.t9PartialSegments.joinToString("") { it.text } else ""
        val action = candidates.candidateActions.firstOrNull()
        val prefix = compositionCommitPrefix(asTyped, input, candidates.shownPreedit(state.inputProfile), t9,
            usesMergedLetterKeys(state.inputProfile.layout), partial,
            englishDelivered = candidates.pendingEnglishText.isNotEmpty(),
            japanesePreview = service.japaneseInputController.conversionPreview()) {
            if (action?.isPluginCandidate == true && !t9) action.commitText
            else engine.conversionPreview().ifEmpty { input }
        }
        val text = prefix + literal
        if (text.isEmpty()) return true
        // 首选是通讯录的人名/号码（不学习）：带着它上屏的这一段同样不学习。
        val private = !asTyped && input.isNotEmpty() && action?.isPrivate == true
        // Rejection retains the complete text and the original command owner for retry/cancel.
        val result = service.keyRouter.commitPreparedText(text, t9, caret, isPrivate = private)
        withContext(Dispatchers.Main) {
            InputCommandOwner.requireOwner().validate()
            if (result.accepted) {
                service.updateUI()
                service.maybeCollapseCandidatePage()
            }
        }
        return result.accepted
    }

    /**
     * 「标点上屏首选」：中文打字中点断句标点，先像空格那样上屏高亮的候选（平常就是首选；九键「下一项」换过高亮时是高亮那个），
     * 候选只用掉一部分编码时，剩下的按首选接上，最后接标点。没在打中文时返回 false，照旧走先上屏字母的路。
     */
    private suspend fun commitCandidateAndPunctuation(literal: String, caret: CommitCaret): Boolean {
        val state = service.uiState.value
        if (state.isAsciiMode || state.inputProfile.language != com.kingzcheung.xime.settings.InputLanguage.CHINESE ||
            state.inputProfile.mode != com.kingzcheung.xime.settings.InputMode.KEYBOARD) return false
        val snapshot = service.candidateState.value
        if (!snapshot.isComposing || snapshot.pendingEnglishText.isNotEmpty() || snapshot.isShowingRecentClipboard) return false
        val index = snapshot.spaceCandidateIndex(state.inputProfile)
        if (index in snapshot.candidates.indices) service.keyRouter.selectCandidateAsync(index, snapshot = snapshot)
        commitCompositionAndLiteral(literal, asTyped = false, caret = caret)
        return true
    }

    internal fun deleteClipboardChars(count: Int) {
        service.currentInputConnection?.deleteSurroundingText(count, 0)
    }

    /**
     * 生成图片 content URI。
     *
     * 优先使用 FileProvider；Android 12+ 部分厂商 ROM 上
     * FileProvider.getUriForFile 内部 resolveContentProvider 以 USER_ALL(-10000)
     * 校验跨用户权限时抛 "Invalid userId -10000"，此时降级为 MediaStore
     * 插入图片获取系统 content URI（API 29+ 免权限）。
     */
    private fun getContentUriForImage(imageFile: File, mimeType: String): Uri? {
        try {
            return FileProvider.getUriForFile(
                service,
                "${service.packageName}.fileprovider",
                imageFile
            )
        } catch (e: IllegalArgumentException) {
            FileLogger.w(XimeInputMethodService.TAG, "FileProvider unavailable, falling back to MediaStore", e)
        } catch (e: Exception) {
            FileLogger.w(XimeInputMethodService.TAG, "FileProvider getUriForFile failed, falling back to MediaStore", e)
        }

        return insertImageToMediaStore(imageFile, mimeType)
    }

    /** 把图片插入 MediaStore（Pictures/CyIME），返回系统 content URI。 */
    private fun insertImageToMediaStore(imageFile: File, mimeType: String): Uri? {
        if (!AndroidPlatform.mediaStoreDownloads) {
            FileLogger.e(XimeInputMethodService.TAG, "MediaStore fallback requires API 29+, image commit failed")
            return null
        }
        return try {
            val resolver = service.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, imageFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CyIME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(imageFile).use { input -> input.copyTo(output) }
                } ?: return null
                if (AndroidPlatform.mediaStoreDownloads) {
                    val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    resolver.update(uri, update, null, null)
                }
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "MediaStore insert failed", e)
            null
        }
    }
}
