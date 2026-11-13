package com.kingzcheung.xime.service

import kotlinx.coroutines.flow.combine

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import com.kingzcheung.xime.settings.KeyboardHeightProfiles
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.keyboard.KeyboardPanelLaunch
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.data.KaomojiData
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.ui.keyboard.LocalStretchFactor
import com.kingzcheung.xime.ui.keyboard.LocalClipboardPanelExpansion
import com.kingzcheung.xime.ui.keyboard.clipboardPanelBounds
import com.kingzcheung.xime.ui.keyboard.clipboardPanelCanExpand
import com.kingzcheung.xime.ui.keyboard.rememberClipboardPanelExpansion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.ProtectedKeyboardSize
import com.kingzcheung.xime.ui.keyboard.KeyboardDockTarget
import com.kingzcheung.xime.ui.keyboard.rectInHost
import com.kingzcheung.xime.ui.keyboard.KeyboardAspectLimits
import com.kingzcheung.xime.ui.keyboard.letterKeyboardDefaults
import com.kingzcheung.xime.ui.keyboard.keyboardPhysicalMetrics
import com.kingzcheung.xime.ui.keyboard.protectKeyboardSize
import com.kingzcheung.xime.ui.keyboard.floatingFooterHeightDp
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardCandidateBar
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardToolbar
import com.kingzcheung.xime.ui.keyboard.HardwareToolbarPosition
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.association.AssociationService
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.sync.ClipboardSyncBridge
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.TOOL_PANEL_SETTINGS_ACTION
import com.kingzcheung.xime.plugin.core.api.TOOL_PANEL_EDIT_ACTION
import com.kingzcheung.xime.plugin.core.api.TOOL_PANEL_EDITING_DISPLAY
import com.kingzcheung.xime.plugin.core.api.ToolPlugin
import com.kingzcheung.xime.plugin.core.api.ToolResult
import com.kingzcheung.xime.plugin.core.lua.PluginEvent
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.buildT9DisplayState
import com.kingzcheung.xime.rime.inlineOffset
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex

import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.ui.theme.keyboardBackgroundBesideCard
import com.kingzcheung.xime.ui.theme.keyboardBackgroundBelowCard
import com.kingzcheung.xime.ui.theme.visualMaterial
import com.kingzcheung.xime.ui.keyboard.isBottomDocked
import com.kingzcheung.xime.ui.keyboard.HardwareCandidateRow
import com.kingzcheung.xime.ui.keyboard.CandidateBarVisuals
import com.kingzcheung.xime.ui.keyboard.NavigationStripBelowCard
import kotlin.math.roundToInt
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.PreeditMergeHelper
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.ActionExecutor
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.underlyingPage
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import java.io.File
import java.io.FileInputStream
import com.kingzcheung.xime.platform.AndroidPlatform

object QuickSendFormEditTextHolder {
    var editText: android.widget.EditText? = null
}

/** 快捷发送表单的触发编码输入框 holder（与内容输入框独立）。 */
object QuickSendFormCodeEditTextHolder {
    var editText: android.widget.EditText? = null
}

/** 通用工具面板输入框 holder（与快捷发送独立，避免互相覆盖）。 */
object ToolPanelEditTextHolder {
    var editText: android.widget.EditText? = null
}

/**
 * T9 九键 partial commit 段：右选部分提交时累积的（文本, 拼音）对。
 * 文本用于 preedit 拼接与上屏，拼音用于用户词典调频/回滚，两者同源同步维护。
 */
data class T9PartialSegment(val text: String, val pinyin: String)

class XimeInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, ActionExecutor {

    companion object {
        /** 键盘与语音悬浮条互换、悬浮条进出时内容下沉/上浮的距离。 */
        private const val CONTENT_SHIFT_DP = 24f
        /** App 收起键盘后等这么久再弹出悬浮条：翻看时 App 可能连续收起几次。 */
        private const val VOICE_PILL_RESHOW_DELAY_MS = 400L
        /** 收起键盘后等系统下滑动画播完再拆玻璃模糊窗口（动画约 0.2–0.4 秒）。 */
        private const val GLASS_BLUR_HIDE_DELAY_MS = 1_000L
        private const val VOICE_PILL_SHOW_TIMEOUT_MS = 1500L
        /** 悬浮条出现、上一段收尾后稍等再开麦：避开键盘弹出动画和发送后输入框的重启。 */
        private const val VOICE_PILL_RESUME_DELAY_MS = 500L
        internal const val TAG = "XimeInputMethodService"
        private const val DARK_MODE_LIGHT = 0
        private const val DARK_MODE_DARK = 1
        private const val DARK_MODE_SYSTEM = 2
        private const val HARDWARE_CANDIDATE_BAR_HEIGHT = 72
        internal const val SAFE_TEXT_LIMIT = 262144
        /** 剪贴板长按「翻译」交给的插件（AI 改写，它有「翻译」动作 `mode:translate`）。 */
        internal const val CLIPBOARD_TRANSLATE_PLUGIN_ID = "com.kingzcheung.xime.plugin.ai_rewrite"

    }

    /** release 构建不输出调试日志，减少 logcat 写入开销。 */
    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    internal val rimeEngine = RimeEngine.getInstance()
    internal val inputReadiness = InputReadiness(android.os.SystemClock::uptimeMillis)
    private val inputDrops = InputDropLog(android.os.SystemClock::uptimeMillis, { FileLogger.w("InputDrop", it) })
    /** When the current editor connection started; only for [noteInputDropped]. */
    private var editorStartedAt = 0L

    /** Logs input refused while no overlay explains it; see [InputDropLog]. Reason and gate state only. */
    internal fun noteInputDropped(reason: String) = inputDrops.drop(reason) {
        val editor = currentInputEditorInfo
        "${inputReadiness.describe()} deploying=${uiState.value.isDeploying} " +
            "pendingCommit=${keyRouter.hasPendingCandidateCommit} queued=${inputCommands.hasPendingCommands} " +
            "editorAgeMs=${android.os.SystemClock.uptimeMillis() - editorStartedAt} " +
            "app=${editor?.packageName} inputType=0x${Integer.toHexString(editor?.inputType ?: 0)} " +
            "connection=${currentInputConnection != null} shown=$isInputViewShown"
    }
    
    internal lateinit var clipboardManager: ClipboardManager

    internal var clipboardSyncBridge: ClipboardSyncBridge? = null
    
    internal lateinit var keyboardContainer: VoiceKeyboardContainer
    
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runtimeObserver: com.kingzcheung.xime.runtime.adaptation.RuntimeObserver? = null

    /** P1 observes proposals only; no feature subscribes to them as an execution policy yet. */
    private fun startRuntimeObservation() {
        if (runtimeObserver == null) {
            runtimeObserver = com.kingzcheung.xime.runtime.adaptation.RuntimeObserver(
                CoroutineScope(serviceScope.coroutineContext + Dispatchers.IO),
                com.kingzcheung.xime.runtime.adaptation.AndroidRuntimeSignalSource(this),
                { com.kingzcheung.xime.runtime.adaptation.RuntimePreferences.read(this) },
            ) { observation ->
                FileLogger.i("RuntimeObservation", "observation only: generation=${observation.generation}, memory=${observation.capabilities.memoryEstimate}, " +
                    "animation=${observation.proposal.animation.proposed}/${observation.proposal.animation.reasons}, " +
                    "keyGlow=${observation.proposal.keyGlow.proposed}/${observation.proposal.keyGlow.reasons}, " +
                    "neuralAdmission=${observation.proposal.neuralPrediction.proposed}/${observation.proposal.neuralPrediction.reasons}, " +
                    "correctionAdmission=${observation.proposal.voiceCorrection.proposed}/${observation.proposal.voiceCorrection.reasons}")
            }
        }
        runtimeObserver?.start()
    }
    @Volatile private var keyProcessingThread: Thread? = null
    private var inputStallWatchdog: InputStallWatchdog? = null
    internal val keyProcessingDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "key-process").also { it.isDaemon = true; keyProcessingThread = it }
    }.asCoroutineDispatcher()
    
    private val inputLatencyWindow = InputLatencyWindow()
    private val inputLatencyByKind = InputLatencyByKind()
    internal val inputCommands = com.kingzcheung.xime.rime.InputCommandQueue(serviceScope, keyProcessingDispatcher,
        reportLatency = { wait, execution ->
            if (wait >= 100 || execution >= 100) FileLogger.w("InputLatency", "queue_wait_ms=$wait action_ms=$execution")
            inputLatencyWindow.record(wait, execution)?.let { timing ->
                // The old 100 ms alarm misses a persistent extra display frame.
                // Report at most once per 64 actions, without logging what was typed.
                if (timing.totalP95Ms >= 16) FileLogger.w("InputLatencyWindow",
                    "actions=${timing.samples} total_median_ms=${timing.totalMedianMs} " +
                        "total_p95_ms=${timing.totalP95Ms} total_max_ms=${timing.totalMaxMs} " +
                        "queue_p95_ms=${timing.waitP95Ms} action_p95_ms=${timing.executionP95Ms}")
            }
        }, reportFailure = { failure ->
            FileLogger.e("InputLifecycle", "Input action failed; subsequent input remains active", failure)
        }, reportSample = { sample ->
            // 按动作类别分别给分位数，区分按键解码、选词、停顿重排与主线程等待；不记按键内容。
            inputLatencyByKind.record(sample)?.let { s ->
                FileLogger.w("InputLatencyKind", "kind=${s.kind} n=${s.samples} total_p50_ms=${s.totalP50} " +
                    "total_p95_ms=${s.totalP95} total_max_ms=${s.totalMax} queue_p95_ms=${s.waitP95} " +
                    "exec_p50_ms=${s.executionP50} exec_p95_ms=${s.executionP95} " +
                    "engine_p50_ms=${s.engineP50} engine_p95_ms=${s.engineP95} engine_max_ms=${s.engineMax}")
            }
        })
    internal val uiEventChannel = Channel<suspend () -> Unit>(Channel.CONFLATED)

    init {
        serviceScope.launch(Dispatchers.Main) {
            consumeInputUiUpdates(uiEventChannel) { failure ->
                FileLogger.e(TAG, "Candidate UI update failed; keeping subsequent updates active", failure)
            }
        }
    }
    
    internal val mainHandler = Handler(Looper.getMainLooper())
    
    internal val uiState = mutableStateOf(InputUIState())
    internal val candidateState = mutableStateOf(CandidateState())
    internal val glideCandidatePreview = mutableStateOf("")
    internal val glideRimePreview = mutableStateOf<com.kingzcheung.xime.rime.RimeProcessResult?>(null)
    internal val glideEnglishPreview = mutableStateOf<com.kingzcheung.xime.ui.keyboard.GlideEnglishPreview?>(null)
    private val clipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    private val voiceAmplitudeState = mutableFloatStateOf(0f)
    private val voiceSpectrumState = mutableStateOf(FloatArray(16))
    /** 实体键盘 Caps Lock 状态，按键事件到达即更新，供收起栏显示 ⇪。 */
    private val hardwareCapsLockState = mutableStateOf(false)
    private val quickSendItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    internal val recentClipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    /** 候选栏剪贴板胶囊的各项：每条原文，带链接的后面跟干净链接和 BV 号（见 ClipboardLinkChoices）。 */
    internal fun clipboardBarTexts(items: List<com.kingzcheung.xime.clipboard.ClipboardItem>): List<String> =
        com.kingzcheung.xime.clipboard.ClipboardLinkChoices.expandItems(items).map {
            com.kingzcheung.xime.clipboard.ClipboardLinkChoices.shownText(items, it)
        }


    private val bottomInsetPxState = mutableStateOf(0)
    private val fixedBottomInsetPxState = mutableStateOf(0)
    private val fixedMinimumInsetPxState = mutableStateOf(0)
    private val floatingMinimumInsetPxState = mutableStateOf(0)
    private var hasMeasuredBottomInsets = false

    private fun floatingMinimumBottomDp() = imeBottomSpaceDp(floatingMinimumInsetPxState.value, resources.displayMetrics.density)
    private fun suggestedBottomDp() = imeBottomSpaceDp(bottomInsetPxState.value, resources.displayMetrics.density)

    private fun updateKeyboardBottomInsets(insets: android.view.WindowInsets?) {
        // 窗口切换时 null 是尚未上报，不代表系统栏已消失。首次出现先保守估算。
        if (insets == null && hasMeasuredBottomInsets) return
        val win = window.window
        val normal = if (insets != null) extractBottomInset(insets, win) else
            (tryGetNavBarHeightDp(this, win) * resources.displayMetrics.density).roundToInt()
        if (insets != null) hasMeasuredBottomInsets = true
        val previousSuggestion = suggestedBottomDp()
        bottomInsetPxState.value = normal
        val minimum = if (win != null && insets != null) fixedKeyboardMinimumInsetPx(insets, win, normal) else normal
        fixedMinimumInsetPxState.value = minimum
        // 贴底只管固定键盘：悬浮键盘的最低位置照旧按系统按钮和手势区算。
        floatingMinimumInsetPxState.value = if (win != null && insets != null)
            floatingMinimumInsetPx(win, insets, fixedKeyboardMinimumInsetPx(insets, win, normal, edge = false,
                position = SettingsPreferences.KeyboardBottomPosition.AUTO)) else minimum
        fixedBottomInsetPxState.value = if (win != null && insets != null)
            fixedKeyboardBottomInsetPx(insets, win, normal, minimum) else normal
        // 设置页的「底部留白校准」看不到输入法窗口，记下这次量到的可调低范围，调低按到下限就停。
        if (insets != null) SettingsPreferences.setKeyboardBottomLoweringLimitDp(this,
            suggestedBottomDp() - imeBottomSpaceDp(minimum, resources.displayMetrics.density))
        val state = uiState.value
        if (state.isFloatingMode && !state.showKeyboardResize && previousSuggestion != suggestedBottomDp() &&
            SettingsPreferences.getFloatingOffsetY(this, resources.configuration.screenWidthDp > resources.configuration.screenHeightDp, -1) < 0) {
            uiState.value = state.copy(floatingOffsetY = suggestedBottomDp())
        }
    }

    /**
     * 固定卡片的位置变了就自检一次底边：输入法窗口没铺到屏幕底边就让它铺到底（[checkImeWindowReachesScreenBottom]），
     * 并记下卡片底下实际空出多少、按规则该空多少，手机上一看日志就知道那条是谁留的。
     */
    private fun checkFixedKeyboardBottom(cardBottomInWindowPx: Int, expectedGapDp: Int) {
        val win = window.window ?: return
        val windowGap = checkImeWindowReachesScreenBottom(win)
        val cardGap = win.decorView.height - cardBottomInWindowPx
        val expected = (expectedGapDp * resources.displayMetrics.density).roundToInt()
        FileLogger.d(TAG, "bottom-edge: windowGap=$windowGap cardGap=$cardGap expected=$expected " +
            "minimum=${fixedMinimumInsetPxState.value} suggestion=${bottomInsetPxState.value}")
    }

    internal fun keyboardBottomSpaceDp(state: InputUIState): Int = imeBottomSpaceDp(
        if (state.isFloatingMode || state.isCompact || state.voiceCollapsed) bottomInsetPxState.value
        else fixedBottomInsetPxState.value,
        resources.displayMetrics.density,
    )

    // The overlay uses the full window. Keep its existing visible-bar fallback in automatic mode.
    private fun fixedClipboardBottomDp(fixedDp: Int, visibleBarDp: Int): Int =
        if (fixedBottomInsetPxState.value == bottomInsetPxState.value) maxOf(fixedDp, visibleBarDp) else fixedDp
    // Connection changes must also recompose the full screen keyboard when compact stays false.
    private var hardwareKeyboardDisplay by mutableStateOf(HardwareKeyboardDisplayState())
    private var settingsPreviewEditor by mutableStateOf(false)
    private var settingsPreviewPanel by mutableStateOf<KeyboardPanelLaunch.Panel?>(null)
    private var settingsExample by mutableStateOf(KeyboardPanelLaunch.Example.Keyboard)
    private val previewCandidates = CandidateState(inputText = "shili", preeditText = "shi li",
        isComposing = true, candidates = listOf("示例", "实例", "实力", "事例"))
    private var settingsResizeBounds: android.graphics.Rect? = null
    private var settingsArrangeBounds: android.graphics.Rect? = null

    private fun allowSettingsPreviewTouch(event: android.view.MotionEvent): Boolean {
        if (!settingsPreviewEditor) return true
        val origin = IntArray(2)
        keyboardContainer.getLocationInWindow(origin)
        val x = (origin[0] + event.x).toInt()
        val y = (origin[1] + event.y).toInt()
        val bounds = when {
            settingsPreviewPanel == KeyboardPanelLaunch.Panel.RESIZE && uiState.value.showKeyboardResize -> settingsResizeBounds
            settingsPreviewPanel == KeyboardPanelLaunch.Panel.ARRANGE &&
                (keyboardViewModel.page.value as? KeyboardPage.Overlay)?.route == OverlayRoute.ToolbarCustomize -> settingsArrangeBounds
            else -> null
        } ?: run {
            // 只读预览被点：请设置页打开试打框，焦点换到真实输入框后这副键盘就能直接打字。调节／排列面板打开时不算。
            if (settingsPreviewPanel == null && event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
                KeyboardPanelLaunch.typingRequested = true
            return false
        }
        return bounds.contains(x, y)
    }
    internal val hasHardwareKeyboard: Boolean get() = hardwareKeyboardDisplay.connected
    private val hardwareGamePackages = HardwareGamePackages { HardwareGamePassthrough.isGame(packageManager.getApplicationInfo(it, 0)) }
    /** 动态背景：当前输入框所在应用是游戏时不动（和游戏直通同一个判断，不管接没接实体键盘）。 */
    private val backgroundMotionGame = mutableStateOf(false)
    /** 动态背景的钟只在键盘窗口显示时走。 */
    private val backgroundMotionWindowShown = mutableStateOf(false)
    /** Between onWindowShown and onWindowHidden; the stall watchdog runs only then. */
    private var imeWindowShown = false
    /** 游戏直通生效中：键盘不显示，实体按键不经过 CyIME（见 [HardwareGamePassthrough]）。 */
    private val hardwareGamePassthrough: Boolean get() = HardwareGamePassthrough.active(
        uiState.value.hardwareOptions.gamePassthrough, hasHardwareKeyboard,
        hardwareGamePackages.isGame(currentInputEditorInfo?.packageName)) && !gameShowExplicit
    /**
     * 游戏直通开着时应用显式要键盘（showSoftInput 带 SHOW_EXPLICIT，如启动器自己的「调用输入法」键，#157）：
     * 这一次照常显示键盘、按键照常经过 CyIME；键盘收起或离开输入框后恢复直通。点输入框的普通请求仍不弹。
     */
    private var gameShowExplicit = false
    private val hardwareInputManager by lazy { getSystemService(android.hardware.input.InputManager::class.java) }
    private val hardwareInputListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = refresh()
        override fun onInputDeviceChanged(deviceId: Int) = refresh()
        private fun refresh() {
            val previous = hasHardwareKeyboard
            updateHardwareKeyboardConnection()
            if (previous != hasHardwareKeyboard) {
                applyCompactMode()
                requestHardwareCursorUpdates()
                // 玩游戏途中接上实体键盘：已经弹出的键盘收起，按键交给游戏。
                if (hardwareGamePassthrough && isInputViewShown) requestHideSelf(0)
            }
        }
    }

    private fun updateHardwareKeyboardConnection() {
        val devices = hardwareInputManager.inputDeviceIds.map(hardwareInputManager::getInputDevice).filterNotNull()
        hardwareKeyboardDisplay = hardwareKeyboardDisplay.withConnection(hasPhysicalTextKeyboard(devices))
    }
    private val hardwareToolbarPosition = mutableStateOf(HardwareToolbarPosition())
    private var hardwareViewportWidthDp = 0f
    private var hardwareViewportHeightDp = 0f
    internal fun hardwareAssociationLimit(): Int {
        if (!uiState.value.isCompact) return PredictionManager.MAX_ASSOCIATION_COUNT + 1
        return if (com.kingzcheung.xime.ui.keyboard.hardwareToolbarCanShowCandidates(
                hardwareViewportWidthDp, hardwareViewportHeightDp, hardwareToolbarPosition.value,
                uiState.value.hardwareOptions.dockAtEdge)) 10 else 0
    }
    private val hardwareToolbarBounds = mutableStateOf<android.graphics.Rect?>(null)
    private var hardwareCandidateBounds: android.graphics.Rect? = null
    private var hardwarePreeditBounds: android.graphics.Rect? = null
    private val glassBlurWindows = mutableMapOf<String, ImeGlassBlurWindow>()
    private val glassSurfaceBounds = mutableMapOf<String, android.graphics.Rect>()
    /** Where [keyboardContainer] sat in the IME window when each of [glassSurfaceBounds] was measured. */
    private val glassSurfaceOrigins = mutableMapOf<String, androidx.compose.ui.unit.IntOffset>()
    internal val editorInputPolicy = EditorSessionInputPolicy()
    private var glassConfig = com.kingzcheung.xime.settings.FrostedGlassConfig()
    private var glassWindowVisible = false
    private var glassSurfaceMode = ""
    // 整块键盘透明度与玻璃材质相乘：模糊窗口随按键一起淡出，而不是始终满强度地糊住宿主。
    private var glassOpacity = 1f
    /** 「柔化顶部边缘」的细条模糊窗口各自的透明度系数（乘在 [glassOpacity] 上）；其他窗口是 1。 */
    private val glassAlphaScale = mutableMapOf<String, Float>()
    /** 「柔化顶部边缘」此刻是否适用（玻璃、固定键盘、顶部直角，见 Compose 里的 SideEffect）。 */
    private var glassTopFeatherOn = false
    /** 生效时固定卡片上沿在窗口里的 y：Compose 的玻璃底色按它渐隐，和下面的细条模糊对齐。 */
    private val glassTopFeatherTop = mutableStateOf<Float?>(null)

    /** The keyboard view's place in the IME window; blur bounds are measured relative to it. */
    private fun glassAnchorOrigin(): androidx.compose.ui.unit.IntOffset? {
        if (!::keyboardContainer.isInitialized || !keyboardContainer.isAttachedToWindow) return null
        val location = IntArray(2)
        keyboardContainer.getLocationInWindow(location)
        return androidx.compose.ui.unit.IntOffset(location[0], location[1])
    }

    /**
     * Blur windows follow measured content bounds; they never determine keyboard geometry.
     * [measuredAt] is [glassAnchorOrigin] when [bounds] was measured (now, for a fresh report).
     */
    private fun updateGlassSurface(name: String, bounds: android.graphics.Rect?,
        measuredAt: androidx.compose.ui.unit.IntOffset? = glassAnchorOrigin()) {
        if (bounds == null || bounds.isEmpty) {
            glassSurfaceBounds.remove(name)
            glassSurfaceOrigins.remove(name)
        } else {
            glassSurfaceBounds[name] = android.graphics.Rect(bounds)
            if (measuredAt == null) glassSurfaceOrigins.remove(name) else glassSurfaceOrigins[name] = measuredAt
        }
        val surface = glassBlurWindows[name] ?: if (glassConfig.enabled && bounds != null) {
            ImeGlassBlurWindow(this, name, anchorOrigin = { glassAnchorOrigin() }) { window.window?.decorView }
                .also { glassBlurWindows[name] = it }
        } else return
        val density = resources.displayMetrics.density
        // 固定键盘关了顶部圆角时卡片顶边是直角，模糊也用直角，顶角不露出没模糊的应用。
        val squareTop = !uiState.value.isFloatingMode && !SettingsPreferences.roundedKeyboardTop(this)
        surface.configure(glassConfig.enabled, (glassConfig.blurRadiusDp * density).roundToInt(),
            if (name == "navigation" || squareTop && name.startsWith("keyboard")) 0f
            else 16f * density)
        surface.setOpacity(glassOpacity * (glassAlphaScale[name] ?: 1f))
        surface.setRegion(glassSurfaceBounds[name], glassSurfaceOrigins[name])
        surface.setVisible(glassWindowVisible)
    }

    /**
     * 「填满留空处」时固定卡片两侧也是键盘：卡片那块模糊放宽到整宽，和整宽键盘一样只有一块。
     * 原来两侧另开直角模糊、卡片那块按 16dp 圆角，卡片顶角处露出一小块没模糊的应用。
     */
    private fun withFilledSides(card: android.graphics.Rect?): android.graphics.Rect? {
        if (card == null || uiState.value.isFloatingMode || uiState.value.handwritingExpanded ||
            !::keyboardContainer.isInitialized || !SettingsPreferences.isKeyboardBlankFilled(this)) return card
        val width = keyboardContainer.width
        if (width <= 0) return card
        val host = IntArray(2)
        keyboardContainer.getLocationInWindow(host)
        return android.graphics.Rect(minOf(host[0], card.left), card.top, maxOf(host[0] + width, card.right), card.bottom)
    }

    private fun onSplitGapChanged() {
        updateKeyboardGlass()
        if (::keyboardContainer.isInitialized) keyboardContainer.requestLayout()
    }

    /** 全屏手写时只模糊底部控件区，书写区保持清晰、能看见后面的应用。 */
    private var handwritingGlassControls: android.graphics.Rect? = null
    private var handwritingGlassControlsOrigin: androidx.compose.ui.unit.IntOffset? = null
    /** 全屏手写底下那块按键（含底部留白）在窗口里的位置；导航栏那条照它的宽度接下去，和平时的键盘卡片一样。 */
    private val handwritingFooterCard = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private val handwritingGlassRegionReporter: (android.graphics.Rect?) -> Unit = { bounds ->
        handwritingFooterCard.value = bounds?.let {
            androidx.compose.ui.geometry.Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
        val origin = glassAnchorOrigin()
        if (handwritingGlassControls != bounds || handwritingGlassControlsOrigin != origin) {
            handwritingGlassControls = bounds
            handwritingGlassControlsOrigin = origin
            updateKeyboardGlass()
        }
    }

    /** 分体时模糊分三块：上方工具栏整宽，左右两半各一块，中缝保持清晰。 */
    private fun updateKeyboardGlass() {
        val handwriting = uiState.value.handwritingExpanded
        val card = withFilledSides(if (handwriting) handwritingGlassControls else keyboardGlassCard)
        // All pieces below come from the card's measurement, so they share its anchor origin.
        val origin = if (handwriting) handwritingGlassControlsOrigin else keyboardGlassCardOrigin
        val hole = card?.let { splitKeyboardGap.hole(it.bottom.toFloat()) }
        if (card == null || hole == null) {
            updateTopGlass(card, origin)
            updateGlassSurface("keyboard-left", null)
            updateGlassSurface("keyboard-right", null)
            return
        }
        val top = hole.top.roundToInt().coerceIn(card.top, card.bottom)
        // 两半向上压住工具栏那块的圆角，拼接处不露缺口。
        val halfTop = (top - (16f * resources.displayMetrics.density).roundToInt()).coerceAtLeast(card.top)
        updateTopGlass(android.graphics.Rect(card.left, card.top, card.right, top), origin)
        updateGlassSurface("keyboard-left", android.graphics.Rect(card.left, halfTop, hole.left.roundToInt(), card.bottom), origin)
        updateGlassSurface("keyboard-right", android.graphics.Rect(hole.right.roundToInt(), halfTop, card.right, card.bottom), origin)
    }

    /**
     * 卡片最上面那块模糊。「柔化顶部边缘」开着时上沿 16dp 切成几条细窗口，透明度一级级升到满，
     * 那块整的从这段下面开始；Compose 里的玻璃底色按同一段渐隐（GlassTopFeather.kt）。
     */
    private fun updateTopGlass(rect: android.graphics.Rect?, origin: androidx.compose.ui.unit.IntOffset?) {
        val featherPx = (com.kingzcheung.xime.ui.theme.GLASS_TOP_FEATHER_HEIGHT.value * resources.displayMetrics.density).roundToInt()
        val bands = if (glassTopFeatherOn && rect != null) com.kingzcheung.xime.ui.theme.glassTopFeatherBands(
            rect.left, rect.top, rect.right, rect.bottom, featherPx) else emptyList()
        glassTopFeatherTop.value = if (bands.isEmpty()) null else rect!!.top.toFloat()
        updateGlassSurface("keyboard", if (bands.isEmpty()) rect
            else android.graphics.Rect(rect!!.left, rect.top + featherPx, rect.right, rect.bottom), origin)
        for (i in 0 until com.kingzcheung.xime.ui.theme.GLASS_TOP_FEATHER_STEPS) {
            val name = "keyboard-edge-$i"
            val band = bands.getOrNull(i)
            if (band == null) glassAlphaScale.remove(name) else glassAlphaScale[name] = band.alpha
            if (band != null || glassBlurWindows.containsKey(name) || glassSurfaceBounds.containsKey(name)) {
                updateGlassSurface(name, band?.let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }, origin)
            }
        }
    }

    private fun updateGlassTopFeather(on: Boolean) {
        if (glassTopFeatherOn == on) return
        glassTopFeatherOn = on
        updateKeyboardGlass()
    }

    /** 当前布局真正以分体显示（偏好开启、屏幕够宽，且是 26 键字母布局）。 */
    internal fun splitKeyboardActive(state: InputUIState): Boolean =
        SettingsPreferences.isSplitKeyboardShown(this) &&
            com.kingzcheung.xime.ui.keyboard.supportsSplitKeyboard(state.currentSchemaId, state.isAsciiMode)

    private fun updateGlassOpacity(opacity: Float) {
        if (glassOpacity == opacity) return
        glassOpacity = opacity
        glassBlurWindows.forEach { (name, surface) -> surface.setOpacity(opacity * (glassAlphaScale[name] ?: 1f)) }
    }

    private fun updateGlassConfiguration(config: com.kingzcheung.xime.settings.FrostedGlassConfig, mode: String) {
        if (glassSurfaceMode != mode) {
            glassSurfaceMode = mode
            glassSurfaceBounds.clear()
            glassSurfaceOrigins.clear()
            glassBlurWindows.values.forEach { it.setRegion(null) }
        }
        glassConfig = config.normalized()
        (glassSurfaceBounds.keys + glassBlurWindows.keys).toSet().forEach { name ->
            updateGlassSurface(name, glassSurfaceBounds[name], glassSurfaceOrigins[name])
        }
    }
    /** 当前输入框是否受限（密码/终端/NO_SUGGESTIONS，见 EditorInfoClassifier）。
     *  主线程写（onStartInput）、key-processing 线程读（英文联想短路），volatile 保证可见性。 */
    @Volatile
    private var editorRestricted: Boolean = false
    /** 秘密输入框（密码/TYPE_NULL）：英文联想与回删替换的统一禁用线 */
    private var editorSecret: Boolean = false
    private var floatingWinX = 100
    private var floatingWinY = 300
    
    internal var isTrackingVoiceButtons = false
    internal var voiceRecordingStarted = false
    private var pendingVoiceAction: (() -> Unit)? = null
    internal var composeViewRef: View? = null
    internal var lastClearedText: String = ""
    /** 空闲态上滑清空掉的整框文字，供下滑撤回原位恢复；与 [lastClearedText]（清掉的编码）互斥。 */
    @Volatile internal var lastClearedDocument: ClearedDocument? = null
    /** 上面两样是从哪个输入框清掉的：下滑撤回只放回同一个框（应用的框清掉的字不能撤回到「新增固定」里）。 */
    internal var lastClearedTarget: InputTarget = InputTarget.Host
    /** 最近一次删除键左右滑删了字的那次触摸；比 [lastClearedDocument] 新，撤回手势先放回它。 */
    @Volatile internal var lastSwipeDelete: ImeDistanceBackspace? = null
    /** 累积的 partial commit 段列表（多段选词场景下逐段追加，文本+拼音同源，供调频/回滚） */
    internal val t9PartialSegments = mutableListOf<T9PartialSegment>()
    /** 键盘回调引用，用于在 RIME selectCandidate 前同步通知 T9 控制器 */
    internal var keyboardCallbacks: KeyboardCallbacks? = null
    private var keyboardReportsChinese = true
    internal var isChineseMode: Boolean
        get() = keyboardReportsChinese && !uiState.value.isAsciiMode &&
            uiState.value.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE
        set(value) { keyboardReportsChinese = value }
    internal var currentEffectiveKeyboardHeight: Int = 0
    private var floatingCardBounds: android.graphics.Rect? = null
    /** 悬浮卡片外四角角标把手（亮着时）：露在卡片外的部分也要能按到。 */
    private var floatingCornerHandleBounds: List<android.graphics.Rect> = emptyList()
    private var fixedCardBounds: android.graphics.Rect? = null
    /** 屏幕键盘时输入框在屏幕上的范围：弹键盘时向输入框要一次，不常驻监听。窄固定键盘据此判断有没有盖住输入框。 */
    private var softEditorBoundsOnScreen: HardwareCursorAnchor? = null
    private var keyboardGlassCard: android.graphics.Rect? = null
    private var keyboardGlassCardOrigin: androidx.compose.ui.unit.IntOffset? = null
    /** 分体中缝（窗口坐标）：触摸区让给宿主，玻璃模糊也避开它。 */
    internal val splitKeyboardGap = com.kingzcheung.xime.ui.keyboard.SplitKeyboardGap().also {
        it.onChanged = { onSplitGapChanged() }
    }
    internal var currentFloatingCardWidthDp = 0
    internal var currentFloatingCardHeightDp: Int = 0
    /** Base keyboard only: expanded clipboard/tool panels must never seed a resize. */
    internal var currentResolvedKeyboardSize: ProtectedKeyboardSize? = null
    internal var previousSchemaId: String = ""
    
    internal val calculatorEngine = com.kingzcheung.xime.calculator.CalculatorEngine()

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    internal val keyboardViewModel: KeyboardViewModel by lazy {
        ViewModelProvider(
            _viewModelStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(applicationContext as android.app.Application)
        ).get(KeyboardViewModel::class.java)
    }

    /** 候选展开页自动收起：候选与联想均空（编码删空）时收起在位展开页，不留空页。
     *  在状态生产端调用（而非依赖 UI 重组观察）——候选变化时键盘区作用域被设计为跳过重组。 */
    internal fun maybeCollapseCandidatePage() {
        if (deleteDragActive.value || !keyboardViewModel.candidatePageExpanded.value) return
        val cs = candidateState.value
        if (cs.candidates.isEmpty() && cs.associationCandidates.isEmpty()) {
            keyboardViewModel.setCandidatePageExpanded(false)
        }
    }

    internal val deleteDragActive = androidx.compose.runtime.mutableStateOf(false)
    private var deleteDragGeneration = 0L

    internal fun beginDeleteDrag(): Long {
        deleteDragGeneration++
        deleteDragActive.value = true
        return deleteDragGeneration
    }

    internal fun finishDeleteDrag(generation: Long) {
        if (!deleteDragActive.value || generation != deleteDragGeneration) return
        if (inputReadiness.ticket() == null) {
            deleteDragActive.value = false
            maybeCollapseCandidatePage()
            return
        }
        keyRouter.postRimeJob("finish_delete_drag") {
            withContext(Dispatchers.Main) {
                if (generation == deleteDragGeneration) {
                    deleteDragActive.value = false
                    maybeCollapseCandidatePage()
                }
            }
        }
    }

    private val expandedCandidateLoader by lazy {
        ExpandedCandidateLoader(serviceScope, keyProcessingDispatcher, rimeEngine::readCandidateBatch)
    }

    /** Expanded candidates belong to an engine revision and editor session, never display text. */
    internal fun refreshExpandedCandidates() {
        expandedCandidateLoader.cancel()
        keyboardViewModel.setCandidateStripExtended(false)
        candidateState.value = candidateState.value.copy(expandedCandidates = emptyList(), expandedCandidatesLoaded = false)
        if (!keyboardViewModel.candidatePageExpanded.value) return
        loadExpandedCandidates()
    }

    /** 收起的候选栏滑到最后几个：把展开列表接在候选栏后面，展开页不打开。 */
    internal fun extendCandidateStrip() {
        if (keyboardViewModel.candidateStripExtended.value) return
        keyboardViewModel.setCandidateStripExtended(true)
        if (!candidateState.value.expandedCandidatesLoaded) loadExpandedCandidates()
    }

    /** 打开「单字」：已读的候选里单字不够（长编码的单字排在几百个词后面）就从已读处接着往后读。 */
    internal fun loadSingleCharacterCandidates() {
        val snapshot = candidateState.value
        if (!keyboardViewModel.singleCharFilter.value || !snapshot.expandedCandidatesLoaded) return
        if (snapshot.expandedCandidates.size >= ExpandedCandidateLoader.readLimit(snapshot.expandedCandidates, true)) return
        loadExpandedCandidates(initial = snapshot.expandedCandidates)
    }

    private fun loadExpandedCandidates(initial: List<com.kingzcheung.xime.rime.RimeCandidate> = emptyList()) {
        val snapshot = candidateState.value
        val session = uiState.value.inputSessionId
        val admission = inputReadiness.ticket() ?: return
        japaneseInputController.displayCandidates(snapshot.inputText)?.let {
            candidateState.value = snapshot.copy(expandedCandidates = it, expandedCandidatesLoaded = true)
            return
        }
        expandedCandidateLoader.load(snapshot.engineRevision, isCurrent = {
            inputReadiness.accepts(admission) && uiState.value.inputSessionId == session &&
                (keyboardViewModel.candidatePageExpanded.value || keyboardViewModel.candidateStripExtended.value) &&
                candidateState.value.engineRevision == snapshot.engineRevision
        }, singleCharacters = { keyboardViewModel.singleCharFilter.value }, initial = initial) { candidates ->
            candidateState.value = candidateState.value.copy(expandedCandidates = candidates, expandedCandidatesLoaded = true)
        }
    }

    /** 硬件键盘在展开态按 DPAD_DOWN/UP：展开页滚动一屏（经事件流驱动 UI） */
    internal fun expandedPageScroll(direction: Int) {
        keyboardViewModel.requestExpandedPageScroll(direction)
    }
    
    internal val predictionManager: PredictionManager = PredictionManager(
        context = this,
        serviceScope = serviceScope,
        onPredictionResult = { candidates ->
            candidateState.value = candidateState.value.copy(
                associationCandidates = if (isChineseMode && !candidateState.value.isComposing &&
                    candidateState.value.inputText.isEmpty() && canPredictAfter(predictionManager.lastCommittedText)) candidates else emptyList()
            )
        },
        completionLanguage = { uiState.value.let { if (it.isAsciiMode) com.kingzcheung.xime.settings.InputLanguage.ENGLISH else it.inputProfile.language } },
        traditionalBridge = { if (traditionalChineseOutput()) traditionalScriptBridge else null },
    )
    private val traditionalScriptBridge by lazy {
        PredictionManager.ScriptBridge(toSimplified = rimeEngine::toSimplified, toTraditional = rimeEngine::toTraditional)
    }
    
    /** 这次说话写进哪个框：开始说话时焦点在翻译栏、剪贴板搜索这些键盘自己的输入框，就一直写那个框（11-11）。 */
    private val voiceTarget = VoiceTarget(
        focusedInternal = { uiState.value.inputTarget.editText() },
        connectionFor = { box: android.widget.EditText -> schemaController.internalConnectionFor(box) },
        host = { currentInputConnection },
    )

    internal val voiceRecognitionHandler = VoiceRecognitionHandler(
        context = this,
        onStateChanged = { newState -> uiState.value = newState },
        getState = { uiState.value },
        getInputConnection = { voiceTarget.connection() },
        onRecordingStopped = { restoreAfterVoiceFinish() },
        onVoiceComplete = {
            val action = pendingVoiceAction
            pendingVoiceAction = null
            action?.invoke()

            restoreAfterVoiceFinish()
        },
        onAmplitudeChanged = { amplitude ->
            voiceAmplitudeState.floatValue = amplitude
        },
        onSpectrumChanged = { spectrum ->
            voiceSpectrumState.value = spectrum
        },
        // 写进键盘自己的框时，应用输入框里没有语音的组合区，也不发到电脑。
        onComposingWritten = { if (!voiceTarget.internal) markInputBoxComposing() },
        remoteTyping = { if (voiceTarget.internal) null else remoteTypingSink() },
        traditionalConverter = { if (traditionalChineseOutput()) rimeEngine::toTraditional else null },
        onSessionStart = { voiceTarget.begin() },
    )

    /** 隔空输入开着、存过电脑时：说完的话发到电脑（RemoteTyping），开始说话时提示一次发到哪。 */
    private fun remoteTypingSink(): ((String) -> Unit)? {
        if (!SettingsPreferences.isSttRemoteTyping(this) || !com.kingzcheung.xime.clipboard.sync.RemoteTyping.available()) return null
        voiceRecognitionHandler.showVoiceNotice("隔空输入：说的话发到" +
            (com.kingzcheung.xime.clipboard.sync.RemoteTyping.knownComputer()?.let { " $it" } ?: "电脑"))
        return { text -> com.kingzcheung.xime.clipboard.sync.RemoteTyping.send(text, ::onRemoteTyped) }
    }

    private fun onRemoteTyped(text: String, result: com.kingzcheung.xime.clipboard.sync.RemoteTypingSender.Result) {
        when {
            result.typed -> Unit
            result.copied -> voiceRecognitionHandler.showVoiceNotice(
                "${result.computer ?: "电脑"} 上当前不是 CyIME 输入框，已放到电脑剪贴板，按 Ctrl+V 粘贴")
            else -> {
                // 没发出去的话不能丢：放到手机剪贴板。
                runCatching {
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("CyIME", text))
                }
                voiceRecognitionHandler.showVoiceNotice("没发到电脑：${result.error ?: "连不上电脑"}。这句已复制到手机剪贴板")
            }
        }
    }

    /**
     * 结束语音会话的统一出口：停止预启动并进入收尾——等待引擎最终结果后提交，
     * 超时回退提交部分结果（见 VoiceRecognitionHandler.finishRecognition）。
     * 键盘状态在收尾完成时经 onVoiceComplete → [restoreAfterVoiceFinish] 恢复，
     * 期间语音面板保持"正在识别..."显示，避免用户感知到结果被截断。
     * 幂等：收尾已在进行中时重复调用自动跳过。
     */
    internal fun endVoiceSession() {
        voiceRecognitionHandler.cancelPreStart()
        voiceRecognitionHandler.finishRecognition()
    }

    /** 按住空格说话上滑取消：停麦，撤掉这次已经写进输入框的字，迟到的识别结果也不再写。 */
    internal fun cancelVoiceSession() {
        voiceRecognitionHandler.discardSession()
        voiceRecognitionHandler.cancelPreStart()
        restoreAfterVoiceFinish()
    }

    /** 语音会话真正完成（最终结果已提交/超时兜底/出错）后恢复键盘状态。幂等。 */
    internal fun restoreAfterVoiceFinish() {
        if (uiState.value.isVoiceMode && uiState.value.voiceSticky) feedbackManager.playKeySound("voice_end")
        // 兜底：会话结束的任何路径都确保录音已请求停止（幂等，正常松手路径
        // recordingThread 已置空时直接返回）。UI 状态一旦清零，松手停止链就失效，
        // 这里漏一次 stop 麦克风/引擎连接就会一直后台占用。
        voiceRecognitionHandler.stopRecognition()
        keyboardViewModel.exitVoice()
        isTrackingVoiceButtons = false
        voiceRecordingStarted = false
        voiceAmplitudeState.floatValue = 0f
        uiState.value = uiState.value.copy(
            isVoiceMode = false,
            voiceSticky = false,
            voiceButtonState = VoiceButtonState(),
            voiceRecognitionState = RecognitionState.IDLE,
            voiceRecognizedText = "",
            voiceAmplitude = 0f
        )
        voiceStopRequested = false
        voiceFieldHadText = false
        voicePillAutoResumed.value = false
        // 开车连续语音：悬浮条听写因发送停下、输入框焦点还在，收尾完就接着听。
        if (voicePillRestartInPlace) {
            voicePillRestartInPlace = false
            scheduleVoicePillResume()
        }
        // 悬浮条是因为 App 收起键盘才出现的：听写结束就一起收起，回到 App 原来没有键盘的样子。
        if (voicePillAuto) {
            voicePillAuto = false
            if (uiState.value.voiceCollapsed) {
                uiState.value = uiState.value.copy(voiceCollapsed = false)
                requestHideSelf(0)
            }
        }
    }
    
    private var sharedPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var clipboardCollectorJob: kotlinx.coroutines.Job? = null
    
    internal val feedbackManager = FeedbackManager(this)

    internal val keyRouter = ImeKeyRouter(this)

    internal val sessionController = ImeSessionController(this)

    internal val schemaController = ImeSchemaController(this)
    internal val japaneseInputController = JapaneseInputController(this)

    internal val textCommit = ImeTextCommit(this)
    
    private val inlineSuggestionManager = if (AndroidPlatform.inlineSuggestions) {
        InlineSuggestionManager(this)
    } else null
    
    private fun loadDarkModePreference() {
        currentResolvedKeyboardSize = null
        if (uiState.value.showKeyboardResize) {
            // 编辑事务的几何不从偏好回灌；确认写完后统一恢复普通读取。
            uiState.value = uiState.value.copy(
                darkMode = SettingsPreferences.getEffectiveDarkMode(this),
                themeId = SettingsPreferences.getKeyboardTheme(this),
                toolbarButtons = SettingsPreferences.getToolbarButtons(this),
                menuButtons = SettingsPreferences.getMenuButtons(this),
                isSttEnabled = SettingsPreferences.isSttEnabled(this),
            )
            return
        }
        val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val isFloatingMode = if (uiState.value.showKeyboardResize) uiState.value.isFloatingMode
            else SettingsPreferences.isFloatingMode(this, isLandscape)
        val loadedX = SettingsPreferences.getFloatingOffsetX(this, isLandscape)
        val loadedY = SettingsPreferences.getFloatingOffsetY(this, isLandscape, suggestedBottomDp())
        val screenW = resources.configuration.screenWidthDp
        val screenH = resources.configuration.screenHeightDp
        // 悬浮窗口的坐标宿主使用物理可见高度，而不是 configuration.screenHeightDp。
        // 后者在 IME 已显示时可能已经被宿主窗口/系统栏裁过，再拿它限制悬浮卡片会导致顶部直接被截掉。
        val physicalScreenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt()
        val statusBarDp = tryGetStatusBarHeightDp(this, window.window)
        val effectiveH = if (isFloatingMode) (physicalScreenDp - statusBarDp).coerceAtLeast(1) else screenH

        val savedWidth = SettingsPreferences.getFloatingWidthDp(this, isLandscape)
        val bottomPadding = SettingsPreferences.getKeyboardBottomPaddingDp(this)
        val fixedWidth = SettingsPreferences.getFixedWidthDp(this, isLandscape)
        val fixedOffset = SettingsPreferences.getFixedOffsetX(this, isLandscape)
        KeyboardHeightProfiles.migrateLegacy(this, isLandscape, effectiveH)
        // 新版第一次启动时只做一次：把 1.14.26–1.14.27 规则留下的越界/偏高固定高度改回 1.14.25 的默认值。
        KeyboardHeightProfiles.normalizeFixedHeightsOnce(this)
        val kbH = KeyboardHeightProfiles.selected(this, isFloatingMode, isLandscape, effectiveH)
        val minY = if (isFloatingMode) floatingMinimumBottomDp() else 0
        // Position limits must use the same automatic/manual size as rendering.
        // Old percentage heights incorrectly moved otherwise valid floating positions on reload.
        val displaySize = resolveKeyboardSize(screenW, effectiveH, minY, uiState.value.copy(
            isFloatingMode = isFloatingMode, floatingWidthDp = savedWidth,
            fixedWidthDp = fixedWidth, keyboardBottomPaddingDp = bottomPadding,
        ))
        val halfMargin = maxOf(0, (screenW - displaySize.width) / 2)
        val cardH = displaySize.height + (if (isFloatingMode) resources.configuration.floatingFooterHeightDp else 0)
        val maxY = maxOf(minY, effectiveH - cardH)
        // 转着的卡片按旋转后的规则限位（顶部条在屏内即可）；当前屏放不下这个角度就先摆正显示，不改保存值。
        val savedRotation = SettingsPreferences.getFloatingRotation(this, isLandscape)
        val rotatedBounds = com.kingzcheung.xime.ui.keyboard.floatingRotatedOffsetBounds(screenW.toFloat(),
            effectiveH.toFloat(), displaySize.width.toFloat(), cardH.toFloat(), savedRotation, minY.toFloat(), footerDp = resources.configuration.floatingFooterHeightDp)
            .takeIf { isFloatingMode && com.kingzcheung.xime.ui.keyboard.isFloatingRotated(savedRotation) && it.feasible }
        val clampedX = rotatedBounds?.clampX(loadedX.toFloat())?.roundToInt() ?: loadedX.coerceIn(-halfMargin, halfMargin)
        val clampedY = rotatedBounds?.clampY(loadedY.toFloat())?.roundToInt() ?: loadedY.coerceIn(minY, maxY)
        // 越界只在当前视口中夹紧，不让一次配置/主题刷新改写另一个方向的保存位置。
        uiState.value = uiState.value.copy(
            darkMode = SettingsPreferences.getEffectiveDarkMode(this),
            themeId = SettingsPreferences.getKeyboardTheme(this),
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
            keyboardHeightDp = kbH,
            keyboardBottomPaddingDp = bottomPadding,
            keyboardOpacity = SettingsPreferences.getKeyboardOpacity(this),
            toolbarButtons = SettingsPreferences.getToolbarButtons(this),
            menuButtons = SettingsPreferences.getMenuButtons(this),
            isFloatingMode = isFloatingMode,
            floatingOffsetX = clampedX,
            floatingOffsetY = clampedY,
            floatingRotation = if (rotatedBounds != null) savedRotation else 0f,
            floatingWidthDp = savedWidth,
            fixedWidthDp = fixedWidth,
            fixedOffsetX = fixedOffset,
        )
    }
    
    private fun registerSharedPrefsListener() {
        val prefs = SettingsPreferences.getPrefsPublic(this)
        privacy.loadManual(SettingsPreferences.isIncognito(this))
        rimeEngine.doublePinyinKeysPreedit = com.kingzcheung.xime.settings.SpellingPatches.doublePinyinRawPreedit(this)
        sharedPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith(com.kingzcheung.xime.settings.HardwareKeyboardPreferences.PREFIX) == true) {
                uiState.value = uiState.value.copy(hardwareOptions = com.kingzcheung.xime.settings.HardwareKeyboardPreferences.read(this))
            }
            when (key) {
                SettingsPreferences.KEY_EDGE_TO_EDGE_KEYBOARD, SettingsPreferences.KEY_KEYBOARD_BOTTOM_PADDING_DP,
                SettingsPreferences.KEY_KEYBOARD_BOTTOM_LOWERING_DP, SettingsPreferences.KEY_KEYBOARD_BOTTOM_EDGE_LOWERING_DP -> {
                    val decor = window.window?.decorView
                    val padding = SettingsPreferences.getKeyboardBottomPaddingDp(this@XimeInputMethodService)
                    if (uiState.value.keyboardBottomPaddingDp != padding)
                        uiState.value = uiState.value.copy(keyboardBottomPaddingDp = padding)
                    updateKeyboardBottomInsets(decor?.rootWindowInsets)
                    decor?.requestApplyInsets()
                }
                SettingsPreferences.KEY_INPUT_TEXT_LOCATION -> syncInlinePinyinLocation()
                RimeConfigHelper.DEPLOYMENT_REVISION, com.kingzcheung.xime.settings.LanguagePreferences.KEY -> {
                    val languagesOnly = key == com.kingzcheung.xime.settings.LanguagePreferences.KEY
                    // 进共享日志：键盘用着用着突然「正在加载词库」，看是哪项设置变化引起的重新准备。
                    FileLogger.w(TAG, "initRimeEngine requested by $key")
                    val pending = rimeInitializationJob
                    if (pending?.isActive == true) {
                        pending.invokeOnCompletion { serviceScope.launch { initRimeEngine(languagesOnly) } }
                    } else initRimeEngine(languagesOnly)
                }
                com.kingzcheung.xime.settings.LanguagePreferences.SCHEMAS_READY_KEY -> sessionController.updateSchemaName()
                com.kingzcheung.xime.settings.InputModes.ORDER_KEY -> {
                    uiState.value = uiState.value.copy(schemas =
                        com.kingzcheung.xime.settings.InputModes.ordered(this@XimeInputMethodService, uiState.value.schemas))
                }
                com.kingzcheung.xime.settings.CustomKeyboardLayouts.REVISION -> schemaController.applyCustomLayouts()
                com.kingzcheung.xime.settings.SpellingPatches.REVISION -> schemaController.applySpellingPatches()
                // 九键双拼的显示在引擎显示层换，下一次按键就用新设置；26 键随 REVISION 重新部署。
                com.kingzcheung.xime.settings.SpellingPatches.DOUBLE_PINYIN_RAW_PREEDIT_KEY -> {
                    rimeEngine.doublePinyinKeysPreedit = com.kingzcheung.xime.settings.SpellingPatches.doublePinyinRawPreedit(this)
                }
                // Saved custom phrases: an open session re-reads them only when its schema is selected again.
                // Not tied to an editor: the keyboard may be hidden while phrases are edited in settings.
                com.kingzcheung.xime.settings.PersonalDictManager.CUSTOM_PHRASE_REVISION -> inputCommands.submit {
                    if (!rimeEngine.reloadQueuedSchema()) return@submit
                    t9PartialSegments.clear()
                    withContext(Dispatchers.Main) { updateUI() }
                }
                SettingsPreferences.KEY_INCOGNITO -> {
                    privacy.setManual(SettingsPreferences.isIncognito(this))
                }
                // 候选开关（繁体输出、表情、时间日期、符号名、希腊字母、生僻字、中英混输）：工具栏、菜单按钮或设置页一改就写进引擎，
                // 正在打的拼音当场按新设置出候选，不用等下一个字母键（以前关了繁体直接点候选或空格，上屏的还是繁体）。
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.TRADITIONAL_OUTPUT_KEY,
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CANDIDATE_EMOJI_KEY,
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.DATETIME_CANDIDATES_KEY,
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.SYMBOL_NAMES_KEY,
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.GREEK_LETTERS_KEY,
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.CYLM_LONG_ALTERNATIVE_KEY,
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.RARE_CHARACTERS_KEY,
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.MIXED_INPUT_KEY -> inputCommands.submit {
                    if (sessionController.applyCandidateOptions(persist = true)) withContext(Dispatchers.Main) { updateUI() }
                }
                SettingsPreferences.KEY_KEY_SHAPE -> {
                    com.kingzcheung.xime.ui.theme.KeyShapes.current = SettingsPreferences.getKeyShape(this@XimeInputMethodService)
                }
                // 卡片顶角由 Compose 跟着改；模糊窗的圆角要在这里重新配一次。
                SettingsPreferences.KEY_ROUNDED_KEYBOARD_TOP, SettingsPreferences.KEY_FILL_KEYBOARD_BLANK -> updateKeyboardGlass()
                SettingsPreferences.KEY_VISUAL_STYLE, SettingsPreferences.KEY_ROUNDED_KEYBOARD_BOTTOM,
                com.kingzcheung.xime.settings.FrostedGlassPreferences.KEY_ENABLED,
                "dark_mode", "keyboard_theme", "show_bottom_buttons", "keyboard_height_dp", "keyboard_height_dp_landscape", "keyboard_bottom_padding_dp", "keyboard_opacity" -> {
                    com.kingzcheung.xime.ui.theme.VisualStyles.current = SettingsPreferences.getVisualStyle(this@XimeInputMethodService)
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                "floating_mode", "floating_mode_landscape", "floating_width_dp", "floating_width_dp_landscape",
                KeyboardHeightProfiles.FLOATING_HEIGHT, KeyboardHeightProfiles.FLOATING_HEIGHT_LANDSCAPE -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                "stt_enabled" -> {
                    uiState.value = uiState.value.copy(isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService))
                }
                SettingsPreferences.KEY_SMART_PREDICTION_ENABLED -> onPredictionSettingChanged()
                SettingsPreferences.KEY_CLIPBOARD_SYNC_ENABLED -> updateClipboardSync()
                SettingsPreferences.KEY_CLIPBOARD_SYNC_PLUGIN_ID -> {
                    stopClipboardSync()
                    updateClipboardSync()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    /** 设置驱动：智能联想开启时加载联想表，关闭时卸载。 */
    private fun onPredictionSettingChanged() {
        val enabled = SettingsPreferences.isSmartPredictionEnabled(this)
        if (enabled) {
            serviceScope.launch(Dispatchers.IO) {
                com.kingzcheung.xime.association.AssociationManager.initialize(this@XimeInputMethodService)
            }
        } else {
            com.kingzcheung.xime.association.AssociationManager.release()
        }
    }
    
    private fun saveDarkModePreference(mode: Int) {
        SettingsPreferences.setDarkMode(this, mode)
        uiState.value = uiState.value.copy(darkMode = mode)
    }
    
    fun toggleDarkMode() {
        val currentMode = uiState.value.darkMode
        val newMode = when (currentMode) {
            DARK_MODE_LIGHT -> DARK_MODE_DARK
            DARK_MODE_DARK -> DARK_MODE_LIGHT
            else -> { // DARK_MODE_SYSTEM: 切换到当前系统主题的反面
                if (isDarkTheme()) DARK_MODE_LIGHT else DARK_MODE_DARK
            }
        }
        saveDarkModePreference(newMode)
    }
    
    fun isDarkTheme(): Boolean {
        com.kingzcheung.xime.ui.theme.VisualStyles.current.dark?.let { return it }
        return when (uiState.value.darkMode) {
            DARK_MODE_DARK -> true
            DARK_MODE_SYSTEM -> {
                val nightModeFlags = resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEV_TOOLS) EnterProbe.service = java.lang.ref.WeakReference(this)
        com.kingzcheung.xime.stats.TypingStats.init(this)
        com.kingzcheung.xime.ui.keyboard.HandwritingGlassRegion.reporter = handwritingGlassRegionReporter
        com.kingzcheung.xime.ui.keyboard.CursorLineMetrics.requester = cursorLineRequester
        // 记录与打字速度有关的本机状态（用户词库体积、可选模型、内存余量），只记数量不记内容。
        serviceScope.launch(Dispatchers.IO) {
            delay(5_000)
            runCatching { FileLogger.w("InputInventory", InputInventory.describe(this@XimeInputMethodService)) }
        }
        // 安装包不是作者签名的就提示一次（每个进程一次）；读签名放后台，不拖慢键盘启动。
        serviceScope.launch {
            val integrity = withContext(Dispatchers.IO) {
                com.kingzcheung.xime.security.InstallIntegrity.check(this@XimeInputMethodService)
            }
            if (integrity == com.kingzcheung.xime.security.InstallIntegrity.Result.FOREIGN) {
                Toast.makeText(this@XimeInputMethodService, "CyIME 不是官方安装包，可能被人改过，请从官方下载页重装", Toast.LENGTH_LONG).show()
            }
        }
        // 允许 IME 窗口绘制到摄像头挖孔/刘海区域（横屏时背景覆盖全屏）
        window.window?.attributes?.layoutInDisplayCutoutMode =
            android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        savedStateRegistryController.performRestore(null)
        window.window?.decorView?.setViewTreeLifecycleOwner(this)
        window.window?.decorView?.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        
        FileLogger.init(this)
        // processAgeMs near 0: the process was started for this keyboard (killed while hidden, a cold load).
        // A large value: only the service was recreated, and the resident engine needs no reload.
        FileLogger.w(TAG, "XimeInputMethodService created processAgeMs=" +
            (android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()) +
            " engineResident=${RimeEngine.isInitialized()}" +
            // 非 0：手机「应用双开/分身/工作资料」里的输入法，是另一份数据、另一套词库编译。
            " user=${android.os.Process.myUid() / 100_000}")
        hardwareInputManager.registerInputDeviceListener(hardwareInputListener, mainHandler)
        FileLogger.i(
            TAG,
            "Device: ${AndroidPlatform.deviceLabel}, ${AndroidPlatform.versionLabel}, " +
                "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}@${resources.displayMetrics.density}"
        )
        // 这台设备按系统版本走哪些路（AndroidPlatform）；用户报问题时对照日志看，不再猜版本差异。
        FileLogger.i(TAG, "Platform: ${AndroidPlatform.summary()}")
        
        feedbackManager.initialize()
        
        loadDarkModePreference()
        registerSharedPrefsListener()
        
        serviceScope.launch {
            SchemaManager.enabledSchemasChanged.collect {
                if (inputReadiness.ticket() != null) sessionController.updateSchemaName()
            }
        }
        initRimeEngine()
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                initClipboardManager()
                initAssociationEngine()
                initSpeechRecognition()

                withContext(Dispatchers.Main) {
                    FileLogger.i(TAG, "Service initialization completed")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Initialization failed: ${e.message}")
            }
        }
    }
    
    private fun initSpeechRecognition() {
        voiceRecognitionHandler.initialize()
        serviceScope.launch {
            androidx.compose.runtime.snapshotFlow { uiState.value.voiceKeyboardLanguages }.collect {
                voiceRecognitionHandler.onInputLanguageChanged()
            }
        }
    }
    
    private fun initAssociationEngine() {
        predictionManager.initialize()
    }
    
    
    private fun getPredictionFromPlugin(contextText: String, afterWholeWord: Boolean) {
        if (!isChineseMode || !canPredictAfter(contextText)) return
        predictionManager.getPrediction(contextText, afterWholeWord)
    }

    /** 中文输出字形：拼音、双拼、仓颉、注音都跟「繁体输出」这一个开关。 */
    internal fun traditionalChineseOutput(): Boolean =
        SettingsPreferences.getPrefsPublic(this)
            .getBoolean(com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.TRADITIONAL_OUTPUT_KEY, false)
    
    private var rimeInitializationJob: kotlinx.coroutines.Job? = null

    /** [languagesOnly]：设置里只改了启用的语言。方案已部署好时静默刷新语言列表，不弹「正在准备」遮罩；
     * 真要部署（新开的语言带方案）时由 prepareAutomatically 报出在准备哪种语言。 */
    private fun initRimeEngine(languagesOnly: Boolean = false) {
        if (rimeInitializationJob?.isActive == true) return
        val quiet = languagesOnly && RimeEngine.isInitialized() && SettingsPreferences.isDeploymentDone(this)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val engineResident = RimeEngine.isInitialized()
        // 上一个服务已经把方案切好的会话（换输入法再切回、系统重建服务）：之后没部署、没换会话就不再重切。
        val liveSchemaGeneration = rimeEngine.liveSchemaGeneration()
        inputReadiness.deployment(true)
        Log.d(TAG, "initRimeEngine: Starting initialization...")
        
        // Parsing all layout YAMLs can take seconds on first launch. Keep the service
        // main thread responsive; the existing loading overlay gates input until the
        // configVersion publication and engine initialization have both completed.
        if (!quiet) uiState.value = uiState.value.copy(isDeploying = true, deploymentMessage = "正在加载键盘配置...")
        
        rimeInitializationJob = serviceScope.launch(Dispatchers.IO) {
            try {
                KeysConfigHelper.loadConfig(this@XimeInputMethodService)

                RimeEngine.setDeploymentCallback { isDeploying, message ->
                    // A native "finished" callback only means nativeInitialize returned.
                    // Deployment and schema restoration still have to finish before typing.
                    if (isDeploying) notifyDeploymentStatus(true, message)
                }

                if (!quiet) notifyDeploymentStatus(true, "正在准备词库，首次使用需要稍候...")
                RimeConfigHelper.prepareAutomatically(this@XimeInputMethodService) { notifyDeploymentStatus(true, it) }
                check(rimeEngine.ensureSession(180_000L)) { "输入会话未就绪" }

                withContext(Dispatchers.Main) {
                    val availableSchemas = rimeEngine.getAvailableSchemas()
                    val storedSchema = SettingsPreferences.getCurrentSchema(this@XimeInputMethodService)
                    val selectableSchemas = if (storedSchema.isNotBlank() && storedSchema !in availableSchemas && storedSchema != com.kingzcheung.xime.settings.InputModes.ENGLISH &&
                        (isHandwritingSchema(storedSchema) || SchemaManager.isSchemaCompiled(this@XimeInputMethodService, storedSchema)))
                        availableSchemas.toList() + storedSchema else availableSchemas.toList()
                    val savedSchema = com.kingzcheung.xime.settings.LanguagePreferences.nativeSchema(
                        storedSchema, selectableSchemas,
                        com.kingzcheung.xime.settings.LanguagePreferences.enabled(this@XimeInputMethodService))
                    if (savedSchema != storedSchema) SettingsPreferences.setCurrentSchema(this@XimeInputMethodService, savedSchema)
                    val currentSchema = rimeEngine.getCurrentSchema()
                    Log.d(TAG, "initRimeEngine: currentSchema=$currentSchema, savedSchema=$savedSchema, availableSchemas=${availableSchemas.joinToString()}")
                    
                    when {
                        isHandwritingSchema(savedSchema) -> {
                            // 手写方案：不要调 rimeEngine.switchSchema（Rime 没有手写引擎），
                            // 也不要覆盖 savedSchema（由 onStartInput 恢复 UI）
                            Log.d(TAG, "initRimeEngine: savedSchema is handwriting, keeping current Rime schema")
                            // UI 布局恢复：冷启动时第一次 onStartInput 先于引擎初始化完成，
                            // RimeEngine.isInitialized()=false 会跳过 handwriting UI 恢复，
                            // 此处必须补切，否则第一次弹出键盘停留在默认全键盘
                            val hwDir = com.kingzcheung.xime.model.ModelStorage.getModelDir(
                                this@XimeInputMethodService, "ochwpro"
                            )
                            com.kingzcheung.xime.model.ModelStorage.migrateLegacyForModel(
                                this@XimeInputMethodService, "ochwpro"
                            )
                            val modelOk = java.io.File(hwDir, "ochwpro.onnx").exists() &&
                                java.io.File(hwDir, "char_index.json").exists()
                            if (modelOk) {
                                val page = keyboardViewModel.page.value
                                val alreadyHandwriting = page is com.kingzcheung.xime.keyboard.KeyboardPage.Main &&
                                    page.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
                                if (!alreadyHandwriting) {
                                    keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
                                }
                                // 手写模型按"用键盘时加载"管理：不在此预载，
                                // HandwritingKeyboardLayout 创建时（LaunchedEffect）负责加载
                            } else {
                                FileLogger.w(TAG, "initRimeEngine: handwriting model missing, keep full keyboard")
                                android.widget.Toast.makeText(
                                    this@XimeInputMethodService,
                                    "请先下载手写模型",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        savedSchema in availableSchemas && savedSchema == currentSchema && liveSchemaGeneration != null &&
                            rimeEngine.liveSchemaGeneration() == liveSchemaGeneration -> {
                            // 会话还是上次完整切好方案的那个（期间没部署、没新建会话）：重切同一方案只会重建翻译器和词典，
                            // 内存紧时要 20 多秒，键盘一直显示「正在加载词库」（平板 11-05..11-08，用户 11-09）。
                            withContext(Dispatchers.IO) { schemaController.applyPageSizeSetting(savedSchema) }
                            FileLogger.w(TAG, "initRimeEngine: kept live schema $savedSchema")
                        }
                        savedSchema in availableSchemas -> {
                            // 即使 savedSchema == currentSchema 也要调用 switchSchema，
                            // 因为 nativeCreateSession 后 schema 的 processor/translator 等
                            // 可能未完全初始化，switchSchema 会触发完整的初始化流程
                            Log.d(TAG, "initRimeEngine: Switching to saved schema: $savedSchema")
                            withContext(Dispatchers.IO) {
                                schemaController.applyPageSizeSetting(savedSchema)
                                check(rimeEngine.switchSchema(savedSchema)) { "输入方案加载失败" }
                            }
                        }
                        SchemaManager.isSchemaCompiled(this@XimeInputMethodService, savedSchema) -> {
                            Log.d(TAG, "initRimeEngine: Schema compiled but not in get_schema_list, switching anyway")
                            withContext(Dispatchers.IO) {
                                schemaController.applyPageSizeSetting(savedSchema)
                                check(rimeEngine.switchSchema(savedSchema)) { "输入方案加载失败" }
                            }
                        }
                        availableSchemas.isNotEmpty() -> {
                            // savedSchema 不可用且未编译，退而求其次用第一个可用方案
                            val fallbackSchema = availableSchemas.first()
                            Log.d(TAG, "initRimeEngine: savedSchema '$savedSchema' not available, falling back to '$fallbackSchema'")
                            withContext(Dispatchers.IO) {
                                schemaController.applyPageSizeSetting(fallbackSchema)
                                check(rimeEngine.switchSchema(fallbackSchema)) { "输入方案加载失败" }
                            }
                            SettingsPreferences.setCurrentSchema(this@XimeInputMethodService, fallbackSchema)
                        }
                    }
                    
                    sessionController.updateSchemaName()
                    // onStartInput 在部署进行中会跳过 schema 切换与选项恢复，
                    // 部署完成后这里补齐 UI 状态，保证键盘可用
                    withContext(Dispatchers.IO) { sessionController.restorePersistedSchemaOptions() }
                    val ascii = editorInputPolicy.engineAsciiOverride() ?: rimeEngine.isAsciiMode()
                    uiState.value = uiState.value.copy(isAsciiMode = ascii)
                    keyboardViewModel.dispatch(com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(
                        ascii, rimeEngine.getCurrentSchema()))
                    updateUI()
                    inputReadiness.completeStartup()
                    uiState.value = uiState.value.copy(isDeploying = false, deploymentMessage = "")
                    // Focus may have changed while native initialization was suspended.
                    // Queue the current field's preparation before accepting its first key.
                    currentInputEditorInfo?.let {
                        EditorInputPreparation(this@XimeInputMethodService).schedule(false,
                            EditorInfoClassifier.isNumberEditor(it))
                    }
                    FileLogger.w(TAG, "initRimeEngine: ready in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms " +
                        "engineResident=$engineResident")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.e(TAG, "initRimeEngine: Failed to initialize Rime engine", e)
                notifyDeploymentStatus(true, "输入准备未完成，3 秒后自动重试")
                delay(3_000)
                withContext(Dispatchers.Main) { rimeInitializationJob = null; initRimeEngine() }
            }
        }
        
        // Watchdog reports timeout without permitting input against an unready engine.
        // withTimeout cannot cancel native JNI calls; if rimeEngine.initialize() hangs
        // in librime, the IO coroutine would block forever. This watchdog ensures the
        // user gets an explicit error; keyboard dismissal remains available.
        // 首次编译最多等 120s + ensureSession 60s + 10s 缓冲
        val attempt = rimeInitializationJob
        serviceScope.launch(Dispatchers.Main) {
            delay(190_000L)
            if (rimeInitializationJob === attempt && attempt?.isActive == true && uiState.value.isDeploying) {
                FileLogger.w(TAG, "initRimeEngine: Watchdog triggered - native init appears stuck, keeping input blocked")
                uiState.value = uiState.value.copy(
                    isDeploying = true,
                    deploymentMessage = "准备时间较长，仍在后台处理中，可先切换输入法"
                )
            }
        }
    }
    
    internal fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        inputReadiness.deployment(isDeploying)
        serviceScope.launch(Dispatchers.Main) {
            uiState.value = uiState.value.copy(
                isDeploying = inputReadiness.ticket() == null,
                deploymentMessage = message
            )
        }
    }
    
    private fun initClipboardManager() {
        Log.d(TAG, "initClipboardManager: Starting initialization...")
        try {
            clipboardManager = ClipboardManager.getInstance(this)
            clipboardItemsState.value = clipboardManager.clipboardItems.value
            quickSendItemsState.value = clipboardManager.quickSendItems.value

            serviceScope.launch {
                clipboardManager.clipboardItems.collect { items ->
                    clipboardItemsState.value = items
                }
            }
            serviceScope.launch {
                clipboardManager.quickSendItems.collect { items ->
                    quickSendItemsState.value = items
                    // 固定剪贴板不对插件开放：连条数变化也不投递 quick_send_changed 事件。
                }
            }
            serviceScope.launch {
                startClipboardSyncIfEnabled()
                PluginManager.pluginInstancesFlow.collect {
                    updateClipboardSync()
                    refreshToolbarPluginButtons()
                    closeToolPanelIfPluginGone()
                }
            }
            Log.d(TAG, "initClipboardManager: Clipboard manager initialized successfully")
        } catch (e: Exception) {
            FileLogger.e(TAG, "initClipboardManager: Failed to initialize clipboard manager", e)
        }
    }

    private fun startClipboardSyncIfEnabled() {
        if (clipboardSyncBridge != null) return
        try {
            if (!SettingsPreferences.isClipboardSyncEnabled(this)) {
                Log.d(TAG, "Clipboard sync disabled in settings")
                return
            }
            val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
            if (enabled.isEmpty()) return
            val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
            val selected = enabled.firstOrNull { it.first == preferredId } ?: enabled.first()
            val nativeWindows = selected.first == com.kingzcheung.xime.clipboard.sync.WindowsDevices.ID
            // 能力声明校验：未声明同步协议的插件不启动（manifest.capabilities.clipboard_sync.protocols）
            val syncCapabilities = ExtensionManager.getAllInstalledPlugins()
                .firstOrNull { it.id == selected.first }
                ?.capabilities?.clipboardSync
            if (!nativeWindows && syncCapabilities?.protocols.isNullOrEmpty()) {
                FileLogger.w(TAG, "Clipboard sync plugin ${selected.first} 未声明同步协议，拒绝启动")
                return
            }
            val plugin = selected.second
            clipboardSyncBridge = ClipboardSyncBridge(
                this,
                clipboardManager,
                plugin,
                pluginId = selected.first,
                foregroundPollIntervalMs = if (nativeWindows) 2000 else syncCapabilities?.foregroundPollIntervalMs ?: 0,
                maxTextBytes = if (nativeWindows) 65536 else syncCapabilities?.maxTextBytes ?: 0,
            )
            clipboardSyncBridge?.start()
            // 电脑同步只在键盘显示时找电脑、查连接：键盘正显示时下一行立刻连上，收起时不在后台跑。
            clipboardSyncBridge?.setKeyboardVisible(isInputViewShown)
            uiState.value = uiState.value.copy(clipboardSyncEnabled = true)
            Log.d(TAG, "Clipboard sync started: ${selected.first}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start clipboard sync", e)
        }
    }

    private fun stopClipboardSync() {
        if (clipboardSyncBridge?.pluginId == com.kingzcheung.xime.clipboard.sync.WindowsDevices.ID) {
            com.kingzcheung.xime.clipboard.sync.WindowsDevices.stopIfUnused()
        }
        clipboardSyncBridge?.release()
        clipboardSyncBridge = null
        uiState.value = uiState.value.copy(clipboardSyncEnabled = false)
    }

    /** 剪贴板同步设置或插件状态变化时调用，按条件动态启停。 */
    private fun updateClipboardSync() {
        if (!::clipboardManager.isInitialized) return
        if (clipboardSyncBridge == null) {
            startClipboardSyncIfEnabled()
            return
        }
        if (
            !SettingsPreferences.isClipboardSyncEnabled(this) ||
            ExtensionManager.getEnabledClipboardSyncPlugins(this).isEmpty()
        ) {
            stopClipboardSync()
            return
        }
        // 当前 bridge 使用的插件与偏好选中的插件不一致时，重启切换到偏好插件
        val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
        val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
        val shouldUse = (if (preferredId.isNotEmpty()) {
            enabled.firstOrNull { it.first == preferredId }
        } else null) ?: enabled.first()
        if (shouldUse.first != clipboardSyncBridge?.pluginId ||
            !clipboardSyncBridge!!.usesPlugin(shouldUse.second)) {
            stopClipboardSync()
            startClipboardSyncIfEnabled()
        }
    }

    /**
     * 刷新已启用插件声明的工具栏按钮到 uiState（插件启用/加载/卸载变化时调用）。
     * 两层显示控制的第一层：只有启用插件的按钮进入候选池；toolbar_buttons 偏好决定最终显示。
     */
    private fun refreshToolbarPluginButtons() {
        serviceScope.launch(Dispatchers.IO) {
            val buttons = ExtensionManager.getAllInstalledPlugins()
                .filter { SettingsPreferences.isPluginEnabled(this@XimeInputMethodService, it.id) }
                .flatMap { info ->
                    info.toolbarButtons.map { btn ->
                        ToolbarButtonItem.Plugin(
                            id = btn.id,
                            label = btn.label.ifBlank { btn.id },
                            icon = ExtensionManager.extractToolbarButtonIcon(
                                this@XimeInputMethodService, info.id, info, btn.icon
                            ) ?: ExtensionManager.extractPluginManifestIcon(this@XimeInputMethodService, info),
                            pluginId = info.id,
                            action = btn.action,
                        )
                    }
                }
            // 已经装好、按钮还在「不使用」里的（2026-11-02 之前装的）也放进菜单一次
            val autoPlaced = buttons.filter { com.kingzcheung.xime.settings.XimeIndexSource.mayAutoPlaceButtons(it.pluginId) }
            val menu = if (SettingsPreferences.placeNewPluginButtons(this@XimeInputMethodService, autoPlaced.map { it.id }))
                SettingsPreferences.getMenuButtons(this@XimeInputMethodService) else null
            withContext(Dispatchers.Main) {
                uiState.value = uiState.value.copy(toolbarPluginButtons = buttons)
                if (menu != null) uiState.value = uiState.value.copy(menuButtons = menu)
            }
        }
    }

    /** 面板打开时记录的输入框选区起止（上屏前恢复以替换原选区）。 */
    private var toolPanelSelection: Pair<Int, Int>? = null
    /** 打开面板时的输入框身份；换了输入框，面板结果不再上屏。 */
    private var toolPanelAdmission: Long? = null

    /** 面板生成轮询任务（流式生成期间持续刷新候选）。 */
    private var toolPanelPollJob: Job? = null

    /** 翻译栏这类 live 面板（manifest `display: live`）：停手后生成，结果写进输入框。 */
    internal val liveToolBar by lazy { LiveToolBar(this) }

    /** 打开面板时的输入框还在：面板结果才能写进去。 */
    internal fun toolPanelEditorStillCurrent(): Boolean =
        toolPanelAdmission?.let { inputReadiness.accepts(it) } == true

    /**
     * 插件工具栏按钮 action=open_panel 的宿主入口：打开该插件的通用面板。
     * 记录选区、按优先级收集上下文预填、向插件取初始面板状态并渲染。
     *
     * [sourceText]：面板的原文用这段字（剪贴板长按「翻译」），不取选区、输入框和剪贴板；点结果插到光标处，
     * 不替换输入框里原有的字。[startAction]：面板打开后自动点这个动作（如 `mode:translate`）。
     */
    internal fun openToolPanel(pluginId: String, sourceText: String? = null, startAction: String? = null) {
        if (toolPanelBlocked()) {
            Toast.makeText(this, "工具面板不支持在悬浮/横屏模式下使用", Toast.LENGTH_SHORT).show()
            return
        }
        closeQuickSend()
        // 翻译栏开着又点了插件按钮：已经写进输入框的译文先定下来
        if (uiState.value.toolPanelDisplay == ToolResult.LIVE.name) liveToolBar.close()
        // 打开面板前清理宿主输入框残留输入态（未上屏的拼音/英文），
        // 避免旧组合混入面板输入、或面板关闭后覆盖宿主输入框中段文字。
        val pending = candidateState.value
        if (pending.isComposing || pending.inputText.isNotEmpty() || pending.pendingEnglishText.isNotEmpty()) {
            rimeEngine.endComposition(isT9Schema(uiState.value.currentSchemaId))
            endComposingInputBox()
            candidateState.value = candidateState.value.copy(
                candidates = emptyList(),
                candidateComments = emptyList(),
                associationCandidates = emptyList(),
                pendingEnglishText = "",
                inputText = "",
                candidateActions = emptyList(),
                preeditText = "",
                isComposing = false,
                isShowingRecentClipboard = false,
                hasNextPage = false,
                hasPrevPage = false
            )
        }
        toolPanelSelection = if (sourceText != null) null else readCurrentSelection()
        toolPanelAdmission = inputReadiness.ticket()
        val display = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.capabilities?.tool?.display
        val live = display == ToolResult.LIVE
        // AI 改写这类插件（manifest `context: field`）：没选字就改整个输入框，点结果时替换整框，不拿剪贴板。
        val wholeField = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.capabilities?.tool?.wholeField == true
        // 翻译栏从空栏开始，译文写在光标处，不拿选区、剪贴板
        if (live) toolPanelSelection = null
        val contextText = when {
            live -> ""
            sourceText != null -> sourceText
            wholeField && toolPanelSelection == null ->
                readWholeField()?.let { (text, range) -> toolPanelSelection = range; text } ?: ""
            else -> collectToolPanelContext()
        }
        val pluginName = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.name ?: pluginId
        if (live) liveToolBar.reset()
        val epoch = uiState.value.toolPanelRequestEpoch + 1
        uiState.value = uiState.value.copy(
            toolPanelVisible = true,
            toolPanelInputFocused = display != ToolResult.PASSIVE,
            toolPanelPluginId = pluginId,
            toolPanelTitle = pluginName,
            toolPanelPrefillText = contextText,
            toolPanelItems = emptyList(),
            toolPanelDisplay = display?.name,
            toolPanelUiNodes = null,
            toolPanelRequestEpoch = epoch,
            enterKeyText = when (display) {
                ToolResult.PASSIVE -> "发送"
                ToolResult.LIVE -> "完成"
                else -> "生成"
            },
        )
        if (display == ToolResult.PASSIVE) {
            // 纯展示面板与表情/符号同级：Overlay 全屏覆盖键盘，不撑高候选栏上方区域
            keyboardViewModel.showOverlay(OverlayRoute.ToolPanel)
        } else {
            ToolPanelEditTextHolder.editText?.let { et ->
                et.setText(contextText)
                et.setSelection(contextText.length)
            }
        }
        // 插件的初始面板状态在后台取（审计 F08：以前在主线程同步等插件，慢插件冻住整个键盘）。
        serviceScope.launch(Dispatchers.IO) {
            val pluginState = runCatching {
                (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)?.getPanelState(contextText)
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelRequestEpoch != epoch || !uiState.value.toolPanelVisible) return@withContext
                // 只在用户还没改过预填时换成插件给的预填（面板会把输入框改回 toolPanelPrefillText）。
                val editor = ToolPanelEditTextHolder.editText
                val untouched = editor == null || editor.text?.toString() == contextText
                val prefill = pluginState.inputText.takeIf { it.isNotBlank() && untouched } ?: uiState.value.toolPanelPrefillText
                uiState.value = uiState.value.copy(
                    toolPanelPrefillText = prefill,
                    toolPanelItems = pluginState.items,
                    toolPanelUiNodes = pluginState.ui,
                )
                if (display != ToolResult.PASSIVE && untouched && editor != null && prefill != contextText) {
                    editor.setText(prefill)
                    editor.setSelection(prefill.length)
                }
                startAction?.let { dispatchToolPanelAction(it) }
            }
        }
    }

    /** 悬浮键盘、横屏下工具面板不开（面板高度按竖屏固定键盘算）。 */
    private fun toolPanelBlocked(): Boolean = uiState.value.isFloatingMode ||
        resources.configuration.screenWidthDp > resources.configuration.screenHeightDp

    /**
     * 剪贴板长按「翻译」用的插件：装着并开着的 AI 改写（它的「翻译」动作，用户自己的接口）。
     * 没有，或者现在开不了工具面板（悬浮、横屏），就返回 null，长按菜单里不显示「翻译」。
     */
    internal fun clipboardTranslatePluginId(): String? = CLIPBOARD_TRANSLATE_PLUGIN_ID.takeIf { id ->
        !toolPanelBlocked() && SettingsPreferences.isPluginEnabled(this, id) &&
            ExtensionManager.getAllInstalledPlugins().any { it.id == id }
    }

    /** 剪贴板长按「翻译」：打开 AI 改写，原文是这条记录，自动翻译；点译文插到光标处。 */
    internal fun translateClipboardText(text: String) {
        val pluginId = clipboardTranslatePluginId() ?: return
        if (text.isBlank()) return
        openToolPanel(pluginId, sourceText = text, startAction = "mode:translate")
    }

    /**
     * passive 纯展示面板的 action 点击：通知插件（onPanelAction）后单次重拉
     * getPanelState 刷新 ui 节点（action 改变数据后面板立即反映）。
     * 先置 loading 再执行：同步生成（插件 onPanelAction 阻塞返回）期间面板显示加载态。
     */
    internal fun dispatchToolPanelAction(actionId: String) {
        val pluginId = uiState.value.toolPanelPluginId
        if (actionId == TOOL_PANEL_SETTINGS_ACTION) {
            // 宿主保留的动作：面板里的「设置」直接进这个插件的设置页
            closeToolPanel()
            try {
                startActivity(com.kingzcheung.xime.MainActivity.buildPluginSettingsIntent(this, pluginId))
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to open plugin settings", e)
            }
            return
        }
        if (actionId == TOOL_PANEL_EDIT_ACTION) {
            // 面板里点了可改的原文：收起面板，在键盘上方打开输入栏改字，回车交回插件（11-03「输入框不合格」）。
            val text = uiState.value.toolPanelUiNodes?.firstOrNull { it.key == TOOL_PANEL_EDIT_ACTION }?.value.orEmpty()
            keyboardViewModel.closeOverlay()
            uiState.value = uiState.value.copy(
                toolPanelDisplay = TOOL_PANEL_EDITING_DISPLAY,
                toolPanelInputFocused = true,
                toolPanelPrefillText = text,
                enterKeyText = "完成",
            )
            ToolPanelEditTextHolder.editText?.let { et ->
                et.setText(text)
                et.setSelection(text.length)
            }
            return
        }
        val epoch = uiState.value.toolPanelRequestEpoch
        uiState.value = uiState.value.copy(toolPanelLoading = true)
        serviceScope.launch(Dispatchers.IO) {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin ?: return@launch
            // runCatching：插件异常（Lua 超时/中毒）时不卡 loading，面板恢复可交互
            val state = runCatching {
                plugin.onPanelAction(actionId)
                plugin.getPanelState("")
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelRequestEpoch == epoch && uiState.value.toolPanelVisible) {
                    uiState.value = uiState.value.copy(
                        toolPanelUiNodes = state?.ui,
                        toolPanelItems = state?.items ?: uiState.value.toolPanelItems,
                        toolPanelLoading = state?.loading ?: false,
                    )
                    // 翻译栏换了目标语言：栏里的字按新设置重新生成
                    if (uiState.value.toolPanelDisplay == ToolResult.LIVE.name) liveToolBar.retranslate()
                }
            }
        }
    }

    /** 改完原文按回车：把字交给插件（onPanelInput），回到全屏面板并刷新。 */
    private fun finishToolPanelEdit() {
        val pluginId = uiState.value.toolPanelPluginId
        val text = ToolPanelEditTextHolder.editText?.text?.toString() ?: uiState.value.toolPanelPrefillText
        val epoch = uiState.value.toolPanelRequestEpoch
        uiState.value = uiState.value.copy(
            toolPanelDisplay = ToolResult.PASSIVE.name,
            toolPanelInputFocused = false,
            toolPanelLoading = true,
            enterKeyText = "发送",
        )
        keyboardViewModel.showOverlay(OverlayRoute.ToolPanel)
        serviceScope.launch(Dispatchers.IO) {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin ?: return@launch
            val state = runCatching {
                plugin.onPanelInput(text)
                plugin.getPanelState("")
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelRequestEpoch == epoch && uiState.value.toolPanelVisible) {
                    uiState.value = uiState.value.copy(
                        toolPanelUiNodes = state?.ui ?: uiState.value.toolPanelUiNodes,
                        toolPanelItems = state?.items ?: uiState.value.toolPanelItems,
                        toolPanelLoading = state?.loading ?: false,
                    )
                }
            }
        }
    }

    /**
     * Opens the clipboard search bar above the keyboard. It types through the tool-panel input
     * channel; the engine is incognito meanwhile, so nothing typed into search is learned.
     */
    internal fun openClipboardSearch() {
        if (uiState.value.toolPanelVisible) closeToolPanel()
        // One keyboard editor at a time: an open edit form would take the chosen candidates, and
        // closing it later reopened the board under the search (two clipboards, user 10-23).
        closeQuickSend()
        keyboardViewModel.closeOverlay()
        // Its own flag: a new input field (restartInput, rotation) rewrites sensitiveEditor, and
        // restoring a saved copy of it on close once let a password field learn.
        privacy.setClipboardSearch(true)
        clipboardManager.boardQuery.value = ""
        uiState.value = uiState.value.copy(clipboardSearchVisible = true, toolPanelInputFocused = true, enterKeyText = "完成")
        fitClipboardSearchOnShortScreen()
    }

    /** Search replaced the keyboard's mode and opacity on a short screen; put back when it closes. */
    private var clipboardSearchFloated = false

    /**
     * When even the one-row search bar leaves no row of results (a short landscape screen or a very
     * tall keyboard), search runs in the floating keyboard at its smallest height, a little see-through
     * (user, 2026-10-22). Nothing is saved: closing search puts the keyboard back.
     */
    internal fun fitClipboardSearchOnShortScreen() {
        val state = uiState.value
        if (state.isCompact || state.showKeyboardResize) return
        val density = resources.displayMetrics.density
        val bottom = (if (state.isFloatingMode) 0 else state.keyboardBottomPaddingDp) +
            keyboardBottomSpaceDp(state)
        val available = resources.configuration.screenHeightDp - bottom
        if (!com.kingzcheung.xime.ui.keyboard.clipboardSearchNeedsFloating(state.keyboardHeightDp, available)) return
        val landscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val hostHeight = ((resources.displayMetrics.heightPixels / density).roundToInt() -
            tryGetStatusBarHeightDp(this, window.window)).coerceAtLeast(1)
        clipboardSearchFloated = true
        uiState.value = state.copy(
            clipboardSearchFloatingHeightDp = com.kingzcheung.xime.ui.keyboard.floatingStoredHeightBounds(hostHeight, landscape).first,
            keyboardOpacity = minOf(state.keyboardOpacity, com.kingzcheung.xime.ui.keyboard.CLIPBOARD_SEARCH_FLOATING_OPACITY))
        restoreKeyboardDisplayMode(true, persist = false)
    }

    private fun restoreDisplayAfterClipboardSearch() {
        if (!clipboardSearchFloated) return
        clipboardSearchFloated = false
        uiState.value = uiState.value.copy(clipboardSearchFloatingHeightDp = 0)
        // The saved mode and opacity of the current orientation: after a rotation the old ones no longer apply.
        val landscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        restoreKeyboardDisplayMode(SettingsPreferences.isFloatingMode(this, landscape), persist = false)
        uiState.value = uiState.value.copy(keyboardOpacity = SettingsPreferences.getKeyboardOpacity(this))
    }

    internal fun closeClipboardSearch(openBoard: Boolean, keepQuery: Boolean = false) {
        if (!uiState.value.clipboardSearchVisible) return
        stopVoiceForClosedEditor(ToolPanelEditTextHolder.editText)
        if (!keepQuery) clipboardManager.boardQuery.value = ""
        if (candidateState.value.isComposing) {
            keyRouter.postRimeJob { keyRouter.endQueuedComposition(); withContext(Dispatchers.Main) { updateUI() } }
        }
        uiState.value = uiState.value.copy(clipboardSearchVisible = false, toolPanelInputFocused = false,
            enterKeyText = editorEnterKeyText())
        ToolPanelEditTextHolder.editText = null
        privacy.setClipboardSearch(false)
        restoreDisplayAfterClipboardSearch()
        if (openBoard) keyboardViewModel.showOverlay(OverlayRoute.Clipboard(0))
    }

    /** Enter in the search bar: the keyboard goes away and the clipboard keeps showing the matches. */
    internal fun submitClipboardSearch() {
        clipboardManager.boardQuery.value = ToolPanelEditTextHolder.editText?.text?.toString()?.trim().orEmpty()
        closeClipboardSearch(openBoard = true, keepQuery = true)
    }

    /** A result goes through the normal clipboard paste, so a fixed record stays private. */
    internal fun selectClipboardSearchResult(text: String) {
        feedbackManager.playClipboardCommitSound()
        // "Close after paste" off: back to the clipboard still showing these matches, to paste more.
        val stay = !com.kingzcheung.xime.ui.menubar.clipboardClosesAfterPaste(this)
        closeClipboardSearch(openBoard = stay, keepQuery = stay)
        textCommit.selectClipboardItem(text)
    }

    /** 正对着键盘自己的框（翻译栏、剪贴板搜索）说话时把框关了：这次语音跟着结束，后面的字不再写进关掉的框。 */
    private fun stopVoiceForClosedEditor(box: android.widget.EditText?) {
        if (box == null || voiceTarget.editor !== box || !(uiState.value.isVoiceMode || voiceRecordingStarted)) return
        voiceRecognitionHandler.endInputSession()
        restoreAfterVoiceFinish()
    }

    internal fun closeToolPanel() {
        stopVoiceForClosedEditor(ToolPanelEditTextHolder.editText)
        // 翻译栏：写进输入框的译文留下（定下来），没译完的不再写
        if (uiState.value.toolPanelDisplay == ToolResult.LIVE.name) liveToolBar.close()
        toolPanelSelection = null
        toolPanelAdmission = null
        stopToolPanelPoll()
        // 面板相关 Overlay 页面（ToolPanel 纯展示页）开着时联动关闭
        val page = keyboardViewModel.page.value
        if (page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay &&
            page.route == OverlayRoute.ToolPanel
        ) {
            keyboardViewModel.closeOverlay()
        }
        uiState.value = uiState.value.copy(
            toolPanelVisible = false,
            toolPanelInputFocused = false,
            toolPanelPluginId = "",
            toolPanelTitle = "",
            toolPanelPrefillText = "",
            toolPanelItems = emptyList(),
            toolPanelDisplay = null,
            toolPanelUiNodes = null,
            toolPanelLoading = false,
            enterKeyText = editorEnterKeyText(),
        )
        ToolPanelEditTextHolder.editText = null
    }

    /**
     * 面板候选条目上屏：有选区时先恢复选区再提交（替换原选区），无选区时光标处追加。
     * 直接经 InputConnection 提交，不走 commitText 重定向（避免注入回面板输入框）。
     */
    internal fun commitToolPanelItem(text: String) {
        // 面板打开后输入框换过（restartInput 不经 onFinishInput 也算）：旧选区属于旧正文，不能覆盖新正文（审计 F08）。
        val admission = toolPanelAdmission
        val ic = currentInputConnection
        if (ic == null || admission == null || !inputReadiness.accepts(admission)) {
            closeToolPanel()
            return
        }
        val pluginId = uiState.value.toolPanelPluginId
        val itemId = uiState.value.toolPanelItems.firstOrNull { it.text == text }?.id ?: text
        serviceScope.launch(Dispatchers.IO) {
            (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)?.onPanelItemClick(itemId)
        }
        toolPanelSelection?.let { (start, end) ->
            runCatching { ic.setSelection(start, end) }
        }
        ic.commitText(text, 1)
        if (isChineseMode) {
            predictionManager.appendCommittedText(text)
            predictionManager.recordInput(text)
        }
        closeToolPanel()
    }

    /**
     * 面板触发生成：通知插件输入变化并触发 generate，异步轮询取回最新面板状态。
     * 同步生成（智能回复）阻塞返回后一次取回；流式生成（帮写）期间插件 loading=true，
     * 宿主持续轮询刷新，直到 loading 结束或面板关闭。
     * 代际号防旧结果回填：新请求/重开会递增 epoch，旧结果回来时检测到已过期则丢弃。
     */
    internal fun triggerToolPanelGenerate() {
        val pluginId = uiState.value.toolPanelPluginId
        // passive 纯展示面板：无生成语义，enter 不触发（数据由事件驱动 + InfoPanel 点击 action 刷新）
        if (uiState.value.toolPanelDisplay == ToolResult.PASSIVE.name) {
            return
        }
        if (uiState.value.toolPanelDisplay == TOOL_PANEL_EDITING_DISPLAY) {
            finishToolPanelEdit()
            return
        }
        if (uiState.value.toolPanelDisplay == ToolResult.LIVE.name) {
            // 翻译栏回车：等这句译完定下来，清空栏接着打下一句
            liveToolBar.finish()
            return
        }
        // 使用前授权检测：插件有未授权网络域名时先引导授权，不发起请求
        if (!com.kingzcheung.xime.plugin.PluginNetworkAuthHelper.ensureAuthorized(this, pluginId)) {
            return
        }
        val inputText = ToolPanelEditTextHolder.editText?.text?.toString() ?: ""
        // 捕获当前代际号（openToolPanel 时递增）。轮询期间持续对比：
        // 面板被重新打开（epoch 递增）即视为过期，丢弃本轮结果。
        val epoch = uiState.value.toolPanelRequestEpoch
        val epochStartTime = System.currentTimeMillis()
        toolPanelPollJob?.cancel()
        toolPanelPollJob = serviceScope.launch(Dispatchers.IO) {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin
            plugin?.onPanelInput(inputText)
            plugin?.onPanelAction("generate")
            while (uiState.value.toolPanelVisible) {
                val state = plugin?.getPanelState(inputText) ?: break
                val items = state.items
                val loading = state.loading
                withContext(Dispatchers.Main) {
                    if (uiState.value.toolPanelRequestEpoch == epoch) {
                        uiState.value = uiState.value.copy(
                            toolPanelItems = items,
                            toolPanelLoading = loading,
                        )
                    }
                }
                if (!loading) break
                delay(200)
            }
            // 结果交互：生成结束后按结果显示方式决策——
            //   display=DIRECT → 直接上屏替换选区并关闭面板（AI 翻译/帮写等单结果场景，无需点击）
            //   display=PASSIVE → 面板本身全屏展示 items（InfoPanel 点选上屏），无需分派
            //   display=null（未声明）→ 直接上屏（旧行为为按数量兜底开页面，页面已并入 passive 面板）
            //   空结果 → 保持面板（用户可重新生成）
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelVisible) {
                    val items = uiState.value.toolPanelItems
                    when {
                        items.isEmpty() -> {
                            // 空结果：若非静默失败（插件刚记录了错误），Toast 告知用户原因
                            val lastError = com.kingzcheung.xime.plugin.core.security.PluginErrorLog
                                .getLastError(pluginId)
                            val errorMessage = lastError?.message
                            if (!errorMessage.isNullOrEmpty() &&
                                lastError.timestamp >= epochStartTime
                            ) {
                                android.widget.Toast.makeText(
                                    this@XimeInputMethodService,
                                    errorMessage,
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        else -> commitToolPanelItem(items[0].text)
                    }
                }
            }
        }
    }

    private fun stopToolPanelPoll() {
        toolPanelPollJob?.cancel()
        toolPanelPollJob = null
    }

    /**
     * 面板所属插件被禁用/卸载时自动关闭面板（工具栏按钮候选池同步移除，
     * toolbar_buttons 偏好中残留 id 匹配不到自然不显示，无需清理偏好）。
     */
    private fun closeToolPanelIfPluginGone() {
        val state = uiState.value
        if (!state.toolPanelVisible) return
        val pluginId = state.toolPanelPluginId
        if (pluginId.isBlank()) return
        val stillEnabled = ExtensionManager.getAllInstalledPlugins().any {
            it.id == pluginId && SettingsPreferences.isPluginEnabled(this, it.id)
        }
        if (!stillEnabled) {
            closeToolPanel()
        }
    }

    /** 读取当前输入框选区（起止不等时返回，供上屏替换原选区）。 */
    private fun readCurrentSelection(): Pair<Int, Int>? {
        val ic = currentInputConnection ?: return null
        return runCatching {
            val req = android.view.inputmethod.ExtractedTextRequest()
            val extracted = ic.getExtractedText(req, 0)
            if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd > extracted.selectionStart) {
                Pair(extracted.selectionStart, extracted.selectionEnd)
            } else null
        }.getOrNull()
    }

    /** 整个输入框的文字和范围；输入框只给出一段（startOffset > 0）或是空的就返回 null，不冒险替换。 */
    private fun readWholeField(): Pair<String, Pair<Int, Int>>? {
        val ic = currentInputConnection ?: return null
        return runCatching {
            val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            val text = extracted?.text?.toString()
            if (extracted == null || extracted.startOffset != 0 || text.isNullOrBlank()) null
            else text to (0 to text.length)
        }.getOrNull()
    }

    /** 收集面板上下文：仅选中文本；未选中时返回空串（不预填输入框全文/剪贴板，待用户自行输入）。 */
    /**
     * 工具面板上下文收集（选区 > 输入框选区 > 剪贴板）：
     * 对方消息通常来自聊天 App 复制而非输入框选区，剪贴板兜底是 AI 回复等
     * 插件拿到上下文的关键路径（插件契约见 plugins/ai-reply/main.lua）。
     */
    private fun collectToolPanelContext(): String {
        val ic = currentInputConnection
        if (ic != null) {
            runCatching {
                val sel = ic.getSelectedText(0)?.toString()
                if (!sel.isNullOrBlank()) return sel
            }
            runCatching {
                val req = android.view.inputmethod.ExtractedTextRequest()
                val extracted = ic.getExtractedText(req, 0)
                if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd > extracted.selectionStart) {
                    val t = extracted.text?.toString()
                    if (t != null) {
                        val s = extracted.selectionStart.coerceIn(0, t.length)
                        val e = extracted.selectionEnd.coerceIn(s, t.length)
                        if (e > s) return t.substring(s, e)
                    }
                }
            }
        }
        // 剪贴板兜底：无选区/无输入连接时取系统剪贴板
        runCatching {
            if (::clipboardManager.isInitialized) {
                clipboardManager.getCurrentClipboardText()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun ensureClipboardManagerInitialized() {
        if (!::clipboardManager.isInitialized) {
            Log.d(TAG, "ensureClipboardManagerInitialized: Initializing clipboard manager synchronously")
            try {
                clipboardManager = ClipboardManager.getInstance(this)
                clipboardItemsState.value = clipboardManager.clipboardItems.value
                quickSendItemsState.value = clipboardManager.quickSendItems.value
                Log.d(TAG, "ensureClipboardManagerInitialized: Clipboard manager initialized")
            } catch (e: Exception) {
                FileLogger.e(TAG, "ensureClipboardManagerInitialized: Failed to initialize clipboard manager", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        keyboardContainer = VoiceKeyboardContainer(
            context = this,
            acceptTouchDown = { downTime ->
                (uiState.value.isDeploying || inputReadiness.acceptsEvent(downTime)).also { accepted ->
                    if (!accepted) noteInputDropped(if (inputReadiness.ticket() == null) "touch_not_ready" else "touch_before_ready")
                }
            },
            allowPreviewTouch = ::allowSettingsPreviewTouch,
            uiStateProvider = { uiState.value },
            onUiStateChanged = { newState -> uiState.value = newState },
            onPerformVibration = { view -> feedbackManager.hapticFeedback(view) },
            onPerformUndo = { pendingVoiceAction = { textCommit.performUndo() } },
            onPerformSearch = { pendingVoiceAction = { textCommit.performSearch() } },
            onStopRecognition = {
                endVoiceSession()
            },
            isRecording = { voiceRecordingStarted },
            setRecording = { voiceRecordingStarted = it },
            onVoiceDismiss = {
                val action = pendingVoiceAction
                pendingVoiceAction = null
                action?.invoke()
                endVoiceSession()
            },
            onTouchCancel = {
                uiState.value = uiState.value.copy(
                    swipeCancelEpoch = uiState.value.swipeCancelEpoch + 1
                )
            }
        )
        // 编码气泡绘制在候选栏上方（栏外，drawBehind 负坐标）：insets 重构后容器
        // 物理高度 = Compose 内容总高（gravity BOTTOM），候选栏紧贴容器顶边，
        // 气泡落在容器顶边之外——关闭容器裁剪，气泡才能画进 inputArea 的空白区。
        // 仅影响越界绘制裁剪，不触碰容器高度/insets 计算路径。
        keyboardContainer.clipChildren = false

        updateKeyboardBottomInsets(window.window?.decorView?.rootWindowInsets)
        keyboardContainer.observeKeyboardBottomInsets(window.window, ::updateKeyboardBottomInsets)

        val composeView = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            composeViewRef = this
            // 键盘里所有能滑的列表都不要边缘拉伸回弹，见 NoKeyboardOverscroll。
            setContent { com.kingzcheung.xime.ui.keyboard.NoKeyboardOverscroll {
              androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val inputWindowWidthDp = maxWidth.value.roundToInt().coerceAtLeast(1)
                val state = uiState.value
                // Reloaded key gaps/rows and split changes affect automatic geometry even
                // when the active input profile and stored dimensions remain unchanged.
                KeysConfigHelper.configVersion.collectAsState().value
                val keyboardPrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences(isDarkTheme())
                val frostedGlass = keyboardPrefs.frostedGlass
                // 尺寸调整中的透明度控制整块键盘；玻璃表面参数仍由独立设置管理。
                val keyboardLayerOpacity = state.keyboardOpacity
                val page by keyboardViewModel.page.collectAsState(com.kingzcheung.xime.keyboard.KeyboardPage.Main(com.kingzcheung.xime.keyboard.MainType.FULL))
                LaunchedEffect(page) {
                    if ((page as? KeyboardPage.Overlay)?.route !is OverlayRoute.Clipboard && clipboardKeyboard.active) {
                        clipboardKeyboard.reset()
                        clipboardRestoreCompact = false
                    }
                }
                val isHandwritingMode = (page.underlyingPage() as? KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
                val handwritingExpansion = com.kingzcheung.xime.ui.keyboard.rememberHandwritingExpansion(
                    isHandwritingMode && !state.showKeyboardResize && !state.isCompact,
                    state.handwritingExpanded, state.inputSessionId)
                val handwritingExpanded = handwritingExpansion.active
                val clipboardPanelOpen = clipboardPanelCanExpand(page, state.showKeyboardResize, state.isCompact)
                val imagePanelExpanded = clipboardPanelOpen && state.clipboardImagesExpanded
                androidx.compose.runtime.LaunchedEffect(isHandwritingMode, state.isCompact, state.voiceCollapsed) {
                    if (!isHandwritingMode && uiState.value.handwritingExpanded)
                        uiState.value = uiState.value.copy(handwritingExpanded = false)
                    // 切回手写、从工具栏或语音悬浮条回到键盘时按上次的全屏/半屏打开；收起状态下不动。
                    else if (isInputViewShown) restoreHandwritingFullscreen()
                }
                val isDarkTheme = isDarkTheme()
                val roundedKeyboardBottom = com.kingzcheung.xime.ui.keyboard.rememberRoundedKeyboardBottom()
                // 「柔化顶部边缘」只给固定键盘、顶部直角时（顶部圆角的缺口里没法渐变；设置页两者互锁）。
                val softTopEdge = com.kingzcheung.xime.ui.keyboard.rememberGlassSoftTopEdge()
                val roundedKeyboardTop = com.kingzcheung.xime.ui.keyboard.rememberRoundedKeyboardTop()
                val glassTopFeather = frostedGlass.enabled && softTopEdge && !roundedKeyboardTop && !state.isCompact &&
                    !state.voiceCollapsed && !state.isFloatingMode && !handwritingExpanded && !state.showKeyboardResize
                // A see-through keyboard showing a panel (clipboard, emoji, symbols, editing, clipboard
                // search) blurs the app behind it, so the panel's text is not read through the app's.
                val panelBackdrop = com.kingzcheung.xime.ui.theme.panelNeedsBackdropBlur(frostedGlass.enabled,
                    page is KeyboardPage.Overlay || state.clipboardSearchVisible, keyboardLayerOpacity)
                SideEffect {
                    updateGlassOpacity(if (state.isCompact || state.voiceCollapsed || panelBackdrop) 1f else keyboardLayerOpacity)
                    updateGlassConfiguration(if (panelBackdrop) frostedGlass.copy(enabled = true) else frostedGlass, when {
                        state.isCompact || state.voiceCollapsed -> "hardware"
                        state.isFloatingMode -> "floating"
                        else -> "fixed"
                    })
                    if (state.isCompact || state.voiceCollapsed || state.isFloatingMode || roundedKeyboardBottom) {
                        updateGlassSurface("navigation", null)
                    }
                    updateGlassTopFeather(glassTopFeather)
                }
                val screenHeightDp = resources.configuration.screenHeightDp
                val physicalScreenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt()
                val statusBarHeightDp = tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)
                val visibleNavBarHeightDp = tryGetVisibleNavBarHeightDp(this@XimeInputMethodService, window.window)
                // 用物理屏幕高度减去状态栏，保证不同 Android 版本一致
                val effectiveScreenH = if (state.isFloatingMode || state.showKeyboardResize || state.isCompact || state.voiceCollapsed) (physicalScreenDp - statusBarHeightDp).coerceAtLeast(1) else screenHeightDp
                val windowVisibleHeightDp = effectiveScreenH
                // 浮动窗口使用物理屏高，底部始终预留实际系统栏；不能根据配置屏高猜测已扣除。
                val floatingMinY = floatingMinimumBottomDp()
                val callbacks = rememberImeKeyboardCallbacks(this@XimeInputMethodService, floatingMinY, state, effectiveScreenH)
                keyboardCallbacks = callbacks
                if (settingsPreviewEditor && KeyboardPanelLaunch.previewVisible && KeyboardPanelLaunch.pendingPanel != null)
                    SideEffect { openPendingKeyboardPanel() }
                // The input controller belongs to the editor session, including compact-only starts.
                keyboardViewModel.bindT9Controller(callbacks)

                val screenWidthDp = inputWindowWidthDp
                val screenIsLandscape = screenWidthDp > screenHeightDp
                val heightBounds = KeyboardHeightProfiles.fixedBounds(this@XimeInputMethodService, screenIsLandscape)
                val activeBottomDp = keyboardBottomSpaceDp(state)
                val minimumBottomDp = imeBottomSpaceDp(fixedMinimumInsetPxState.value, resources.displayMetrics.density)
                val editingFixedBottom = state.showKeyboardResize && !state.isFloatingMode
                // 「底部位置」三档的留白都能调到 0（11-08），键盘调节的底边也一样能拖到屏幕底边（11-10「调不了」）：
                // 只剩没选过底部位置、又关着贴底的旧状态照原来从系统按钮上沿量。
                val edgeKeyboard = SettingsPreferences.edgeToEdgeKeyboard(this@XimeInputMethodService) ||
                    SettingsPreferences.keyboardBottomPosition(this@XimeInputMethodService) != SettingsPreferences.KeyboardBottomPosition.AUTO
                // 调节固定键盘时底边从哪里量起（11-03「用户点到哪儿就是哪儿」）：从屏幕底边量，底边能一直拖到屏幕底边，
                // 调低过的也从它现在真实的位置开始，确认后停在拖到的地方。
                val previewInsetDp = if (!editingFixedBottom) activeBottomDp else if (edgeKeyboard) 0 else minimumBottomDp
                // 「重置」回到的位置，和确认时换算校准值的基准：贴底时是贴底位置，不贴底时是系统建议。
                val defaultBottomPadding = if (!editingFixedBottom) 0 else if (edgeKeyboard) minimumBottomDp
                    else (imeBottomSpaceDp(bottomInsetPxState.value, resources.displayMetrics.density) - minimumBottomDp).coerceAtLeast(0)
                val geometryBottomSpace = if (state.isFloatingMode) floatingMinY else
                    state.keyboardBottomPaddingDp + activeBottomDp
                val protectedSize = resolveKeyboardSize(screenWidthDp, effectiveScreenH, geometryBottomSpace, state)
                val resetSize = resolveKeyboardSize(screenWidthDp, effectiveScreenH,
                    if (state.isFloatingMode) floatingMinY else previewInsetDp + defaultBottomPadding,
                    state, reset = true)
                val keyboardHeight = protectedSize.height
                val handwritingFraction = handwritingExpansion.fraction
                fun handwritingSize(normal: Int, full: Int) = (normal + (full - normal) * handwritingFraction).roundToInt()
                val handwritingWidth = handwritingSize(protectedSize.width, screenWidthDp)
                val handwritingNormalX = if (state.isFloatingMode) state.floatingOffsetX else protectedSize.offsetX
                val handwritingOffsetX = handwritingSize(handwritingNormalX, 0)
                val contentBottomPaddingDp = if (handwritingExpanded || state.isFloatingMode) 0 else state.keyboardBottomPaddingDp
                val floatingDragBarHeight = if (state.isFloatingMode && !handwritingExpanded) resources.configuration.floatingFooterHeightDp else 0
                // Keep a floating panel's bottom in place while it grows, and reserve its drag bar.
                val clipboardBottomSpace = if (state.isFloatingMode) {
                    state.floatingOffsetY.coerceIn(floatingMinY,
                        (effectiveScreenH - keyboardHeight - floatingDragBarHeight).coerceAtLeast(floatingMinY)) + floatingDragBarHeight
                } else fixedClipboardBottomDp(activeBottomDp, visibleNavBarHeightDp) + contentBottomPaddingDp
                // 工具面板、翻译栏开着时剪贴板照样在它们下面（见下面 toolPanelExtra），上拉的最高处给它们留出这一截。
                val toolPanelAboveBar = com.kingzcheung.xime.ui.keyboard.toolPanelExtraHeight(
                    state.toolPanelVisible, state.toolPanelDisplay)
                val clipboardExpansion = rememberClipboardPanelExpansion(
                    clipboardPanelOpen, state.inputSessionId,
                    clipboardPanelBounds(keyboardHeight, effectiveScreenH, clipboardBottomSpace + toolPanelAboveBar),
                    state.clipboardImagesExpanded,
                ) { expanded ->
                    uiState.value = uiState.value.copy(clipboardImagesExpanded = expanded)
                }
                // Clipboard search keeps the keyboard as it is: the results take the space the clipboard
                // would take when pulled up, above the keyboard and the search bar.
                // The container takes that height at once; the results slide in inside it (drawing only).
                // Growing the window frame by frame re-laid out the keyboard and the app behind it on
                // every frame, and opening stuttered on the phone.
                val clipboardSearchResultsHeight = com.kingzcheung.xime.ui.keyboard.clipboardSearchResultsTarget(
                    state.clipboardSearchVisible, clipboardPanelBounds(keyboardHeight, effectiveScreenH, clipboardBottomSpace), keyboardHeight,
                    available = effectiveScreenH - clipboardBottomSpace)
                // Too short for the two-row bar (phone landscape): one-row bar, no category row in the results.
                val clipboardSearchInline = state.clipboardSearchVisible && com.kingzcheung.xime.ui.keyboard.clipboardSearchInline(
                    keyboardHeight, effectiveScreenH - clipboardBottomSpace)
                // 浮动只收窄宽度，不再压缩按键行高或带入普通模式的底部留白。
                val effectiveKeyboardHeight = when {
                    handwritingExpanded -> handwritingSize(keyboardHeight, (effectiveScreenH - floatingMinY).coerceAtLeast(1))
                    clipboardExpansion != null -> clipboardExpansion.height.roundToInt()
                    else -> keyboardHeight
                }
                val floatingCardContentHeight = effectiveKeyboardHeight + floatingDragBarHeight
                
                val density = LocalDensity.current
                // 统一使用 View 层多类型检测的 insets，避免 Compose
                // navigationBars 恒为手势条高度导致与系统栏（三键导航）差异被抹平。
                val navBarDp = activeBottomDp.dp
                val hasNavBar = navBarDp > 0.dp

                // 工具面板为"键盘上方的撑高面板"：显示时键盘总高增加面板高度（面板在键盘上方，
                // 不遮键盘按键），同时容器物理高度同步变大（updateHeight）→ IME insets 由系统确定性重算，
                // 关闭后容器还原，彻底避免 insets 残留与白色区域。
                // 菜单、表情、剪贴板等面板打开时照样算：工具面板、翻译栏、剪贴板搜索还画在候选栏上面（表情、符号也打进它们里面），
                // 面板只换掉下面的按键。以前打开面板就不算这一截，键盘先矮一截，面板又从顶上量候选栏高度，
                // 盖住了被往下推的工具栏（11-10：开着翻译栏打开菜单，工具栏图标画在菜单格子上）。
                // Clipboard editing uses a separate popup and does not resize the keyboard.
                // 与面板实际高度同一来源（toolPanelExtraHeight），否则容器比面板多/少一截，键盘被拉高。
                // PASSIVE 纯展示面板走 Overlay 全屏覆盖（键盘窗口内容区），不撑高。
                val toolPanelExtra = toolPanelAboveBar
                // Clipboard search adds its results and search panel above the keyboard like the tool panel.
                val overlayPanelExtra = toolPanelExtra + com.kingzcheung.xime.ui.keyboard.clipboardSearchExtraHeight(
                    state.clipboardSearchVisible, clipboardSearchResultsHeight, clipboardSearchInline)

                val isDark = isDarkTheme
                val hardwarePalette = com.kingzcheung.xime.ui.theme.resolveKeyboardPalette(state.themeId, isDark)
                val cardBg = hardwarePalette.background
                val keyboardBgColor = com.kingzcheung.xime.ui.theme.KeyboardThemes.getKeyboardBackgroundColor(state.themeId, isDark)
                val rootTheme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getRenderingScheme(state.themeId)
                val contentHeight = if (state.showKeyboardResize) state.resizePreviewHeightDp else floatingCardContentHeight + overlayPanelExtra
                val navigationStripCard = remember(state.isFloatingMode, state.isCompact, state.voiceCollapsed, state.showKeyboardResize) {
                    mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
                }
                // 动态背景（主题与外观 › 背景图片）：只动图片背景；游戏、系统省电、系统关了动画时不动，键盘收起时停。
                val backgroundMotionSettings = com.kingzcheung.xime.ui.theme.rememberBackgroundMotionSettings()
                // 轮播：「我的图片」主图在前，加上还在的轮播图片；不到两张不轮播。
                val backgroundSlidesContext = androidx.compose.ui.platform.LocalContext.current
                val backgroundSlides = remember(backgroundMotionSettings.slides, rootTheme.keyboardBackground) {
                    rootTheme.keyboardBackground?.takeIf { com.kingzcheung.xime.ui.theme.CustomImageTheme.isCustomImage(it) }?.src
                        ?.let { main -> (listOf(main) + com.kingzcheung.xime.ui.theme.CustomImageTheme.slides(
                            backgroundSlidesContext, backgroundMotionSettings.slides)).distinct() }
                        ?.takeIf { it.size > 1 } ?: emptyList()
                }
                val backgroundMotion = com.kingzcheung.xime.ui.theme.rememberKeyboardBackgroundMotion(
                    backgroundMotionSettings,
                    allowed = com.kingzcheung.xime.ui.theme.BackgroundMotionGate.allowed(
                        enabled = backgroundMotionSettings.enabled,
                        imageBackground = rootTheme.keyboardBackground?.type == "image",
                        game = backgroundMotionGame.value,
                        systemAllows = com.kingzcheung.xime.ui.theme.rememberSystemAllowsBackgroundMotion(backgroundMotionSettings.enabled),
                        surface = !state.isCompact && !state.voiceCollapsed,
                    ),
                    running = backgroundMotionWindowShown.value,
                    slides = backgroundSlides,
                )
                XimeTheme(darkTheme = isDarkTheme, themeId = state.themeId) {
                    com.kingzcheung.xime.ui.theme.KeyboardBackdropHost(
                        enabled = frostedGlass.enabled && !state.isCompact && !state.voiceCollapsed && !state.isFloatingMode && !roundedKeyboardBottom,
                        background = rootTheme.keyboardBackground, isDark = isDark,
                        fallback = cardBg, glass = frostedGlass,
                        backdropHeight = (contentHeight + contentBottomPaddingDp + activeBottomDp).dp,
                        modifier = Modifier.fillMaxSize(),
                        clipboardInMotion = clipboardExpansion?.isInMotion == true,
                        topFeather = glassTopFeatherTop,
                        fixedCard = navigationStripCard.takeUnless { state.showKeyboardResize || handwritingExpanded },
                        motion = backgroundMotion,
                    ) {
                        // Sync FrameLayout height with Compose content height
                        val totalDp = if (handwritingExpanded || state.showKeyboardResize || state.isCompact || state.voiceCollapsed || state.isFloatingMode) effectiveScreenH
                            else contentHeight + contentBottomPaddingDp + activeBottomDp
                        SideEffect {
                            val editingPreview = (settingsPreviewPanel == KeyboardPanelLaunch.Panel.RESIZE && state.showKeyboardResize) ||
                                (settingsPreviewPanel == KeyboardPanelLaunch.Panel.ARRANGE &&
                                    (page as? KeyboardPage.Overlay)?.route == OverlayRoute.ToolbarCustomize)
                            keyboardContainer.importantForAccessibility = if (settingsPreviewEditor && !editingPreview)
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            currentResolvedKeyboardSize = protectedSize
                            // 容器物理高度 = Compose 内容总高（含底部留白），全模式统一。
                            // 容器高度变化 → View 层 relayout → traversal → onComputeInsets
                            // 自动以新高度重算并上报（ViewRootImpl 每次 traversal 都 dispatch
                            // OnComputeInternalInsetsListener，值变化即 setInsets），
                            // 无需 +1dp hack 强制造型变化。
                            handwritingNormalInsetDp = keyboardHeight + state.keyboardBottomPaddingDp + activeBottomDp
                            handwritingOverlayActive = handwritingExpanded
                            keyboardContainer.updateHeight(totalDp)
                            currentEffectiveKeyboardHeight = if (state.isFloatingMode) floatingCardContentHeight + overlayPanelExtra
                                else if (state.isCompact) HARDWARE_CANDIDATE_BAR_HEIGHT
                                else effectiveKeyboardHeight + overlayPanelExtra
                        }
                        if (state.isCompact) {
                          androidx.compose.runtime.CompositionLocalProvider(
                              com.kingzcheung.xime.ui.theme.LocalMaterialPalette provides
                                  com.kingzcheung.xime.ui.theme.MaterialPalette(cardBg, hardwarePalette.accent)) {
                          androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = activeBottomDp.dp)) {
                            SideEffect {
                                hardwareViewportWidthDp = maxWidth.value
                                hardwareViewportHeightDp = maxHeight.value
                            }
                            val hardwareExample = settingsPreviewEditor && settingsExample == KeyboardPanelLaunch.Example.Hardware
                            val position = if (hardwareExample) HardwareToolbarPosition(0.5f, 1f) else hardwareToolbarPosition.value
                            val bottomDocked = position.isBottomDocked(state.hardwareOptions.dockAtEdge) && maxWidth >= 336.dp
                            val glass = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences(isDark).frostedGlass
                            // The keyboard backdrop supplies theme/glass color and opacity. Do not
                            // add visualMaterial here: its rim redraws an unwanted outline around
                            // both the tight preedit preview and the single-row candidate surface.
                            val hardwareSurface: @Composable () -> Modifier = {
                                Modifier.keyboardBackground(rootTheme.keyboardBackground, isDark, cardBg,
                                    frostedGlass = glass, translucentSurface = true)
                            }
                            val hardwareEnglish = !hardwareExample && (state.isAsciiMode || state.inputProfile.typesLettersDirectly)
                            val pendingPrediction by predictionManager.pendingRequest.collectAsState()
                            // The toolbar numbers 10 candidates; a longer engine page is shown in windows of 10.
                            // 候选只在实体键盘浮条这里读；放在外层会让每次候选变化都重组整个键盘。
                            val cand = if (hardwareExample) previewCandidates else candidateState.value
                            val hardwareWindow = if (hardwareExample) 0 else keyRouter.hardwareWindowStart(cand)
                            com.kingzcheung.xime.gloss.CandidateGlossary.revision // 词典加载完成后重组
                            val hardwareGlosses = !hardwareEnglish &&
                                state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE &&
                                com.kingzcheung.xime.gloss.CandidateGlossary.isLoaded
                            val hardwareCandidates = hardwareCandidatePresentation(cand, hardwareEnglish,
                                predictionPending = pendingPrediction != 0L).window(hardwareWindow).let { shown ->
                                    // 释义只换显示用注释，与屏幕候选栏同一规则。
                                    if (hardwareEnglish || shown.association ||
                                        state.inputProfile.language != com.kingzcheung.xime.settings.InputLanguage.CHINESE) shown
                                    else if (com.kingzcheung.xime.gloss.CandidateGlossary.physicalShiftHeld.value) {
                                        val (words, notes) = com.kingzcheung.xime.gloss.CandidateGlossary.swapped(shown.words, shown.comments)
                                        shown.copy(words = words, comments = notes)
                                    }
                                    else shown.copy(comments = com.kingzcheung.xime.gloss.CandidateGlossary.annotate(shown.words, shown.comments))
                                }
                            val hardwareHighlight = keyRouter.hardwareCandidateHighlight(cand, hardwareEnglish,
                                predictionPending = pendingPrediction != 0L)
                            val hardwareLanguage = if (state.isAsciiMode) "EN" else when (state.inputProfile.language) {
                                com.kingzcheung.xime.settings.InputLanguage.ENGLISH -> "EN"
                                com.kingzcheung.xime.settings.InputLanguage.CHINESE -> "中"
                                com.kingzcheung.xime.settings.InputLanguage.JAPANESE -> "あ"
                                else -> com.kingzcheung.xime.settings.LetterLanguage.of(state.inputProfile.language)?.badge
                                    ?: state.inputProfile.language.displayName
                            }
                            val selectHardwareCandidate: (Int) -> Unit = { index ->
                                if (hardwareCandidates.association) {
                                    val current = candidateState.value
                                    if ((hardwareEnglish || !predictionManager.hasPendingPrediction) &&
                                        state.inputSessionId == uiState.value.inputSessionId &&
                                        cand.pendingEnglishText == current.pendingEnglishText &&
                                        cand.associationCandidates == current.associationCandidates) {
                                        keyboardCallbacks?.onAssociationSelect?.invoke(index)
                                    }
                                } else if (cand.hasSameSelectionSource(candidateState.value)) {
                                    keyRouter.selectCandidate(hardwareWindow + index)
                                    keyRouter.resetHardwareCandidateSelection()
                                }
                            }
                            val previousHardwareCandidate: () -> Unit = {
                                if (cand.hasSameSelectionSource(candidateState.value)) {
                                    keyRouter.moveHardwareCandidate(-1)
                                }
                            }
                            val nextHardwareCandidate: () -> Unit = {
                                if (cand.hasSameSelectionSource(candidateState.value)) {
                                    keyRouter.moveHardwareCandidate(1)
                                }
                            }
                            HardwareKeyboardCandidateBar(
                                frostedGlass = glass,
                                inputText = if (hardwareEnglish) "" else cand.inputText,
                                preeditText = if (hardwareEnglish) "" else cand.shownPreedit(state.inputProfile),
                                showPreedit = keyboardPrefs.showCodeInCandidateBar,
                                candidates = hardwareCandidates.windowWords(state.hardwareOptions.associationInWindow),
                                hasNextPage = hardwareCandidates.hasNextPage,
                                hasPrevPage = hardwareCandidates.hasPreviousPage,
                                cursorAnchor = state.cursorAnchor.takeIf { state.hardwareOptions.followCursor },
                                highlightIndex = hardwareHighlight,
                                cardBackgroundColor = cardBg,
                                candidateTextColor = hardwarePalette.candidateText,
                                activeColor = hardwarePalette.accent,
                                selectedTextColor = hardwarePalette.selectedText,
                                preeditBackgroundColor = hardwarePalette.key,
                                comments = hardwareCandidates.comments,
                                commentsAreGlosses = hardwareGlosses && !hardwareCandidates.association,
                                onPrevious = previousHardwareCandidate.takeUnless { hardwareCandidates.association },
                                onNext = nextHardwareCandidate.takeUnless { hardwareCandidates.association },
                                onCandidateSelect = selectHardwareCandidate,
                                bottomDocked = bottomDocked,
                                showNumberLabels = true,
                                surface = hardwareSurface,
                                onPreeditBoundsChanged = { updateHardwarePreeditBounds(it) },
                                onBoundsChanged = { updateHardwareSurfaceBounds(toolbar = false, it) },
                                avoidBoundsInWindow = hardwareToolbarBounds.value,
                                avoidCaret = state.cursorAnchor.takeIf { state.hardwareOptions.avoidEditor },
                            )
                            HardwareKeyboardToolbar(
                                frostedGlass = glass,
                                selectedTextColor = hardwarePalette.selectedText,
                                accentColor = hardwarePalette.accent,
                                onShowKeyboard = { showScreenKeyboardFromHardware() },
                                onEmoji = { showScreenKeyboardFromHardware(OverlayRoute.Emoji) },
                                onClipboard = { showScreenKeyboardFromHardware(OverlayRoute.Clipboard()) },
                                // 「语音转文本」关着时物理键盘工具条不放麦克风。
                                onVoice = if (state.isSttEnabled) ({ handleHardwareTool(HardwareToolShortcut.MICROPHONE) }) else null,
                                voiceActive = state.isVoiceMode,
                                onSwitchLanguage = { switchHardwareLanguage() },
                                languageLabel = hardwareLanguage,
                                statusText = if (state.isDeploying) state.deploymentMessage.lineSequence().firstOrNull()
                                    ?.takeIf { it.isNotBlank() } ?: "词库加载中，请稍候" else null,
                                languageActions = com.kingzcheung.xime.ui.keyboard.KeyboardInputActions(
                                    schemas = com.kingzcheung.xime.settings.InputModes.available(state.schemas),
                                    currentInputModeId = com.kingzcheung.xime.settings.InputModes.selectedId(state.currentSchemaId, state.isAsciiMode),
                                    fieldNativeModeId = state.currentSchemaId.takeIf { state.isAddressField },
                                    onSwitchSchema = { id -> if (inputReadiness.ticket() != null) schemaController.switchSchema(id) },
                                ),
                                cursorAnchor = state.cursorAnchor,
                                editorBounds = state.editorBounds,
                                avoidEditor = state.hardwareOptions.avoidEditor,
                                dockAtEdge = state.hardwareOptions.dockAtEdge,
                                // 吸附底边时的样式：默认为现在的长条，可选三星式贴底通栏。
                                samsungBottomBar = state.hardwareOptions.bottomBar,
                                onHideKeyboard = { requestHideSelf(0) },
                                capsLock = hardwareCapsLockState.value,
                                backgroundColor = cardBg,
                                surface = hardwareSurface,
                                bottomDocked = bottomDocked,
                                bottomCandidateContent = if (hardwareCandidates.words.isEmpty()) null else {
                                  {
                                    HardwareCandidateRow(if (state.hardwareOptions.bottomBar) hardwareCandidates.words.take(9)
                                        else hardwareCandidates.words, hardwareCandidates.comments,
                                        hardwareHighlight,
                                        hardwareCandidates.hasPreviousPage, hardwareCandidates.hasNextPage,
                                        CandidateBarVisuals(cardBg, hardwarePalette.candidateText, Color.Transparent,
                                            hardwarePalette.accent, hardwarePalette.selectedText),
                                        selectHardwareCandidate,
                                        previousHardwareCandidate.takeUnless { hardwareCandidates.association },
                                        nextHardwareCandidate.takeUnless { hardwareCandidates.association },
                                        showNumberLabels = !hardwareEnglish,
                                        commentsAreGlosses = hardwareGlosses && !hardwareCandidates.association)
                                  }
                                },
                                showCandidatesWhenFloating = hardwareCandidates.association && !state.hardwareOptions.associationInWindow,
                                candidatesPending = !hardwareEnglish && pendingPrediction != 0L,
                                contentColor = hardwarePalette.candidateText,
                                position = position,
                                onPositionChange = { hardwareToolbarPosition.value = it },
                                onBoundsChanged = { updateHardwareSurfaceBounds(toolbar = true, it) },
                            )
                          }
                          }
                        } else if (state.voiceCollapsed) {
                          // 语音收起：键盘让位，只留底部悬浮条；外观沿用实体键盘浮条（主题 + 毛玻璃）。
                          androidx.compose.runtime.CompositionLocalProvider(
                              com.kingzcheung.xime.ui.theme.LocalMaterialPalette provides
                                  com.kingzcheung.xime.ui.theme.MaterialPalette(cardBg, hardwarePalette.accent)) {
                          Box(Modifier.fillMaxSize().padding(bottom = activeBottomDp.dp)) {
                            androidx.compose.runtime.DisposableEffect(Unit) {
                                voicePillOnScreen = true
                                onDispose { voicePillOnScreen = false }
                            }
                            val pillPrefs = com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences(isDark)
                            val glass = pillPrefs.frostedGlass
                            val pillEnglish = state.isAsciiMode || state.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.ENGLISH
                            com.kingzcheung.xime.ui.keyboard.VoiceCollapsedPill(
                                listening = state.isVoiceMode,
                                autoResumed = voicePillAutoResumed.value,
                                amplitude = { voiceAmplitudeState.floatValue },
                                languageLabel = if (pillEnglish) "EN" else when (state.inputProfile.language) {
                                    com.kingzcheung.xime.settings.InputLanguage.CHINESE -> "中"
                                    com.kingzcheung.xime.settings.InputLanguage.JAPANESE -> "あ"
                                    else -> com.kingzcheung.xime.settings.LetterLanguage.of(state.inputProfile.language)?.badge
                                        ?: state.inputProfile.language.displayName
                                },
                                languageActions = com.kingzcheung.xime.ui.keyboard.KeyboardInputActions(
                                    schemas = com.kingzcheung.xime.settings.InputModes.available(state.schemas),
                                    currentInputModeId = com.kingzcheung.xime.settings.InputModes.selectedId(state.currentSchemaId, state.isAsciiMode),
                                    fieldNativeModeId = state.currentSchemaId.takeIf { state.isAddressField },
                                    onSwitchSchema = { id -> if (inputReadiness.ticket() != null) schemaController.switchSchema(id) },
                                ),
                                onSwitchLanguage = { switchHardwareLanguage() },
                                onShowKeyboard = { expandFromVoicePill() },
                                onBackspace = { keyboardCallbacks?.onKeyPress("delete", false) },
                                onBackspacePress = { keyboardCallbacks?.onKeyPressDown?.invoke("delete") },
                                onBackspaceRelease = { keyboardCallbacks?.onKeyRelease?.invoke("delete") },
                                backspaceRepeatMs = pillPrefs.deleteRepeatSpeed.intervalMs,
                                onMic = {
                                    // 在悬浮条上点麦克风停下：用户自己停的，之后不再自动续听。
                                    // 收尾期间焦点离开也不能再记成“被打断”。
                                    if (uiState.value.isVoiceMode) { stopVoicePillResume(); voiceStopRequested = true }
                                    keyboardCallbacks?.onVoiceStickyToggle?.invoke()
                                },
                                backgroundColor = cardBg,
                                contentColor = hardwarePalette.candidateText,
                                selectedTextColor = hardwarePalette.selectedText,
                                accentColor = hardwarePalette.accent,
                                frostedGlass = glass,
                                surface = {
                                    Modifier.keyboardBackground(rootTheme.keyboardBackground, isDark, cardBg,
                                        frostedGlass = glass, translucentSurface = true)
                                },
                                onBoundsChanged = { updateHardwareSurfaceBounds(toolbar = true, it) },
                                position = hardwareToolbarPosition.value,
                                onPositionChange = { hardwareToolbarPosition.value = it },
                                dockAtEdge = state.hardwareOptions.dockAtEdge,
                            )
                          }
                          }
                        } else {
                        // The navigation strip and keyboard sample one backdrop in window coordinates;
                        // this strip draws only its own slice, never a separately fitted glass image.
                        // 键盘内容通过 offset 上移 activeBottomDp 留出导航栏空间。
                        // 浮动模式：卡片由 KeyboardView 内部 FloatingKeyboardContainer 自绘背景与定位，此处不做背景/偏移。
                        // 固定卡片在窗口里的位置：导航条的渐变/图片接着卡片底边铺，不另起一段。
                        // 「填满留空处」：固定卡片两侧的留空用键盘背景铺满（默认镂空，透出后面的应用）。
                        if (!state.isFloatingMode && !handwritingExpanded &&
                            com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences(isDark).fillBlankAreas) {
                            val corner = com.kingzcheung.xime.ui.keyboard.FloatingKeyboardCardCorner
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = keyboardLayerOpacity }
                                    .keyboardBackgroundBesideCard(rootTheme.keyboardBackground, isDark, keyboardBgColor,
                                        card = { navigationStripCard.value },
                                        topCorner = if (com.kingzcheung.xime.ui.keyboard.rememberRoundedKeyboardTop()) corner else 0.dp,
                                        bottomCorner = if (roundedKeyboardBottom) corner else 0.dp)
                            )
                        }
                        if (!state.isFloatingMode && !roundedKeyboardBottom) {
                            val filledSides = !handwritingExpanded &&
                                com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences(isDark).fillBlankAreas
                            // 卡片底下系统按钮那条只和卡片一样宽，卡片底色接到屏幕底边；「填满留空处」开着时仍整宽。
                            // 全屏手写：画板透明，这条接着底下那块按键（11-09：以前整宽另铺，和按键那块断开）。
                            val stripCard = { if (handwritingExpanded) handwritingFooterCard.value else navigationStripCard.value }
                            NavigationStripBelowCard(
                                height = activeBottomDp.dp,
                                card = stripCard(),
                                followCard = !filledSides,
                                onBounds = { updateGlassSurface("navigation", it) },
                                surface = Modifier
                                    .graphicsLayer { alpha = keyboardLayerOpacity }
                                    .keyboardBackgroundBelowCard(rootTheme.keyboardBackground, isDark, keyboardBgColor,
                                        card = stripCard, filledSides = filledSides),
                            )
                        }
                        Box(
                            modifier = if (handwritingExpanded) Modifier.fillMaxWidth().height(effectiveKeyboardHeight.dp)
                                .align(androidx.compose.ui.Alignment.BottomCenter).offset(y = (-handwritingSize(
                                    if (state.isFloatingMode) state.floatingOffsetY.coerceAtLeast(floatingMinY) else activeBottomDp,
                                    activeBottomDp)).dp)
                            else if (com.kingzcheung.xime.ui.keyboard.usesFullKeyboardHost(
                                state.isFloatingMode, state.showKeyboardResize
                            )) {
                                // 正常悬浮也必须全高。确认后缩成键盘高度会让上移的卡片被图层裁掉。
                                Modifier.fillMaxSize()
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .height((floatingCardContentHeight + contentBottomPaddingDp + overlayPanelExtra).dp)
                                    .align(androidx.compose.ui.Alignment.BottomCenter)
                                    .then(if (state.isFloatingMode) Modifier else Modifier.offset(y = (-activeBottomDp).dp))
                            }
                        ) {
                        CompositionLocalProvider(
                            com.kingzcheung.xime.clipboard.LocalClipboardKeyboardNavigation provides clipboardKeyboard,
                            LocalStretchFactor provides state.stretchFactor,
                            LocalClipboardPanelExpansion provides clipboardExpansion,
                        ) {
                            // 注意：kbState 只承载键盘按键/布局状态，不承载候选数据。
                            // 候选数据单独通过 candidateState 传给 KeyboardView。
                            // remember 的 key 均为候选无关依赖：候选变化时 kbState 实例保持不变，
                            // KeyboardView（按键区）跳过重组，只有读取 candidateState 的候选栏重组，
                            // 避免长按退格时高频候选更新触发整个键盘重组导致候选栏闪烁。
                            val kbState = remember(
                                state,
                                isDarkTheme,
                                effectiveKeyboardHeight,
                                // Without this key the keyboard view kept a stale results height (0) and the keys
                                // stretched into the results.
                                clipboardSearchResultsHeight, clipboardSearchInline,
                                handwritingExpanded, handwritingWidth, handwritingOffsetX,
                                protectedSize,
                                floatingMinY,
                            isHandwritingMode,
                            clipboardItemsState.value,
                            quickSendItemsState.value,
                            recentClipboardItemsState.value,
                            calculatorEngine.isActive(),
                            ) {
                                KeyboardUiState(
                                    clipboardImagesExpanded = imagePanelExpanded,
                                    rejectedCommitText = state.rejectedCommitText,
                                    isAsciiMode = state.isAsciiMode,
                                    schemaName = if (state.isAsciiMode) "英文" else state.schemaName,
                                    currentSchemaId = state.currentSchemaId,
                                    schemas = state.schemas,
                                    schemaSwitches = state.schemaSwitches,
                                    enterKeyText = state.enterKeyText,
                                    isDarkTheme = isDarkTheme,
                                    darkMode = state.darkMode,
                                    themeId = state.themeId,
                                    handwritingExpanded = handwritingExpanded,
                                    handwritingControlsWidthDp = protectedSize.width,
                                    handwritingControlsOffsetX = handwritingNormalX - handwritingOffsetX,
                                    handwritingNormalHeightDp = keyboardHeight,
                                    keyboardHeightDp = effectiveKeyboardHeight,
                                    keyboardWidthDp = if (handwritingExpanded) handwritingWidth else protectedSize.width,
                                    keyboardBottomPaddingDp = contentBottomPaddingDp + (activeBottomDp - previewInsetDp),
                                    keyboardOpacity = state.keyboardOpacity,
                                    // 词库部署/加载期间键盘区显示加载页（之前没传进来，加载页从不出现）。
                                    isDeploying = state.isDeploying,
                                    deploymentMessage = state.deploymentMessage,
                                    clipboardItems = clipboardItemsState.value,
                                    quickSendItems = quickSendItemsState.value,
                                    recentClipboardItems = recentClipboardItemsState.value,
                                    isVoiceMode = state.isVoiceMode,
                                    voiceSticky = state.voiceSticky,
                                    voiceBottomActive = state.voiceButtonState.bottomActive,
                                    voiceLeftActive = state.voiceButtonState.leftActive,
                                    voiceRightActive = state.voiceButtonState.rightActive,
                                    voicePluginName = state.voicePluginName,
                                    voiceNotice = state.voiceNotice,
                                    voiceRecognitionState = state.voiceRecognitionState,
                                    voiceRecognizedText = state.voiceRecognizedText,
                                    isSttEnabled = state.isSttEnabled,
                                    toolbarButtons = state.toolbarButtons,
                                    menuButtons = state.menuButtons,
                                    toolbarPluginButtons = state.toolbarPluginButtons,
                                    isCalculatorMode = calculatorEngine.isActive(),
                                    inputSessionId = state.inputSessionId,
                                    isAddressField = state.isAddressField,
                                    isFloatingMode = state.isFloatingMode && !handwritingExpanded,
                                    isHandwritingMode = isHandwritingMode,
                                    // Floating cards remain draggable even when an old size is being protected.
                                    floatingOffsetX = state.floatingOffsetX,
                                    floatingOffsetY = state.floatingOffsetY,
                                    floatingRotation = if (state.isFloatingMode && !handwritingExpanded) state.floatingRotation else 0f,
                                    floatingMinOffsetY = floatingMinY,
                                    floatingScreenHeightDp = effectiveScreenH,
                                    resizePreviewWidthDp = state.resizePreviewWidthDp,
                                    floatingWidthDp = state.floatingWidthDp,
                                    fixedWidthDp = if (handwritingExpanded) handwritingWidth else state.fixedWidthDp,
                                    fixedOffsetX = if (handwritingExpanded) handwritingOffsetX else protectedSize.offsetX,
                                    t9ResetSignal = state.t9ResetSignal,
                                    swipeCancelEpoch = state.swipeCancelEpoch,
                                    t9RightCandidateSelectedCount = state.t9RightCandidateSelectedCount,
                                    t9SelectedCandidatePinyin = state.t9SelectedCandidatePinyin,
                                    showQuickSendForm = state.showQuickSendForm,
                                    ocrDraft = state.ocrDraft,
                                    quickSendFormFocused = state.quickSendFormFocused,
                                    quickSendEditingItemId = state.quickSendEditingItemId,
                                    quickSendEditingItemText = state.quickSendEditingItemText,
                                    quickSendEditingItemCode = state.quickSendEditingItemCode,
                                    toolPanelVisible = state.toolPanelVisible,
                                    toolPanelInputFocused = state.toolPanelInputFocused,
                                    clipboardSearchVisible = state.clipboardSearchVisible,
                                    clipboardSearchResultsHeight = clipboardSearchResultsHeight,
                                    clipboardSearchInline = clipboardSearchInline,
                                    toolPanelPluginId = state.toolPanelPluginId,
                                    toolPanelTitle = state.toolPanelTitle,
                                    toolPanelPrefillText = state.toolPanelPrefillText,
                                    toolPanelItems = state.toolPanelItems,
                                    toolPanelLoading = state.toolPanelLoading,
                                    toolPanelRequestEpoch = state.toolPanelRequestEpoch,
                                    toolPanelDisplay = state.toolPanelDisplay,
                                    toolPanelUiNodes = state.toolPanelUiNodes,
                                    clipboardSyncEnabled = state.clipboardSyncEnabled,
                                )
                            }
                            val hapticView = LocalView.current
                            val resizeControls: (@Composable () -> Unit)? = if (state.showKeyboardResize) {
                                {
                              var bottomEdited by remember(state.isFloatingMode) { mutableStateOf(false) }
                              KeyboardResizeOverlay(
                                     onPreviewControlsPositioned = if (!settingsPreviewEditor) null else { r ->
                                         if (settingsResizeBounds != r) {
                                             settingsResizeBounds = r
                                             keyboardContainer.requestLayout()
                                         }
                                     },
                                     initialHeightDp = effectiveKeyboardHeight,
                                     // 左右边/四角可调宽度：边界与屏宽一致，与几何纯函数同源。
                                     initialWidthDp = state.resizePreviewWidthDp,
                                     minWidthDp = com.kingzcheung.xime.ui.keyboard.keyboardWidthBounds(resources.configuration.screenWidthDp).first,
                                     maxWidthDp = com.kingzcheung.xime.ui.keyboard.keyboardWidthBounds(resources.configuration.screenWidthDp).last,
                                     defaultHeightDp = SettingsPreferences.getDefaultKeyboardHeightDp(this@XimeInputMethodService, screenIsLandscape),
                                     defaultSize = resetSize,
                                     currentBottomPaddingDp = contentBottomPaddingDp + (activeBottomDp - previewInsetDp),
                                     defaultBottomPaddingDp = defaultBottomPadding,
                                     isFloatingMode = state.isFloatingMode,
                                     initialOpacity = state.keyboardOpacity,
                                     onFloatingModeChange = { enabled ->
                                         restoreKeyboardDisplayMode(enabled, floatingMinY, persist = false)
                                     },
                                     // 屏幕太窄（手机竖屏）时分体按钮显示未选中并禁用，存着的「分体键盘」不动。
                                     isSplitKeyboard = SettingsPreferences.isSplitKeyboardShown(this@XimeInputMethodService),
                                     // 始终传入回调：当前方案不支持分体时由覆盖层禁用按钮，
                                     // 而不是让按钮整个消失（用户无法判断是禁用还是坏了）。
                                     splitKeyboardSupported = com.kingzcheung.xime.ui.keyboard.supportsSplitKeyboard(state.currentSchemaId, state.isAsciiMode) &&
                                         com.kingzcheung.xime.ui.keyboard.SplitKeyboardGeometry.fitsScreen(resources.configuration.screenWidthDp),
                                     onSplitKeyboardChange = { enabled -> SettingsPreferences.setSplitKeyboardEnabled(this@XimeInputMethodService, enabled) },
                                     onPositionDrag = { dx, dy -> callbacks.onFloatingKeyboardDrag?.invoke(dx, dy) },
                                     onPositionDragEnd = { callbacks.onFloatingKeyboardDragEnd?.invoke() },
                                     onSnapFeedback = { feedbackManager.hapticFeedback(hapticView) },
                                     onRotationConfirm = { degrees -> callbacks.onFloatingKeyboardRotate?.invoke(degrees) },
                                     modeSwitchTarget = { toFloating, hostW, hostH ->
                                         val d = resources.displayMetrics.density
                                         if (toFloating) resolveFloatingSwitchRect(hostW, hostH, floatingMinY)
                                         else resolveKeyboardDockTarget((hostW / d).roundToInt().coerceAtLeast(1), uiState.value)
                                             .rectInHost(hostW, hostH, d)
                                     },
                                     onOpacityChange = { opacity ->
                                         uiState.value = uiState.value.copy(keyboardOpacity = opacity)
                                     },
                                     onHeightChange = { newHeight ->
                                       uiState.value = uiState.value.copy(
                                           resizePreviewHeightDp = newHeight
                                       )
                                   },
                                   onGeometryChange = { geometry ->
                                       // 悬浮矩形拖动：一次写入宽度/高度/水平偏移/底边偏移，四个值同源。
                                       // 分体两半总是贴边占满宽度，不覆盖非分体时保存的宽度和水平位置。
                                       val split = splitKeyboardActive(uiState.value)
                                       if (uiState.value.isFloatingMode) uiState.value = uiState.value.copy(
                                           resizePreviewWidthDp = if (split) uiState.value.resizePreviewWidthDp else geometry.widthDp,
                                           resizePreviewHeightDp = geometry.heightDp,
                                           floatingOffsetX = if (split) uiState.value.floatingOffsetX else geometry.horizontalOffsetDp,
                                           floatingOffsetY = geometry.bottomOffsetDp,
                                       ) else if (!split) uiState.value = uiState.value.copy(
                                           fixedWidthDp = geometry.widthDp,
                                           fixedOffsetX = geometry.horizontalOffsetDp,
                                       )
                                   },

                                  onWidthChange = { newWidth ->
                                       // 宽度变了，水平可拖动范围跟着变：超出的偏移要收回，
                                           // 否则卡片会有一部分跑到屏幕外。
                                           val screenWidth = resources.configuration.screenWidthDp
                                           val halfMargin = ((screenWidth - newWidth) / 2f).roundToInt().coerceAtLeast(0)
                                           uiState.value = uiState.value.copy(
                                               resizePreviewWidthDp = newWidth,
                                               floatingOffsetX = uiState.value.floatingOffsetX.coerceIn(-halfMargin, halfMargin),
                                           )
                                   },
                                   onBottomPaddingChange = { bottomEdited = true },
                                  onReset = { defaultHeight ->
                                       uiState.value = uiState.value.copy(
                                           resizePreviewHeightDp = defaultHeight,
                                           keyboardBottomPaddingDp = 0,
                                           stretchFactor = 1f
                                       )
                                   },
                                  onConfirm = { newHeight, newPadding, floatingMode, opacity ->
                                       // 先快照：写偏好会触发监听器，不能再读被监听器重载过的预览偏移。
                                       val preview = uiState.value
                                       val savedHeight = KeyboardHeightProfiles.save(
                                           this@XimeInputMethodService, floatingMode, screenIsLandscape,
                                           newHeight, effectiveScreenH,
                                       )
                                       val savedWidth = preview.resizePreviewWidthDp
                                           .takeIf { floatingMode && it > 0 } ?: preview.floatingWidthDp
                                       if (floatingMode) {
                                           SettingsPreferences.setFloatingWidthDp(this@XimeInputMethodService, savedWidth, screenIsLandscape)
                                           SettingsPreferences.setFloatingOffsetX(this@XimeInputMethodService, preview.floatingOffsetX, screenIsLandscape)
                                           SettingsPreferences.setFloatingOffsetY(this@XimeInputMethodService, preview.floatingOffsetY, screenIsLandscape)
                                       }
                                       if (!floatingMode) {
                                           if (bottomEdited) {
                                               SettingsPreferences.setKeyboardBottomAdjustmentDp(this@XimeInputMethodService, newPadding - defaultBottomPadding)
                                           }
                                           SettingsPreferences.setFixedHorizontalGeometry(this@XimeInputMethodService,
                                               preview.fixedWidthDp, preview.fixedOffsetX, screenIsLandscape)
                                       }
                                       val savedPadding = SettingsPreferences.getKeyboardBottomPaddingDp(this@XimeInputMethodService)
                                       SettingsPreferences.setKeyboardOpacity(this@XimeInputMethodService, opacity)
                                       SettingsPreferences.setFloatingMode(this@XimeInputMethodService, floatingMode, screenIsLandscape)
                                       uiState.value = uiState.value.copy(
                                           showKeyboardResize = false,
                                           isFloatingMode = floatingMode,
                                           keyboardHeightDp = savedHeight,
                                           resizePreviewHeightDp = 0,
                                           keyboardBottomPaddingDp = savedPadding,
                                           floatingWidthDp = savedWidth,
                                           fixedWidthDp = preview.fixedWidthDp,
                                           fixedOffsetX = preview.fixedOffsetX,
                                           resizePreviewWidthDp = 0,
                                           floatingOffsetX = preview.floatingOffsetX,
                                           floatingOffsetY = preview.floatingOffsetY,
                                           keyboardOpacity = opacity,
                                       )
                                       // 不再重复 toggleFloatingMode；它会重新读取设置并覆盖刚确认的预览位置。
                                       refreshKeyboardGeometry()
                                       applyWindowBackground()
                                    },
                                    onCancel = { cancelKeyboardResize() },
                                    modifier = Modifier
                                       .fillMaxSize()
                              )
                           }
                            } else null
                            androidx.compose.runtime.CompositionLocalProvider(
                                com.kingzcheung.xime.ui.keyboard.LocalSplitKeyboardGap provides splitKeyboardGap) {
                            val sampleLayout = if (settingsPreviewEditor) remember(settingsExample, kbState.currentSchemaId, kbState.inputProfile) {
                                settingsExample.sampleLayout(kbState.inputProfile, kbState.currentSchemaId)
                            } else null
                            KeyboardView(
                                previewLetterLayout = sampleLayout != null,
                                onPreviewArrangePositioned = if (!settingsPreviewEditor) null else { r -> settingsArrangeBounds = r },
                                hardwareKeyboardConnected = hasHardwareKeyboard,
                                hardwareCandidateHighlight = if (hasHardwareKeyboard) keyRouter.hardwareCandidateSelection(
                                    candidateState.value, state.isAsciiMode || state.inputProfile.typesLettersDirectly,
                                    predictionManager.hasPendingPrediction) else null,
                                inputSessionManagedByHost = true,
                                resizeOverlay = resizeControls,
                                fixedBottomInsetDp = previewInsetDp,
                                fixedHeightRange = heightBounds,
                                dockTarget = if (state.isFloatingMode && !state.showKeyboardResize && !handwritingExpanded)
                                    resolveKeyboardDockTarget(inputWindowWidthDp, state) else null,
                                viewModel = keyboardViewModel,
                                state = if (sampleLayout == null) kbState else kbState.copy(currentSchemaId = sampleLayout,
                                    isAsciiMode = sampleLayout == com.kingzcheung.xime.settings.InputModes.ENGLISH ||
                                        (settingsExample == KeyboardPanelLaunch.Example.Letters && sampleLayout == kbState.currentSchemaId && kbState.isAsciiMode)),
                                candidateState = if (!settingsPreviewEditor) candidateState else remember(settingsExample) {
                                    mutableStateOf(when (settingsExample) {
                                        KeyboardPanelLaunch.Example.Candidates -> previewCandidates
                                        KeyboardPanelLaunch.Example.Associations -> CandidateState(associationCandidates = listOf("早上好", "下午好", "晚上好"))
                                        else -> CandidateState()
                                    })
                                },
                                predictionTransition = if (settingsPreviewEditor) null else predictionManager.transitionPending,
                                glideCandidatePreview = glideCandidatePreview,
                                glideRimePreview = glideRimePreview,
                                glideEnglishPreview = glideEnglishPreview,
                                voiceAmplitudeState = this@XimeInputMethodService.voiceAmplitudeState,
                                voiceSpectrumState = this@XimeInputMethodService.voiceSpectrumState,
                                callbacks = callbacks,
                                inlineSuggestions = if (settingsPreviewEditor) emptyList() else inlineSuggestionManager?.suggestions.orEmpty(),
                                // 悬浮透明度在卡片本身处理，宿主不再强制创建键盘大小的离屏缓冲。
                                modifier = if (state.isFloatingMode || state.showKeyboardResize) Modifier
                                    else Modifier.graphicsLayer {
                                        alpha = keyboardLayerOpacity
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    },
                                // 非按键交互（符号/表情面板、菜单栏、候选栏按钮）的振动，
                                // 语义与按键按下反馈完全一致（模式/时长/振幅走同一配置）
                                onHapticFeedback = { feedbackManager.hapticFeedback(hapticView) },
                                onCardPositioned = { left: Int, top: Int, right: Int, bottom: Int ->
                                    // 毛玻璃窗口只能是直角矩形，盖不住转过的卡片；转着时不放玻璃。
                                    keyboardGlassCard = if (state.isFloatingMode && com.kingzcheung.xime.ui.keyboard
                                            .isFloatingRotated(state.floatingRotation)) null
                                        else android.graphics.Rect(left, top, right, bottom)
                                    keyboardGlassCardOrigin = glassAnchorOrigin()
                                    updateKeyboardGlass()
                                    if (!state.isFloatingMode) navigationStripCard.value = androidx.compose.ui.geometry.Rect(
                                        left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
                                    val cardHeightPx = bottom - top
                                    if (!handwritingExpanded && !state.isFloatingMode && !state.showKeyboardResize && cardHeightPx > 0) {
                                        val bounds = android.graphics.Rect(left, top, right, bottom)
                                        if (fixedCardBounds != bounds) {
                                            fixedCardBounds = bounds
                                            keyboardContainer.requestLayout()
                                            checkFixedKeyboardBottom(bottom, activeBottomDp + contentBottomPaddingDp)
                                        }
                                    }
                                    if (!handwritingExpanded && state.isFloatingMode && !state.showKeyboardResize && cardHeightPx > 0) {
                                        currentEffectiveKeyboardHeight = (cardHeightPx / density.density).roundToInt()
                                        currentFloatingCardHeightDp = currentEffectiveKeyboardHeight
                                        currentFloatingCardWidthDp = ((right - left) / density.density).roundToInt()
                                        val bounds = android.graphics.Rect(left, top, right, bottom)
                                        if (floatingCardBounds != bounds) {
                                            floatingCardBounds = bounds
                                            keyboardContainer.requestLayout()
                                        }
                                    }
                                },
                                onCornerHandlesPositioned = { rects ->
                                    if (floatingCornerHandleBounds != rects) {
                                        floatingCornerHandleBounds = rects
                                        if (::keyboardContainer.isInitialized) keyboardContainer.requestLayout()
                                    }
                                },
                            )
                            }
                           }

                           }
                            if (!state.isFloatingMode && navBarDp > 0.dp) {
                                Spacer(modifier = Modifier.fillMaxWidth().height(navBarDp))
                            }
                       }
                      }
                 }
             }
         } }
        
        }
        keyboardContainer.addView(composeView)

        applyWindowBackground()

        return keyboardContainer
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        try {
            window.window?.decorView
                ?.findViewById<FrameLayout>(android.R.id.inputArea)
                ?.let { area ->
                    area.updateLayoutParams<android.view.ViewGroup.LayoutParams> {
                        height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    // 栏外编码气泡越出容器顶边后进入 inputArea 空白区，
                    // inputArea 默认 clipChildren=true 会把越界部分裁掉。
                    area.clipChildren = false
                    // Only the keyboard card and navigation strip paint a background.
                    area.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            // 容器在 inputArea 内底部对齐（gravity BOTTOM）：
            // 容器物理高度 = Compose 内容总高，小于全屏窗口时若默认 top-left
            // 对齐会让键盘跑到屏顶。高度由 SideEffect 的 updateHeight 动态设置。
            view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                gravity = android.view.Gravity.BOTTOM
            }
            val state = uiState.value
            if (state.isFloatingMode || state.showKeyboardResize || state.isCompact) {
                view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                // 首显前预设容器高度：容器初始 MATCH_PARENT（贴底 → 顶部 y=0）会让窗口
                // 第一次 traversal 的 onComputeInsets 报告"键盘占满全屏"，而 SideEffect 的
                // updateHeight 下一帧才生效；部分应用按首次 inset 布局后不再响应修正，
                // 输入框被顶到屏顶、与键盘间留大片空白（重进时容器已带正确高度故不复现）。
                // 按偏好高度预设首帧真实几何，之后仍由 SideEffect 统一维护。
                val density = resources.displayMetrics.density
                val activeBottomDp = keyboardBottomSpaceDp(state)
                val displayHeight = resolveKeyboardSize(resources.configuration.screenWidthDp,
                    resources.configuration.screenHeightDp, state.keyboardBottomPaddingDp + activeBottomDp, state).height
                view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                    height = ((displayHeight + state.keyboardBottomPaddingDp + activeBottomDp) * density).toInt()
                }
            }
        } catch (_: Exception) {}
    }
    
    // ── ActionExecutor 实现 ──

    override fun performEditorMenuAction(actionId: Int) {
        when (actionId) {
            android.R.id.undo, android.R.id.redo ->
                editorConnection()?.let { sendUndoShortcut(it, redo = actionId == android.R.id.redo) }
            android.R.id.paste -> if (!pasteIntoTerminal() && editorConnection()?.performContextMenuAction(actionId) == true)
                hideClipboardSuggestionsAfterPaste()
            else -> editorConnection()?.performContextMenuAction(actionId)
        }
    }

    /** performContextMenuAction 对 undo 支持不一致，改用 Ctrl+Z / Ctrl+Shift+Z 键盘快捷键。 */
    internal fun sendUndoShortcut(connection: InputConnection, redo: Boolean) {
        // 终端里 Ctrl+Z 是挂起前台程序，不是撤销。
        if (isTerminalEditor()) return
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or if (redo) KeyEvent.META_SHIFT_ON else 0
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
    }

    /** Shared by Compose, first-frame insets, resize entry/reset and mode changes. */
    internal fun resolveKeyboardSize(
        availableWidthDp: Int,
        hostHeightDp: Int,
        bottomSpaceDp: Int,
        state: InputUIState = uiState.value,
        floating: Boolean = state.isFloatingMode,
        reset: Boolean = false,
    ): ProtectedKeyboardSize {
        val config = resources.configuration
        val landscape = config.screenWidthDp > config.screenHeightDp
        val preview = state.showKeyboardResize && !reset
        val height = if (preview && state.resizePreviewHeightDp > 0) state.resizePreviewHeightDp else
            if (floating && !reset && state.clipboardSearchFloatingHeightDp > 0) state.clipboardSearchFloatingHeightDp else
            if (reset) SettingsPreferences.getDefaultKeyboardHeightDp(this, landscape)
            else KeyboardHeightProfiles.selected(this, floating, landscape, hostHeightDp)
        // 悬浮：已存或预览中的高度可以低到方形吸附点（默认高度已在 KeyboardHeightProfiles 里按默认下限夹过）。
        val heightBounds = if (floating)
            com.kingzcheung.xime.ui.keyboard.floatingStoredHeightBounds(hostHeightDp, landscape)
            else KeyboardHeightProfiles.fixedBounds(this, landscape)
        val savedWidth = if (reset) 0 else if (floating)
            (state.resizePreviewWidthDp.takeIf { preview && it > 0 } ?: state.floatingWidthDp)
            else state.fixedWidthDp
        val width = savedWidth.takeIf { it > 0 } ?: if (floating)
            com.kingzcheung.xime.ui.keyboard.resolvedFloatingWidth(
                availableWidthDp, config.screenHeightDp, height, maxOf(config.screenWidthDp, config.screenHeightDp),
                state.inputProfile.layout.kind in setOf(com.kingzcheung.xime.settings.LayoutKind.ALPHABETIC,
                    com.kingzcheung.xime.settings.LayoutKind.MERGED), 0,
            ) else availableWidthDp
        // Fixed mode's configuration height already describes its usable viewport.
        // Resize overlays use a taller host, but must not change the default budget.
        val availableHeight = (if (floating) hostHeightDp - bottomSpaceDp - resources.configuration.floatingFooterHeightDp
            else config.screenHeightDp - (if (reset) 0 else state.keyboardBottomPaddingDp)).coerceAtLeast(1)
        val maximumHeight = heightBounds.last.coerceAtMost(availableHeight)
        val requestedHeight = height.coerceIn(heightBounds.first.coerceAtMost(maximumHeight)..maximumHeight)
        val split = splitKeyboardActive(state)
        val physicalMetrics = keyboardPhysicalMetrics(this, window.window?.decorView?.display)
        val splitGap = if (split) com.kingzcheung.xime.ui.keyboard.SplitKeyboardGeometry.resolvedGap(
            SettingsPreferences.getSplitGapFraction(this, landscape), availableWidthDp.toFloat(),
            com.kingzcheung.xime.ui.keyboard.SplitKeyboardGeometry.gutterDp(floating),
            physicalMetrics?.dpFromMillimeters(com.kingzcheung.xime.ui.keyboard.CALIBRATED_QWERTY_PITCH_MM)) else 0.1f
        val protected = protectKeyboardSize(
            availableWidthDp, if (split) availableWidthDp else width, requestedHeight,
            if (reset) 0 else if (floating) state.floatingOffsetX else state.fixedOffsetX,
            KeyboardAspectLimits.forLayout(state.inputProfile.layout.kind, split, splitGap),
            customSize = !reset && (preview || savedWidth > 0 || KeyboardHeightProfiles.hasSavedHeight(this, floating, landscape)),
            letterDefaults = letterKeyboardDefaults(state.inputProfile, availableHeight, state.isAsciiMode, floating,
                split = split,
                physicalMetrics = physicalMetrics,
                maximumHeightDp = maximumHeight),
        )
        // 分体两半贴住左右边：宽度占满、无水平偏移；保存的非分体宽度不受影响。
        return if (split) protected.copy(width = availableWidthDp, offsetX = 0) else protected
    }

    /** Both the restore operation and its preview resolve the saved fixed geometry here. */
    private fun resolveFixedKeyboardRestore(
        availableWidthDp: Int, state: InputUIState,
    ): Pair<InputUIState, ProtectedKeyboardSize> {
        val config = resources.configuration
        val landscape = config.screenWidthDp > config.screenHeightDp
        val fixed = state.copy(showKeyboardResize = false, isFloatingMode = false,
            fixedWidthDp = SettingsPreferences.getFixedWidthDp(this, landscape),
            fixedOffsetX = SettingsPreferences.getFixedOffsetX(this, landscape))
        val bottom = keyboardBottomSpaceDp(fixed)
        return fixed to resolveKeyboardSize(availableWidthDp, config.screenHeightDp,
            fixed.keyboardBottomPaddingDp + bottom, fixed, floating = false)
    }

    /** Destination after docking settles, including tools that remain open in fixed mode. */
    internal fun resolveKeyboardDockTarget(
        availableWidthDp: Int, state: InputUIState = uiState.value,
    ): KeyboardDockTarget {
        val (fixed, normal) = resolveFixedKeyboardRestore(availableWidthDp, state)
        val page = keyboardViewModel.page.value
        val bottom = keyboardBottomSpaceDp(fixed)
        val clipboardBottom = fixedClipboardBottomDp(bottom, tryGetVisibleNavBarHeightDp(this, window.window)) +
            fixed.keyboardBottomPaddingDp
        // 和窗口高度同一口径：开着菜单等面板时工具面板、翻译栏仍在候选栏上面。
        val toolExtra = com.kingzcheung.xime.ui.keyboard.toolPanelExtraHeight(
            fixed.toolPanelVisible, fixed.toolPanelDisplay)
        val contentHeight = if (clipboardPanelCanExpand(page, false, fixed.isCompact) && fixed.clipboardImagesExpanded)
            clipboardPanelBounds(normal.height, resources.configuration.screenHeightDp, clipboardBottom + toolExtra).expanded
            else normal.height
        return KeyboardDockTarget(normal.width, contentHeight + toolExtra, normal.offsetX,
            fixed.keyboardBottomPaddingDp, bottom)
    }

    private var rotatedTouchKey: Pair<android.graphics.Rect, Float>? = null
    private val rotatedTouchCache = android.graphics.Region()

    /** 转过的悬浮卡片的触摸区域（横条近似）；同一卡片和角度直接复用。 */
    private fun rotatedTouchRegion(card: android.graphics.Rect, degrees: Float): android.graphics.Region {
        val key = android.graphics.Rect(card) to degrees
        if (rotatedTouchKey == key) return rotatedTouchCache
        rotatedTouchCache.setEmpty()
        com.kingzcheung.xime.ui.keyboard.floatingRotatedTouchStrips(com.kingzcheung.xime.ui.keyboard.ResizeRect(
            card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat()), degrees).forEach {
            rotatedTouchCache.op(kotlin.math.floor(it.left).toInt(), kotlin.math.floor(it.top).toInt(),
                kotlin.math.ceil(it.right).toInt(), kotlin.math.ceil(it.bottom).toInt(), android.graphics.Region.Op.UNION)
        }
        rotatedTouchKey = key
        return rotatedTouchCache
    }

    /** 调节界面里从固定拖成悬浮时，悬浮卡片会落到的矩形（宿主 px）；与 restoreKeyboardDisplayMode(true) 同一套尺寸和位置。 */
    private fun resolveFloatingSwitchRect(hostWidthPx: Float, hostHeightPx: Float, navBarDp: Int): com.kingzcheung.xime.ui.keyboard.ResizeRect {
        val config = resources.configuration
        val landscape = config.screenWidthDp > config.screenHeightDp
        val density = resources.displayMetrics.density
        val hostHeight = ((resources.displayMetrics.heightPixels / density).roundToInt() -
            tryGetStatusBarHeightDp(this, window.window)).coerceAtLeast(1)
        val minBottom = maxOf(navBarDp, floatingMinimumBottomDp())
        val size = resolveKeyboardSize((hostWidthPx / density).roundToInt().coerceAtLeast(1), hostHeight, minBottom,
            uiState.value.copy(showKeyboardResize = false,
                floatingWidthDp = SettingsPreferences.getFloatingWidthDp(this, landscape),
                fixedWidthDp = SettingsPreferences.getFixedWidthDp(this, landscape)),
            floating = true)
        return com.kingzcheung.xime.ui.keyboard.floatingCardRect(hostWidthPx, hostHeightPx, size.width * density,
            (size.height + resources.configuration.floatingFooterHeightDp) * density,
            SettingsPreferences.getFloatingOffsetX(this, landscape) * density,
            SettingsPreferences.getFloatingOffsetY(this, landscape, suggestedBottomDp()) * density, minBottom * density)
    }

    /** 读取目标模式的尺寸；恢复固定时透明度归零，保留各模式的位置。 */
    internal fun restoreKeyboardDisplayMode(enabled: Boolean, navBarDp: Int = 0, persist: Boolean = true) {
        val config = resources.configuration
        val landscape = config.screenWidthDp > config.screenHeightDp
        val before = uiState.value
        val restoringFixed = before.isFloatingMode && !enabled
        val hostHeight = ((resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt() -
            tryGetStatusBarHeightDp(this, window.window)).coerceAtLeast(1)
        val savedWidth = SettingsPreferences.getFloatingWidthDp(this, landscape)
        val savedFixedWidth = SettingsPreferences.getFixedWidthDp(this, landscape)
        val savedX = SettingsPreferences.getFloatingOffsetX(this, landscape)
        val savedY = SettingsPreferences.getFloatingOffsetY(this, landscape, suggestedBottomDp())
        val availableWidth = if (::keyboardContainer.isInitialized)
            (keyboardContainer.width / resources.displayMetrics.density).roundToInt().takeIf { it > 0 }
                ?: config.screenWidthDp else config.screenWidthDp
        val fixedRestore = if (!enabled) resolveFixedKeyboardRestore(availableWidth, before) else null
        val targetSize = fixedRestore?.second ?: resolveKeyboardSize(availableWidth,
            if (enabled || before.showKeyboardResize) hostHeight else config.screenHeightDp,
            if (enabled) maxOf(navBarDp, floatingMinimumBottomDp())
            else before.keyboardBottomPaddingDp +
                imeBottomSpaceDp(fixedBottomInsetPxState.value, resources.displayMetrics.density),
            before.copy(showKeyboardResize = false, floatingWidthDp = savedWidth, fixedWidthDp = savedFixedWidth),
            floating = enabled)
        val targetHeight = targetSize.height
        if (persist) {
            if (restoringFixed) SettingsPreferences.setKeyboardOpacity(this, 1f)
            SettingsPreferences.setFloatingMode(this, enabled, landscape)
        }
        uiState.value = uiState.value.copy(
            isFloatingMode = enabled,
            keyboardOpacity = if (restoringFixed) 1f else before.keyboardOpacity,
            keyboardHeightDp = targetHeight,
            resizePreviewHeightDp = if (before.showKeyboardResize) targetHeight else 0,
            resizePreviewWidthDp = if (before.showKeyboardResize && enabled) targetSize.width else 0,
            floatingWidthDp = savedWidth,
            fixedWidthDp = if (before.showKeyboardResize && !enabled) targetSize.width
                else fixedRestore?.first?.fixedWidthDp ?: savedFixedWidth,
            fixedOffsetX = fixedRestore?.first?.fixedOffsetX ?: SettingsPreferences.getFixedOffsetX(this, landscape),
            floatingOffsetX = savedX,
            floatingOffsetY = savedY,
            floatingRotation = SettingsPreferences.getFloatingRotation(this, landscape),
        )
        if (enabled) closeToolPanel()
        refreshKeyboardGeometry()
        applyWindowBackground()
    }

    internal fun cancelKeyboardResize() {
        if (!uiState.value.showKeyboardResize) return
        SettingsPreferences.setSplitKeyboardEnabled(this, uiState.value.resizeInitialSplit)
        val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        uiState.value = uiState.value.copy(
            showKeyboardResize = false,
            isFloatingMode = uiState.value.resizeInitialFloating,
            floatingOffsetX = uiState.value.resizeInitialX,
            floatingOffsetY = uiState.value.resizeInitialY,
            fixedWidthDp = SettingsPreferences.getFixedWidthDp(this, isLandscape),
            fixedOffsetX = SettingsPreferences.getFixedOffsetX(this, isLandscape),
            resizePreviewWidthDp = 0,
            resizePreviewHeightDp = 0,
            keyboardHeightDp = KeyboardHeightProfiles.selected(
                this, uiState.value.resizeInitialFloating, isLandscape,
                ((resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt() -
                    tryGetStatusBarHeightDp(this, window.window)).coerceAtLeast(1),
            ),
            keyboardBottomPaddingDp = SettingsPreferences.getKeyboardBottomPaddingDp(this),
            keyboardOpacity = SettingsPreferences.getKeyboardOpacity(this),
        )
        refreshKeyboardGeometry()
    }

    /** 模式变化立即撤销旧卡片的高度/触摸区域，避免宿主缓存上一帧的浮动留白。 */
    internal fun refreshKeyboardGeometry() {
        floatingCardBounds = null
        fixedCardBounds = null
        clearHardwareSurfaceBounds()
        currentResolvedKeyboardSize = null
        currentFloatingCardHeightDp = 0
        currentEffectiveKeyboardHeight = 0
        if (::keyboardContainer.isInitialized) {
            val state = uiState.value
            val config = resources.configuration
            val screenHeight = config.screenHeightDp
            val activeBottom = keyboardBottomSpaceDp(state)
            // 悬浮模式必须让输入法宿主保持“物理可见全高”。
            // 不能在确认后又退回 configuration.screenHeightDp；那会把卡片上半部裁出 IME 宿主。
            val physicalScreenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt()
            val statusBarDp = tryGetStatusBarHeightDp(this, window.window)
            val floatingHostHeight = (physicalScreenDp - statusBarDp).coerceAtLeast(1)
            val height = when {
                state.isFloatingMode || state.showKeyboardResize || state.isCompact -> floatingHostHeight
                else -> {
                    val content = resolveKeyboardSize(config.screenWidthDp, screenHeight,
                        state.keyboardBottomPaddingDp + activeBottom, state).height
                    content + state.keyboardBottomPaddingDp + activeBottom
                }
            }
            keyboardContainer.updateHeight(height)
            keyboardContainer.requestLayout()
        }
        window.window?.decorView?.requestApplyInsets()
    }

    private val hardwareLanguageShortcuts = HardwareLanguageShortcuts()

    /** 只按着 Alt 时这个键在本机键盘布局里打出的字（没有就是 null）；普通电脑键盘的 Alt+字母没有字。 */
    private fun altLayerChar(e: KeyEvent): Char? {
        if (!e.isAltPressed || e.isCtrlPressed || e.isMetaPressed) return null
        val code = runCatching { e.getUnicodeChar(e.metaState) }.getOrDefault(0)
        if (code == 0 || code and KeyCharacterMap.COMBINING_ACCENT != 0) return null
        return code.toChar().takeUnless { it.isISOControl() || it.isWhitespace() }
    }
    private var clipboardRestoreCompact = false
    private var microphoneRestoreCompact = false
    private val clipboardNavigationKeys = mutableSetOf<Int>()
    private val hardwareCandidateNavigationKeys = mutableSetOf<Int>()
    /** Shift 加数字或空格已用来选候选（上屏英文）的键，松开时一并吞掉。 */
    private val glossShiftKeys = mutableSetOf<Int>()
    /** 实体键盘 Ctrl+回车、Shift+回车按下时已处理，抬起也不再交给应用。 */
    private val ctrlEnterKeys = mutableSetOf<Int>()
    private val clipboardKeyboard = com.kingzcheung.xime.clipboard.ClipboardKeyboardNavigation {
        keyboardViewModel.leaveClipboard()
        if (clipboardRestoreCompact) collapseScreenKeyboardToHardwareToolbar()
        clipboardRestoreCompact = false
    }

    private fun handleHardwareTool(tool: HardwareToolShortcut) {
        when (tool) {
            HardwareToolShortcut.PUNCTUATION -> {
                if (inputReadiness.ticket() != null) sessionController.togglePunctuationWidth(notifyAscii = true)
            }
            HardwareToolShortcut.CLIPBOARD -> {
                val route = (keyboardViewModel.page.value as? KeyboardPage.Overlay)?.route
                if (route is OverlayRoute.Clipboard) clipboardKeyboard.close()
                else {
                    clipboardRestoreCompact = uiState.value.isCompact
                    clipboardKeyboard.open()
                    showScreenKeyboardFromHardware(OverlayRoute.Clipboard())
                }
            }
            HardwareToolShortcut.MICROPHONE -> {
                if (uiState.value.isVoiceMode) {
                    endVoiceSession()
                    if (microphoneRestoreCompact) collapseScreenKeyboardToHardwareToolbar()
                    microphoneRestoreCompact = false
                } else if (!uiState.value.isSttEnabled) {
                    android.widget.Toast.makeText(this, "请先在设置中启用语音输入", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    microphoneRestoreCompact = uiState.value.isCompact
                    if (microphoneRestoreCompact) showScreenKeyboardFromHardware()
                    keyboardCallbacks?.onVoiceStickyToggle?.invoke()
                }
            }
        }
    }

    private var hardwareCursorImmediateOnly = false
    private var cursorLinePending = false
    // 移光标开始时要一次输入框排版；实体键盘模式已常驻监听，等下一次更新即可。输入法内部输入框不向宿主要。
    private val cursorLineRequester: () -> Unit = {
        val connection = currentInputConnection
        if (connection != null && !uiState.value.inputTarget.isInternal) {
            cursorLinePending = true
            if (!hasHardwareKeyboard && !requestLineLayout(connection)) cursorLinePending = false
        }
    }
    private fun switchHardwareLanguage(mode: com.kingzcheung.xime.settings.LanguageSwitchMode? = null) {
        keyboardViewModel.resetShift()
        keyRouter.handleKeyPress("ime_switch", uiState.value.isAsciiMode, languageSwitchMode = mode)
    }

    /**
     * 实体键盘 Shift 是否按住，以每个按键事件自带的修饰键为准（11-04 平板：候选一直 south 在前，空格和点「南 south」
     * 都上屏英文）。只在 Shift 按下、抬起时记一次的话，抬起没送到这里（游戏直通、设置预览、焦点切走）就一直算按住。
     * Shift 按下只在有实体键盘时算；其余按键（含另一个 Shift 抬起）照事件里的 isShiftPressed 改正。
     */
    private fun syncPhysicalShift(keyCode: Int, event: KeyEvent?, down: Boolean) {
        val e = event ?: return
        val shiftKey = keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
        val held = hasHardwareKeyboard && (if (shiftKey && down) true else e.isShiftPressed)
        if (com.kingzcheung.xime.gloss.CandidateGlossary.physicalShiftHeld.value != held) {
            com.kingzcheung.xime.gloss.CandidateGlossary.physicalShiftHeld.value = held
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        syncPhysicalShift(keyCode, event, down = true)
        if (settingsPreviewEditor) {
            if (keyCode == KeyEvent.KEYCODE_BACK) return true
            return if (event?.isSystem == true) super.onKeyDown(keyCode, event) else true
        }
        if (hardwareGamePassthrough) return super.onKeyDown(keyCode, event)
        event?.let { hardwareCapsLockState.value = it.isCapsLockOn }
        if (hasHardwareKeyboard) event?.let { feedbackManager.playHardwareKeySound(it) }
        if (uiState.value.showQuickSendForm && keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            keyRouter.handleKeyPress("enter", event?.isShiftPressed == true)
            return true
        }
        if (uiState.value.showQuickSendForm && keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            event?.startTracking()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            (keyboardViewModel.page.value is KeyboardPage.Overlay || keyboardCallbacks?.onDismissPreeditEditor != null ||
                keyboardViewModel.candidatePageExpanded.value ||
                uiState.value.showKeyboardResize || uiState.value.handwritingExpanded || uiState.value.clipboardSearchVisible ||
                (hasHardwareKeyboard && hardwareKeyboardDisplay.screenKeyboardRequested))) {
            event?.startTracking()
            return true
        }
        val e = event ?: return super.onKeyDown(keyCode, event)
        if (hasHardwareKeyboard) {
            // Shift 单击切语言时这个键会被下面的快捷键吞掉，按住状态要先记下。
            // 全键盘手机的 Alt 数字层（Titan 2：Alt+字母 = 数字）：打字中按出数字就和数字键一样选候选（GitHub #295）。
            val altChar = altLayerChar(e)
            val altDigit = altChar.takeIf { it in '0'..'9' && keyCode !in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 }
                ?.let { KeyEvent.KEYCODE_0 + (it - '0') }
            if (altDigit != null && keyRouter.hardwareComposing() && keyRouter.handleHardwareCandidateKey(altDigit,
                    KeyEvent(e.downTime, e.eventTime, e.action, altDigit, e.repeatCount))) {
                hardwareCandidateNavigationKeys += keyCode
                return true
            }
            val shortcut = hardwareLanguageShortcuts.down(keyCode, e.eventTime, e.repeatCount,
                e.isCtrlPressed, e.isAltPressed, e.isMetaPressed, e.isShiftPressed, uiState.value.hardwareOptions,
                altTypesChar = altChar != null)
            shortcut.switchMode?.let { switchHardwareLanguage(it) }
            shortcut.tool?.let { handleHardwareTool(it) }
            if (shortcut.consume) return true
            // 实体键盘 Ctrl+回车：和屏幕上按住 !@# 点回车同一条规则，发送（终端照旧交给终端）。
            // 属于「工具快捷键」，关掉后交给应用。
            if (uiState.value.hardwareOptions.toolShortcuts &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && e.isCtrlPressed &&
                !e.isAltPressed && !e.isMetaPressed && !e.isShiftPressed && !isTerminalEditor()) {
                if (e.repeatCount == 0) ctrlEnter()
                ctrlEnterKeys += keyCode
                return true
            }
            // 实体键盘 Shift+回车：和屏幕上按住 Shift 点回车同一条规则，换行。终端、打字中（回车上屏编码、选中的候选）照旧。
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && e.isShiftPressed &&
                !e.isAltPressed && !e.isMetaPressed && !e.isCtrlPressed && !isTerminalEditor() && !enterStaysPlain()) {
                shiftEnter()
                ctrlEnterKeys += keyCode
                return true
            }
            val continuingCandidatePress = e.repeatCount > 0 && hardwareCandidateNavigationKeys.contains(keyCode)
            if ((e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) && !continuingCandidatePress && !isHardwareArrow(keyCode)) {
                // 实体键盘 Ctrl+V 交给应用粘贴，剪贴板胶囊跟着收起；终端里 Ctrl+V 是终端自己的键，不算粘贴。
                if (isHardwarePasteShortcut(keyCode, e.isCtrlPressed, e.isAltPressed, e.isMetaPressed, e.repeatCount) &&
                    !isTerminalEditor()) hideClipboardSuggestionsAfterPaste()
                return super.onKeyDown(keyCode, event)
            }
            // 按住 Shift：候选栏中英对调；Shift 加数字或空格选候选时上屏英文（只在释义词典生效时接管）。
            if (keyCode != KeyEvent.KEYCODE_SHIFT_LEFT && keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT &&
                e.isShiftPressed && e.repeatCount == 0 && keyRouter.selectHardwareCandidateWithShift(keyCode)) {
                glossShiftKeys += keyCode
                return true
            }
            val clipboardRoute = (keyboardViewModel.page.value as? KeyboardPage.Overlay)?.route as? OverlayRoute.Clipboard
            val clipboardOpen = clipboardRoute != null
            if (clipboardKeyboard.active && !clipboardOpen) clipboardKeyboard.reset()
            if (clipboardNavigationKeys.contains(keyCode) && e.repeatCount > 0 && !clipboardOpen) return true
            if (clipboardOpen && clipboardKeyboard.active) {
                val action = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> com.kingzcheung.xime.clipboard.ClipboardNavigation.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> com.kingzcheung.xime.clipboard.ClipboardNavigation.RIGHT
                    KeyEvent.KEYCODE_DPAD_UP -> com.kingzcheung.xime.clipboard.ClipboardNavigation.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> com.kingzcheung.xime.clipboard.ClipboardNavigation.DOWN
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> com.kingzcheung.xime.clipboard.ClipboardNavigation.CONFIRM
                    KeyEvent.KEYCODE_ESCAPE -> com.kingzcheung.xime.clipboard.ClipboardNavigation.CANCEL
                    KeyEvent.KEYCODE_SPACE -> com.kingzcheung.xime.clipboard.ClipboardNavigation.MENU
                    KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> com.kingzcheung.xime.clipboard.ClipboardNavigation.DELETE
                    else -> null
                }
                if (action != null) {
                    clipboardNavigationKeys += keyCode
                    if (e.repeatCount == 0 || action !in listOf(com.kingzcheung.xime.clipboard.ClipboardNavigation.CONFIRM,
                            com.kingzcheung.xime.clipboard.ClipboardNavigation.CANCEL, com.kingzcheung.xime.clipboard.ClipboardNavigation.MENU,
                            com.kingzcheung.xime.clipboard.ClipboardNavigation.DELETE)) clipboardKeyboard.dispatch(action)
                    return true
                }
            }
            // Back to the physical keyboard after expanding the on-screen one: collapse it to the
            // toolbar on the first typed character, so it stops covering the app.
            if (hardwareKeyboardDisplay.screenKeyboardRequested && !clipboardOpen &&
                keyCodeToKey(keyCode, e.isShiftPressed) != null && keyCode != KeyEvent.KEYCODE_SPACE) {
                collapseScreenKeyboardToHardwareToolbar()
            }
            // Typing after a dismissed input view must restore its surface without
            // requiring the user to refocus the editor. Esc itself stays inside IME.
            if (!isInputViewShown && currentInputConnection != null &&
                (keyCodeToKey(keyCode, e.isShiftPressed) != null || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                requestShowSelf(0)
            }
            // Resolve cursor/candidate actions after earlier letters in the same FIFO.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                hardwareCandidateDigitIndex(keyCode, e.isShiftPressed) != null) {
                if (keyRouter.handleHardwareCandidateKey(keyCode, e)) {
                    hardwareCandidateNavigationKeys += keyCode
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }
        val pagingKey = hardwareCandidatePageDirection(keyCode, hasInput = true,
            modified = e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) != 0
        if (hasHardwareKeyboard && pagingKey) {
            val symbol = keyCodeToKey(keyCode, e.isShiftPressed)
            if (symbol != null) {
                val pageDirection = hardwareCandidatePageDirection(keyCode, hasInput = true,
                    modified = false, options = uiState.value.hardwareOptions)
                if (pageDirection == 0) {
                    // Rime also binds -= and [] to paging. An explicitly disabled group
                    // must use the ordinary symbol-key path, not reach that second binding.
                    if (inputReadiness.ticket() != null && !keyRouter.hasPendingCandidateCommit) {
                        textCommit.commitLiteralText(symbol, hardware = true)
                    }
                } else {
                    // Check composition inside the FIFO queue, after preceding letters/commits.
                    // UI candidates may still be empty during rapid physical input.
                    keyRouter.handleKeyPress(symbol, e.isShiftPressed, hardwarePageDirection = pageDirection)
                }
                return true
            }
        }
        val chinese = uiState.value.inputProfile.language == com.kingzcheung.xime.settings.InputLanguage.CHINESE
        // Caps Lock while composing commits the letters as typed (as on the desktop); the system
        // still toggles the lock, so the key is not consumed.
        if (keyCode == KeyEvent.KEYCODE_CAPS_LOCK && hasHardwareKeyboard && e.repeatCount == 0 &&
            chinese && !uiState.value.isAsciiMode) keyRouter.commitCompositionAsTyped()
        // Caps Lock inverts letter case like Shift (digits and symbols keep their own Shift meaning);
        // a capital letter in Chinese is typed literally. Japanese kana input keeps its own case rules.
        val capsLetter = e.isCapsLockOn && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z &&
            uiState.value.inputProfile.language != com.kingzcheung.xime.settings.InputLanguage.JAPANESE
        val isShifted = e.isShiftPressed != capsLetter
        val key = keyCodeToKey(keyCode, isShifted)
        if (key != null) {
            keyRouter.handleKeyPress(key, isShifted, hardwareEvent = if (hasHardwareKeyboard) KeyEvent(e) else null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        syncPhysicalShift(keyCode, event, down = false)
        if (settingsPreviewEditor) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 设置页里调节键盘时按返回 = 取消：切过的悬浮、分体、尺寸都不留下，只有「确认」才算数。
                if (event?.isCanceled != true) {
                    if (uiState.value.showKeyboardResize) cancelKeyboardResize()
                    requestHideSelf(0)
                }
                return true
            }
            return if (event?.isSystem == true) super.onKeyUp(keyCode, event) else true
        }
        if (hardwareGamePassthrough) return super.onKeyUp(keyCode, event)
        event?.let { hardwareCapsLockState.value = it.isCapsLockOn }
        if (uiState.value.showQuickSendForm && keyCode in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) return true
        if (uiState.value.showQuickSendForm && keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
            if (event?.isCanceled != true) keyboardCallbacks?.onHideQuickSendForm?.invoke()
            return true
        }
        if (glossShiftKeys.remove(keyCode)) return true
        if (ctrlEnterKeys.remove(keyCode)) return true
        val clipboardConsumed = clipboardNavigationKeys.remove(keyCode)
        val candidateConsumed = hardwareCandidateNavigationKeys.remove(keyCode)
        if (candidateConsumed) keyRouter.releaseHardwareCandidateKey(keyCode)
        if (hasHardwareKeyboard && event != null) {
            val shortcut = hardwareLanguageShortcuts.up(keyCode, event.eventTime, event.isCanceled, uiState.value.hardwareOptions)
            shortcut.switchMode?.let { switchHardwareLanguage(it) }
            if (shortcut.consume || clipboardConsumed || candidateConsumed) return true
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) return true
        }
        if (clipboardConsumed || candidateConsumed) return true
        // Back leaves clipboard search the way its X does: back to the clipboard, keyboard kept.
        if (keyCode == KeyEvent.KEYCODE_BACK && uiState.value.clipboardSearchVisible) {
            if (event?.isCanceled != true) closeClipboardSearch(openBoard = true)
            return true
        }
        val overlay = keyboardViewModel.page.value as? KeyboardPage.Overlay
        if (keyCode == KeyEvent.KEYCODE_BACK && overlay != null) {
            if (event?.isCanceled != true) {
                keyboardViewModel.backFromOverlay()
            }
            return true
        }
        // 候选展开着时返回只收起展开页，编码和键盘都留着（11-03：一下就把展开、键盘和编码全清了）。
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboardViewModel.candidatePageExpanded.value) {
            if (event?.isCanceled != true) keyboardViewModel.setCandidatePageExpanded(false)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && uiState.value.handwritingExpanded) {
            if (event?.isCanceled != true) {
                com.kingzcheung.xime.ui.keyboard.HandwritingFullscreenMemory.write(this, false)
                uiState.value = uiState.value.copy(handwritingExpanded = false)
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboardCallbacks?.onDismissPreeditEditor != null) {
            if (event?.isCanceled != true) keyboardCallbacks?.onDismissPreeditEditor?.invoke()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && uiState.value.showKeyboardResize) {
            if (event?.isCanceled != true) cancelKeyboardResize()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && hasHardwareKeyboard && hardwareKeyboardDisplay.screenKeyboardRequested) {
            if (event?.isCanceled != true) collapseScreenKeyboardToHardwareToolbar()
            return true
        }
        // 返回键就是退出键盘：听写同时结束，不收成悬浮条（用户 10-24）。
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.isCanceled != true && uiState.value.isVoiceMode &&
            !voiceKeepsListeningAfter(VoiceSessionEnd.BACK_KEY)) { stopVoicePillResume(); stopVoiceBeforeLeaving() }
        return super.onKeyUp(keyCode, event)
    }

    override fun sendKeyEvent(keyCode: Int) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            schemaController.moveEditorCursorVertical(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            return
        }
        val connection = editorConnection() ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun executeCommand(name: String) {
        when (name) {
            "clear_composition" -> {
                keyRouter.postRimeJob {
                    keyRouter.endQueuedComposition()
                    withContext(Dispatchers.Main) {
                        mainHandler.post { updateUI() }
                    }
                }
            }
            "show_ime_picker" -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            }
            com.kingzcheung.xime.keyboard.CtrlShortcuts.ENTER_COMMAND -> ctrlEnter()
            com.kingzcheung.xime.keyboard.CtrlShortcuts.SHIFT_ENTER_COMMAND -> shiftEnter()
            else -> com.kingzcheung.xime.keyboard.CtrlShortcuts.letterOf(name)?.let { letter ->
                // Ctrl+A/C/X/V 和编辑面板的全选、复制、剪切、粘贴同一路；终端里 Ctrl+C 等是终端自己的键，照旧发按键。
                val editing = com.kingzcheung.xime.keyboard.CtrlShortcuts.editingAction(letter)
                if (editing != null && !isTerminalEditor()) {
                    schemaController.handleToolbarEditingAction(editing)
                    return@let
                }
                // 屏幕上的符号键按住当 Ctrl：和实体键盘一样把 Ctrl+字母交给应用，编码中的拼音不动。
                val now = SystemClock.uptimeMillis()
                // 焦点在键盘自己的输入框时 Ctrl+Z 等给那个框，不给后面的应用。
                val connection = editorConnection()
                com.kingzcheung.xime.keyboard.CtrlShortcuts.keyEvents(letter, now).forEach { connection?.sendKeyEvent(it) }
            } ?: FileLogger.w(TAG, "Unknown command: $name")
        }
    }

    /**
     * Ctrl+回车 = 发送（屏幕上按住 !@# 点回车、实体键盘 Ctrl+回车）。还在打字、或在键盘自己的输入框里（快捷发送、
     * 工具面板）时和回车一样，只上屏编码，不把拼音发出去；终端照实体键盘发 Ctrl+回车；其他按 [EnterKeyAction.ctrlEnter]，
     * 和 Ctrl+A/C/X/V 同一条编辑队列。
     */
    private fun ctrlEnter() {
        val composing = candidateState.value.let { it.isComposing || it.inputText.isNotEmpty() || it.preeditText.isNotEmpty() } ||
            t9PartialSegments.isNotEmpty()
        val state = uiState.value
        when {
            composing || state.inputTarget.isInternal || state.toolPanelInputFocused || state.showQuickSendForm ->
                keyRouter.handleKeyPress("enter", false)
            isTerminalEditor() -> currentInputConnection?.let { ic ->
                com.kingzcheung.xime.keyboard.CtrlShortcuts.enterKeyEvents(SystemClock.uptimeMillis()).forEach(ic::sendKeyEvent)
            }
            else -> schemaController.handleToolbarEditingAction("send")
        }
    }

    /**
     * Shift+回车 = 换行（屏幕上按住 Shift 点回车、从 Shift 拖到回车、实体键盘 Shift+回车），不管应用把回车设成什么
     * （用户 11-04）。还在打字、或在键盘自己的输入框里时和回车一样只上屏编码；终端发 Shift+回车键；
     * 其他按 [EnterKeyAction.shiftEnter]，和 Ctrl+回车同一条编辑队列。
     */
    private fun shiftEnter() {
        when {
            enterStaysPlain() -> keyRouter.handleKeyPress("enter", false)
            isTerminalEditor() -> currentInputConnection?.let { ic ->
                com.kingzcheung.xime.keyboard.CtrlShortcuts.shiftEnterKeyEvents(SystemClock.uptimeMillis()).forEach(ic::sendKeyEvent)
            }
            else -> schemaController.handleToolbarEditingAction("newline")
        }
    }

    /** 还在打字、或焦点在键盘自己的输入框（快捷发送、工具面板）：Shift+回车和回车一样。 */
    private fun enterStaysPlain(): Boolean {
        val composing = candidateState.value.let { it.isComposing || it.inputText.isNotEmpty() || it.preeditText.isNotEmpty() } ||
            t9PartialSegments.isNotEmpty()
        val state = uiState.value
        return composing || state.inputTarget.isInternal || state.toolPanelInputFocused || state.showQuickSendForm
    }

    override fun repeatLastInput() {
        val lastText = predictionManager.lastCommittedText
        if (lastText.isNotEmpty()) {
            currentInputConnection?.commitText(lastText, 1)
        }
    }

    override fun dispatchKey(key: String) {
        // 与物理键盘 onKeyDown 同一入口：handleKeyPress 内部自行调度到 key-processing 线程
        keyRouter.handleKeyPress(key, false)
    }

    // ── 原有方法 ──

    
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        endGlidePreview()
        super.onStartInput(attribute, restarting)
        editorStartedAt = android.os.SystemClock.uptimeMillis()
        val preview = KeyboardPanelLaunch.isPreview(attribute, packageName)
        val requestedTool = if (preview) KeyboardPanelLaunch.editorPanel(attribute) else null
        if ((preview || settingsPreviewEditor) && requestedTool != KeyboardPanelLaunch.Panel.RESIZE) cancelKeyboardResize()
        if ((preview || settingsPreviewEditor) && requestedTool != KeyboardPanelLaunch.Panel.ARRANGE &&
            (keyboardViewModel.page.value as? KeyboardPage.Overlay)?.route == OverlayRoute.ToolbarCustomize) keyboardViewModel.closeOverlay()
        settingsPreviewEditor = preview
        settingsPreviewPanel = requestedTool
        settingsExample = KeyboardPanelLaunch.Example.entries.firstOrNull { preview && it.option == attribute?.privateImeOptions }
            ?: KeyboardPanelLaunch.Example.Keyboard
        KeyboardPanelLaunch.previewVisible = settingsPreviewEditor && isInputViewShown
        if (!restarting) {
            settingsResizeBounds = null
            settingsArrangeBounds = null
        }
        // 宿主在打拼音时重启了同一个输入框（QQ 留言板等每改一次字就重设文字）：编码原样还在就接着打。
        val resumingAfterLock = lockCompositionSuspended
        if (resumeCompositionAfterRestart(attribute, restarting, resumingAfterLock)) {
            lockCompositionSuspended = false
            if (resumingAfterLock) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                refreshLockResumedCandidates()
            }
            return
        }
        lockCompositionSuspended = false
        // 新连接开始时旧组合区一律失效；同一输入框重启时撤掉被定格的拼音编码。
        val identity = attribute?.let { Triple(it.packageName, it.fieldId, it.inputType) }
        val sameField = restarting && identity != null && identity == lastEditorIdentity
        // 听写中宿主重启了同一个输入框（B 站评论框写满一行时重设文字、改输入类型，光标被放回开头）：
        // 不算换输入框，听写接着写（见 VoiceRecognitionHandler.continueAfterHostRestart）。
        val voiceThroughRestart = restarting && (uiState.value.isVoiceMode || voiceRecordingStarted) &&
            !attribute?.packageName.isNullOrEmpty() && attribute?.packageName == lastEditorIdentity?.first &&
            attribute?.fieldId == lastEditorIdentity?.second &&
            !EditorInfoClassifier.isSecretEditor(attribute)
        val hostCaretsBeforeRestart = listOf(voiceHostCaret, voiceHostCaretBefore)
        lastEditorIdentity = identity
        // Search belongs to the field it was opened in; another field starts without it.
        if (!sameField) closeClipboardSearch(openBoard = false)
        // 换了输入框：上一处刚上屏的词不再跟着退格减学习。
        if (!sameField) rimeEngine.rejectedCommit.clear()
        val detachedComposition = inlineComposition.detach()
        detachedComposition?.let { removeDetachedComposition(it, attribute, restarting, sameField) }
        // 新输入框的初始光标：之后打的拼音从这里开始写（重启后靠它找回编码）。
        if (detachedComposition == null) inlineComposition.selectionReported(attribute?.initialSelStart ?: -1, attribute?.initialSelEnd ?: -1, -1, -1)
        englishEditorReadCapability.clear()
        if (inputStallWatchdog == null) inputStallWatchdog = InputStallWatchdog(serviceScope, mainHandler) { keyProcessingThread }
        // 卡顿记录只在键盘显示时跑：输入框有焦点但键盘收着（聊天页翻消息）时不再每 0.25 秒唤醒两条线程。
        if (imeWindowShown) inputStallWatchdog?.start()
        hardwareLanguageShortcuts.reset()
        clipboardKeyboard.reset()
        clipboardNavigationKeys.clear()
        hardwareCandidateNavigationKeys.clear()
        glossShiftKeys.clear()
        com.kingzcheung.xime.gloss.CandidateGlossary.physicalShiftHeld.value = false
        com.kingzcheung.xime.gloss.CandidateGlossary.t9NextHeld.value = false
        clipboardRestoreCompact = false
        microphoneRestoreCompact = false
        uiState.value = uiState.value.copy(hardwareOptions = com.kingzcheung.xime.settings.HardwareKeyboardPreferences.read(this))
        // A hardware keyboard can switch editors without starting the soft input view.
        // Never keep the previous editor's position while waiting for this connection.
        uiState.value = uiState.value.copy(cursorAnchor = null, editorBounds = null)
        updateHardwareKeyboardConnection()
        applyCompactMode()
        requestHardwareCursorUpdates()
        if (!voiceThroughRestart) noteVoicePillInterrupted(VoiceSessionEnd.FOCUS_TO_OTHER_FIELD)
        newInputGeneration(keepVoice = voiceThroughRestart)
        if (!voiceThroughRestart && (uiState.value.isVoiceMode || voiceRecordingStarted)) {
            voiceRecognitionHandler.abandonSession()
            voiceRecognitionHandler.cancelPreStart()
            restoreAfterVoiceFinish()
        }
        schemaController.resetEditorSelection()
        schemaController.observeEditorSelection(attribute?.initialSelStart ?: -1, attribute?.initialSelEnd ?: -1)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        loadDarkModePreference()

        val editorPresentation = beginEditorField(attribute, restarting)
        // 新输入框从框架给的初始光标开始记；刚撤掉过定格的编码时光标已变，等宿主报来。密码框、终端不记。
        (currentInputConnection as? TrackedInputConnection)?.memory?.started(
            if (detachedComposition == null) attribute?.initialSelStart ?: -1 else -1,
            if (detachedComposition == null) attribute?.initialSelEnd ?: -1 else -1,
            enabled = !editorSecret && !TerminalEditor.active)
        voicePillResumePending = voicePillResumeEnabled() && voicePillResume.appliesTo(attribute?.packageName) &&
            voicePillResumeFieldAllowed()
        lastClearedText = ""
        lastClearedDocument = null
        lastSwipeDelete = null

        // 旧组合区已在 onStartInput 开头脱离（重启输入时框架也会定格它），此处不再保留标记，
        // 避免 endComposingInputBox 对新连接执行 setComposingText("")（选中文字时等于删除选区）。

        predictionManager.clearCommittedText()
        // 换了输入框（QQ 收起键盘后再点回草稿）：上一个输入框里还没回来的联想、按下退格时留的「在等联想」一并作废。
        // 不作废的话，回来后的第一下退格会被当成「取消联想」吞掉，看起来就是要按两下（用户 11-02）。同一输入框重启不动。
        if (!sameField) predictionManager.invalidatePendingPredictions()
        // 新输入会话清空 partial commit 累积：外部 UI（如设置页输入框"清除"按钮仅清 Compose
        // state）会触发 restartInput → 此处重建 T9，若残留累积会被 buildT9DisplayState 拼进
        // preedit 回灌输入框（2026-08-07 日志实证：清除后 testText 从 '' 回灌为 '几乎'）。
        t9PartialSegments.clear()
        debugLog("onStartInput: cleared lastCommittedText restarting=$restarting sameField=$sameField page=${keyboardViewModel.page.value::class.simpleName}")

        // 跨进程同步文件日志开关（开关在主进程设置页切换）
        FileLogger.setVerboseLoggingEnabled(
            SettingsPreferences.isVerboseLoggingEnabled(this)
        )
        
        uiState.value = uiState.value.copy(
            inputSessionId = System.nanoTime(),
            isAddressField = editorInputPolicy.field.isAddressField,
            isAsciiMode = editorPresentation.isAsciiMode,
            currentSchemaId = editorPresentation.currentSchemaId,
            clipboardImagesExpanded = false,
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
        )
        // 听写接上新的输入会话；文字或光标对不上时照旧结束听写。说进翻译栏这类键盘自己的框时，宿主重启后结束听写，
        // 不拿宿主的光标去对键盘里的框。
        if (voiceThroughRestart && (voiceTarget.internal || !voiceRecognitionHandler.continueAfterHostRestart(hostCaretsBeforeRestart))) {
            noteVoicePillInterrupted(VoiceSessionEnd.FOCUS_TO_OTHER_FIELD)
            voiceRecognitionHandler.endInputSession()
            if (uiState.value.isVoiceMode || voiceRecordingStarted) restoreAfterVoiceFinish()
        }
        voiceHostCaretBefore = -1
        voiceHostCaret = attribute?.takeIf { it.initialSelStart >= 0 && it.initialSelStart == it.initialSelEnd }?.initialSelStart ?: -1

        // Show the existing layout immediately. Native preparation is queued below,
        // before the first key, rather than loading translators on this callback.
        if (!restarting) keyboardViewModel.resetKeyboard(uiState.value.isAsciiMode,
            uiState.value.currentSchemaId.ifBlank { SettingsPreferences.getCurrentSchema(this) },
            editorInputPolicy.field.numberPanel ||
                (EditorInfoClassifier.isNumberEditor(attribute) && SettingsPreferences.isAutoNumberKeyboardEnabled(this)))
        synchronizeKeyboardInputSession()

        // 先重置候选状态到初始值，避免前一 session 的残留状态影响新输入
        candidateState.value = CandidateState()

        // 获取最近30秒的剪切板内容
        ensureClipboardManagerInitialized()
        try {
            if (clipboardManager.images.pasteFailure.value?.packageName != attribute?.packageName) clipboardManager.images.clearPasteFailure()
            clipboardManager.refreshClipboard()
            recentClipboardItemsState.value = clipboardManager.getRecentItems(30)
            val recentImage = clipboardManager.images.preview.value?.takeIf { it.timestamp >= (recentClipboardItemsState.value.maxOfOrNull { item -> item.timestamp } ?: 0) }
            // 将最近剪切板内容显示在候选栏。没有可显示的就不标成「在显示剪贴板」：以前一律标上，
            // 回到输入框（微信退出聊天再进来）第一下退格只去收起这个看不见的剪贴板，不删字（用户 11-01）。
            candidateState.value = candidateState.value.copy(
                candidates = clipboardBarTexts(recentClipboardItemsState.value),
                clipboardExtractedCount = com.kingzcheung.xime.clipboard.ClipboardLinkChoices.extractedCount(recentClipboardItemsState.value),
                clipboardImage = recentImage,
                smsVerificationCode = clipboardManager.verificationCode.value?.text,
                candidateComments = emptyList(),
                isShowingRecentClipboard = recentClipboardItemsState.value.isNotEmpty() || recentImage != null
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to get recent clipboard items", e)
        }

        // 监听clipboardItems变化，更新候选栏
        clipboardCollectorJob?.cancel()
        clipboardCollectorJob = serviceScope.launch {
            combine(clipboardManager.clipboardItems, clipboardManager.verificationCode, clipboardManager.images.preview) { _, _, _ -> Unit }.collect {
                val items = clipboardManager.getRecentItems(30)
                if (!canShowClipboardSuggestions(candidateState.value.isComposing,
                        candidateState.value.pendingEnglishText, inputCommands.hasPendingCommands)) return@collect
                recentClipboardItemsState.value = items
                val image = clipboardManager.images.preview.value?.takeIf { it.timestamp >= (items.maxOfOrNull { item -> item.timestamp } ?: 0) }
                if (items.isNotEmpty() || image != null) {
                    // 清空Rime联想词等
                    rimeEngine.clearComposition()
                    candidateState.value = candidateState.value.copy(
                        candidates = clipboardBarTexts(items),
                        clipboardExtractedCount = com.kingzcheung.xime.clipboard.ClipboardLinkChoices.extractedCount(items),
                        clipboardImage = image,
                        smsVerificationCode = clipboardManager.verificationCode.value?.text,
                        candidateComments = emptyList(),
                        inputText = "",
                        isComposing = false,
                        associationCandidates = emptyList(),
                        isShowingRecentClipboard = true
                    )
                } else if (candidateState.value.isShowingRecentClipboard) {
                    // 如果没有recent items，清空候选栏
                    candidateState.value = candidateState.value.copy(
                        candidates = emptyList(),
                        clipboardImage = null,
                        candidateComments = emptyList(),
                        isShowingRecentClipboard = false,
                        candidateActions = emptyList()
                    )
                }
            }
        }

        EditorInputPreparation(this).schedule(restarting, EditorInfoClassifier.isNumberEditor(attribute))
        attribute?.let { updateEnterKeyText(it) }
    }
    
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        KeyboardPanelLaunch.previewVisible = settingsPreviewEditor
        // 动态背景开着才查应用类别（每个包名只查一次，见 HardwareGamePackages）。
        backgroundMotionGame.value = com.kingzcheung.xime.settings.BackgroundMotionPreferences.read(this).enabled &&
            hardwareGamePackages.isGame(info?.packageName)
        KeyLatencyProbe.refresh(window.window)
        startRuntimeObservation()
        DictionaryPrefetch.onShow(this, serviceScope, rimeEngine)
        info?.let { updateEnterKeyText(it) }
        updateHardwareKeyboardConnection()
        applyCompactMode()
        softEditorBoundsOnScreen = null
        requestSoftEditorBounds()
        // 开车连续语音：回到刚才的应用，键盘直接是悬浮条，随后自动开麦。
        if (voicePillResumePending && !settingsPreviewEditor) {
            voicePillResumePending = false
            if (!uiState.value.isCompact) {
                voicePillByUser = true
                voicePillPackage = currentInputEditorInfo?.packageName
                uiState.value = uiState.value.copy(voiceCollapsed = true, handwritingExpanded = false)
                scheduleVoicePillResume()
            }
        }
        restoreHandwritingFullscreen()
        applyWindowBackground()
        requestHardwareCursorUpdates()
        openPendingOcrPanel()
        openPendingKeyboardPanel()
        // 释义词典随设置与下载状态后台加载/释放；选拼音时不占内存。
        com.kingzcheung.xime.gloss.CandidateGlossary.prepare(this)
        // 通讯录插件：启用且授权时后台读入（只在内存），关了就丢掉。
        com.kingzcheung.xime.contacts.ContactSuggestions.sync(this)
    }

    /** 手写上次是全屏就直接全屏打开（11-05）；首次和上次半屏都照旧半屏。 */
    private fun restoreHandwritingFullscreen() {
        val state = uiState.value
        val handwritingPage = (keyboardViewModel.page.value.underlyingPage() as? KeyboardPage.Main)?.type ==
            com.kingzcheung.xime.keyboard.MainType.HANDWRITING
        if (!com.kingzcheung.xime.ui.keyboard.HandwritingFullscreenMemory.shouldRestore(
                remembered = com.kingzcheung.xime.ui.keyboard.HandwritingFullscreenMemory.read(this),
                handwritingPage = handwritingPage,
                alreadyExpanded = state.handwritingExpanded,
                compact = state.isCompact,
                voiceCollapsed = state.voiceCollapsed,
                resizing = state.showKeyboardResize,
                settingsPreview = settingsPreviewEditor,
            )) return
        uiState.value = state.copy(handwritingExpanded = true)
    }

    private fun requestHardwareCursorUpdates() {
        hardwareCursorImmediateOnly = false
        val connection = currentInputConnection ?: return
        if (!hasHardwareKeyboard) {
            try { connection.requestCursorUpdates(0) } catch (_: Exception) { }
            return
        }
        val mode = InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
        fun request(flags: Int, filtered: Boolean = false): Boolean = try {
            if (filtered && AndroidPlatform.cursorAnchorFilter) connection.requestCursorUpdates(flags,
                InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER or InputConnection.CURSOR_UPDATE_FILTER_CHARACTER_BOUNDS or
                    InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS)
            else connection.requestCursorUpdates(flags)
        } catch (_: Exception) { false }
        val acceptedMode = when {
            AndroidPlatform.cursorAnchorFilter && request(mode, filtered = true) -> mode
            request(mode) -> mode
            request(InputConnection.CURSOR_UPDATE_MONITOR) -> InputConnection.CURSOR_UPDATE_MONITOR
            request(InputConnection.CURSOR_UPDATE_IMMEDIATE) -> InputConnection.CURSOR_UPDATE_IMMEDIATE
            else -> 0
        }
        hardwareCursorImmediateOnly = acceptedMode == InputConnection.CURSOR_UPDATE_IMMEDIATE
        if (acceptedMode == 0) uiState.value = uiState.value.copy(cursorAnchor = null, editorBounds = null)
        debugLog("Hardware cursor request package=${currentInputEditorInfo?.packageName} mode=$acceptedMode")
    }

    private val trackedConnectionLock = Any()
    private var trackedConnection: TrackedInputConnection? = null

    /**
     * 宿主连接包一层 [TrackedInputConnection]：光标前文字已知时不再同步等宿主（宿主忙时每次 100 多毫秒）。
     * 同一个连接始终给同一个包装，`currentInputConnection !== original` 这类比较照旧成立。
     */
    override fun getCurrentInputConnection(): InputConnection? {
        val base = super.getCurrentInputConnection() ?: return null
        if (base is TrackedInputConnection) return if (TrackedInputConnection.wantsRaw) base.base else base
        if (TrackedInputConnection.wantsRaw) return base
        synchronized(trackedConnectionLock) {
            trackedConnection?.takeIf { it.base === base }?.let { return it }
            return TrackedInputConnection(base).also { trackedConnection = it }
        }
    }

    /** 宿主报来的光标还是输入框现在的光标（Android 可能晚送一条更早的「写到末尾」）。 */
    private fun hostSelectionIsCurrent(selection: Int): Boolean = currentInputConnection?.let { connection ->
        val value = runCatching { connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull()
        value != null && value.selectionStart == value.selectionEnd && value.startOffset + value.selectionStart == selection
    } == true

    /**
     * 拼音写在输入框里、正在组词时，用户把光标点到（或选中到）编码以外：宿主报的组合区还在，光标不在里面。
     * 宿主可能晚送一条更早的选区，所以再问一次输入框现在的选区，对得上才算。日语读音、键盘自己的输入框、
     * 语音、密码框不管；编码显示在候选栏时光标本来就不影响组词。
     */
    private fun caretLeftInlineComposition(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int): Boolean {
        val state = uiState.value
        if (!inlineComposition.active || inlineComposition.text.isNullOrEmpty() || !candidateState.value.isComposing ||
            state.inputTarget.isInternal || state.isVoiceMode || isSecretEditor() || schemaController.japaneseReadingOnScreen() ||
            TerminalEditor.inputTextLocation(this) != SettingsPreferences.INPUT_TEXT_INPUT_BOX) return false
        if (!inlineCaretOutsideSpan(selStart, selEnd, candidatesStart, candidatesEnd)) return false
        val value = currentInputConnection?.let { connection ->
            runCatching { connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull()
        } ?: return false
        return value.startOffset + value.selectionStart == selStart && value.startOffset + value.selectionEnd == selEnd
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // 先对上回执，下面再问光标前文字时才知道记住的那份还算不算数。
        (currentInputConnection as? TrackedInputConnection)?.memory
            ?.selectionReported(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        inlineComposition.selectionReported(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val restored = if (candidateState.value.isComposing && !uiState.value.inputTarget.isInternal &&
            !uiState.value.isVoiceMode && !isSecretEditor())
            inlineComposition.restoreLostSpan(currentInputConnection, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        else null
        schemaController.observeEditorSelection(newSelStart, newSelEnd)
        if (restored == null && caretLeftInlineComposition(newSelStart, newSelEnd, candidatesStart, candidatesEnd))
            keyRouter.finishCompositionAfterCaretLeft()
        val hostCaret = if (newSelStart >= 0 && newSelStart == newSelEnd) newSelStart else -1
        if (hostCaret != voiceHostCaret) { voiceHostCaretBefore = voiceHostCaret; voiceHostCaret = hostCaret }
        if (restored != null || oldSelStart != newSelStart || oldSelEnd != newSelEnd) {
            inlineComposition.hostCaret(restored?.start ?: candidatesStart, restored?.end ?: candidatesEnd, newSelStart, newSelEnd)?.let { offset ->
                val state = uiState.value
                if (!state.inputTarget.isInternal && !state.isAsciiMode && state.inputProfile.capabilities.editablePinyin &&
                    candidateState.value.isComposing && TerminalEditor.inputTextLocation(this) == SettingsPreferences.INPUT_TEXT_INPUT_BOX &&
                    currentInputConnection?.let { connection ->
                        // Android can deliver an earlier write-to-end after a newer caret move.
                        val value = runCatching { connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull()
                        value != null && value.selectionStart == value.selectionEnd &&
                            value.startOffset + value.selectionStart == newSelStart
                    } == true &&
                    keyboardCallbacks?.onPreeditCursorMove?.invoke("preedit_cursor:inline:$offset") == true) {
                    inlineComposition.moveCaret(offset)
                } else if (!state.inputTarget.isInternal && schemaController.japaneseReadingOnScreen() &&
                    TerminalEditor.inputTextLocation(this) == SettingsPreferences.INPUT_TEXT_INPUT_BOX &&
                    hostSelectionIsCurrent(newSelStart)) {
                    // 日语：在输入框里点了读音中间，接着打的字插在那里（11-05 用户反馈：句中光标处输入的内容出现在句末）。
                    inlineComposition.moveCaret(offset)
                    keyRouter.postRimeJob("japanese_caret") { japaneseInputController.placeCaret(offset) }
                }
            }
        }
        if (uiState.value.isVoiceMode && uiState.value.voiceSticky && !voiceStopRequested) {
            // 听写中输入框从有字变成空：多半是点了发送，听写结束。
            val ic = currentInputConnection
            val text = runCatching {
                val before = ic?.getTextBeforeCursor(1, 0)
                val after = ic?.getTextAfterCursor(1, 0)
                if (before == null || after == null) null else before.toString() + after.toString()
            }.getOrNull()
            if (voiceFieldCleared(voiceFieldHadText, text)) {
                noteVoicePillInterrupted(VoiceSessionEnd.FIELD_CLEARED)
                voicePillRestartInPlace = voicePillResume.armed
                stopVoiceBeforeLeaving()
            }
            else if (!text.isNullOrEmpty()) voiceFieldHadText = true
        }
        // 验证码已经进了输入框（手打、系统粘贴或应用自动填充）：候选栏不再提示它。
        val smsCode = if (::clipboardManager.isInitialized) clipboardManager.verificationCode.value?.text else null
        if (smsCode != null && com.kingzcheung.xime.clipboard.ClipboardManager.smsCodeEntered(smsCode,
                runCatching { currentInputConnection?.getTextBeforeCursor(smsCode.length, 0) }.getOrNull())) {
            clipboardManager.dismissVerificationCode()
        }
        val current = candidateState.value
        if (!current.isComposing && current.inputText.isEmpty() &&
            (predictionManager.hasPendingPrediction || current.associationCandidates.isNotEmpty())) {
            val before = runCatching { currentInputConnection?.getTextBeforeCursor(maxOf(25, current.pendingEnglishText.length), 0)?.toString() }.getOrNull()
            val contextChanged = before != null && if (current.pendingEnglishText.isNotEmpty())
                !before.endsWith(current.pendingEnglishText) else !predictionManager.matchesCommittedContext(before)
            if (newSelStart != newSelEnd || contextChanged) {
                predictionManager.invalidatePendingPredictions()
                before?.let(predictionManager::replaceCommittedText)
                candidateState.value = current.copy(associationCandidates = emptyList(), pendingEnglishText = "")
                keyRouter.resetHardwareCandidateSelection()
            }
        }
        if (hasHardwareKeyboard && hardwareCursorImmediateOnly) {
            try { currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE) } catch (_: Exception) { }
        }
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (cursorLinePending) {
            cursorLinePending = false
            val ic = currentInputConnection
            val sample = runCatching {
                "${ic?.getTextBeforeCursor(40, 0) ?: ""}${ic?.getTextAfterCursor(40, 0) ?: ""}"
            }.getOrDefault("")
            com.kingzcheung.xime.ui.keyboard.CursorLineMetrics.update(runCatching { cursorAnchorCharsPerLine(info, sample) }.getOrNull())
        }
        if (!hasHardwareKeyboard) {
            noteSoftEditorBounds(info)
            return
        }
        try {
            val anchor = resolveHardwareCursorAnchor(info)
            uiState.value = uiState.value.copy(cursorAnchor = anchor, editorBounds = resolveHardwareEditorBounds(info))
            debugLog("Hardware cursor anchor=$anchor markerFlags=${info.insertionMarkerFlags}")
        } catch (e: Exception) {
            uiState.value = uiState.value.copy(cursorAnchor = null, editorBounds = null)
            FileLogger.e(TAG, "onUpdateCursorAnchorInfo failed", e)
        }
    }

    /** 固定键盘比窗口窄时才用得上；系统不支持输入框范围（Android 13 以下）或输入框不回就照旧避让。 */
    private fun requestSoftEditorBounds() {
        if (hasHardwareKeyboard || !AndroidPlatform.cursorAnchorFilter || uiState.value.isFloatingMode) return
        currentInputConnection?.let { requestLineLayout(it) }
    }

    private fun noteSoftEditorBounds(info: CursorAnchorInfo) {
        val bounds = runCatching { resolveHardwareEditorBounds(info) }.getOrNull()
        if (bounds == softEditorBoundsOnScreen) return
        softEditorBoundsOnScreen = bounds
        // 重新上报避让：relayout 触发 traversal，onComputeInsets 按新的输入框范围算。
        if (::keyboardContainer.isInitialized && fixedCardBounds != null) keyboardContainer.requestLayout()
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return !hardwareGamePassthrough
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        if (hardwareGamePassthrough && flags and android.view.inputmethod.InputMethod.SHOW_EXPLICIT != 0) {
            gameShowExplicit = true
            debugLog("Game passthrough: explicit show request package=${currentInputEditorInfo?.packageName}")
        }
        if (hardwareGamePassthrough) {
            debugLog("Game passthrough: keyboard stays hidden package=${currentInputEditorInfo?.packageName}")
            voicePillSelfShow = false
            return false
        }
        // 悬浮条状态下再点输入框：App 会再次请求弹出键盘，换回完整键盘；语音照常听写，与展开按钮相同。
        if (!voicePillResumePending &&
            voicePillExpandsOnShowRequest(uiState.value.voiceCollapsed, voicePillSelfShow, configChange)) expandFromVoicePill()
        voicePillSelfShow = false
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        currentResolvedKeyboardSize = null
        clearHardwareSurfaceBounds()
        softEditorBoundsOnScreen = null
        uiState.value = uiState.value.copy(cursorAnchor = null, editorBounds = null)
        updateHardwareKeyboardConnection()
        // 框架在这里用 currentInputConnection 重启输入并把它存成当前连接：给原连接，别把包装存进去。
        // 期间经原连接做的编辑不会记进光标前文字，结束后整份作废，下次照旧去读。
        TrackedInputConnection.withRawConnection { super.onConfigurationChanged(newConfig) }
        trackedConnection?.memory?.edited()
        if (newConfig.screenWidthDp > newConfig.screenHeightDp) {
            closeToolPanel()
        }
        // Its results were sized for the old screen; rotating closes search like other panels.
        closeClipboardSearch(openBoard = false)
        applyCompactMode()
        loadDarkModePreference()
        applyWindowBackground()
        requestHardwareCursorUpdates()
    }

    /** 引擎学习开关跟随生效中的无痕；在输入队列中执行，与按键保持先后顺序。 */
    internal val privacy = InputPrivacy { active -> inputCommands.submit { rimeEngine.setIncognito(active) } }

    internal fun applyWindowBackground() {
        val state = uiState.value
        val isDark = isDarkTheme()
        try {
            val theme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getRenderingScheme(state.themeId)
            // 图片背景无法映射到 window 层，用主题主色作为导航栏/窗口兜底色；
            // solid / gradient 用解析出的键盘背景兜底色。
            val bgColor = if (theme.keyboardBackground?.type == "image") {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getPrimaryColor(state.themeId, isDark)
            } else {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getKeyboardBackgroundColor(state.themeId, isDark)
            }
            val argb = (bgColor.alpha * 255).toInt() shl 24 or
                (bgColor.red * 255).toInt() shl 16 or
                (bgColor.green * 255).toInt() shl 8 or
                (bgColor.blue * 255).toInt()
            window.window?.let { win ->
                if (state.isCompact || state.voiceCollapsed) {
                    // 硬件键盘候选栏模式：窗口透明
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                } else if (state.isFloatingMode) {
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                } else {
                    // 非浮动模式：参考成熟输入法 FULL 方案的背景/高度布局。
                    // 1) edge-to-edge：窗口绘制到系统导航栏后面，键盘背景（渐变/图片）可延伸到底部；
                    // 2) 窗口背景透明：键盘内容由 Compose 绘制，键盘上方露出应用内容而不是白色/主题色块；
                    // 3) 直角模式由 Compose 填充导航区；圆角模式保留透明并启用系统对比度保护。
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (AndroidPlatform.barContrast) {
                        win.isNavigationBarContrastEnforced = SettingsPreferences.roundedKeyboardBottom(this)
                    }
                    if (AndroidPlatform.navBarColorIgnored) {
                        // Android 15 起（targetSdk 35）这个颜色系统不再画，导航区照旧由 Compose 画；只是把键盘底色报出去，
                        // 小米「输入法全面屏优化」（含 LSP 模块开的）用它给键盘下面那条系统栏上色，不报就是系统默认色（#144）。
                        // 图片/渐变背景没有单一颜色可报，玻璃效果（半透明）及底部圆角都需要保持透明导航栏。
                        val bgType = theme.keyboardBackground?.type
                        val glass = com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.read(this, isDark).frostedGlass
                        val rounded = SettingsPreferences.roundedKeyboardBottom(this)
                        val transparentNav = glass.enabled || rounded || bgType == "image" || bgType == "gradient"
                        win.setNavigationBarColor(if (transparentNav) android.graphics.Color.TRANSPARENT
                            else bgColor.copy(alpha = 1f).toArgb())
                    } else {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                }
                // 状态栏底色也要透明（11-05 微信打开键盘时点图片预览，键盘收起前屏幕顶上有条黑线）：
                // 系统给输入法窗口开了 FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS，状态栏底色默认是系统主题的不透明深色。
                // 平时输入法窗口在状态栏下面，用不上；应用切到全屏（图片预览、视频）隐藏状态栏那一下，
                // 输入法窗口先长到屏幕顶上、状态栏还没退完，系统就在窗口顶上画一条状态栏高的深色底，直到键盘收起。
                win.statusBarColor = android.graphics.Color.TRANSPARENT
                if (AndroidPlatform.barContrast) win.isStatusBarContrastEnforced = false
                win.decorView?.let { decor ->
                    val controller = androidx.core.view.WindowInsetsControllerCompat(win, decor)
                    val rounded = SettingsPreferences.roundedKeyboardBottom(this)
                    val systemDark = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val colors = KeysConfigHelper.getKeyboardColors()
                    val textValue = if (isDark) colors.keyTextColorDark else colors.keyTextColor
                    val textColor = com.kingzcheung.xime.ui.theme.KeyboardThemes.getKeyTextColorOverride(state.themeId, isDark)
                        ?: androidx.compose.ui.graphics.Color(if (textValue > 0xFFFFFF) textValue else (0xFF000000L or textValue))
                    controller.isAppearanceLightNavigationBars =
                        com.kingzcheung.xime.ui.keyboard.useDarkNavigationIcons(rounded, systemDark, textColor.luminance())
                }
                // setDecorFitsSystemWindows(false) 后必须重新分发 insets，
                // 否则 onApplyWindowInsets 不会触发、底部导航栏高度检测不到。
                win.decorView?.requestApplyInsets()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "applyWindowBackground failed", e)
        }
    }

    private fun applyCompactMode() {
        val current = uiState.value
        val isCompact = if (settingsPreviewEditor) settingsExample == KeyboardPanelLaunch.Example.Hardware else hardwareKeyboardDisplay.compact
        FileLogger.i(
            TAG,
            "applyCompactMode: keyboardCfg=${keyboardConfigName(resources.configuration.keyboard)}, " +
                "hasHardwareKeyboard=$hasHardwareKeyboard, isCompact=$isCompact (was ${current.isCompact})"
        )
        if (current.isCompact != isCompact) {
            if (isCompact) {
                // A keyboard may be attached while a full-screen editor/resize layer is open.
                // Those layers must not keep a full-window touch region behind the toolbar.
                cancelKeyboardResize()
                keyboardCallbacks?.onDismissPreeditEditor?.invoke()
                // The compact toolbar has no search bar: search would stay open but unseen.
                closeClipboardSearch(openBoard = false)
                if (uiState.value.isVoiceMode) endVoiceSession()
                closeToolPanel()
                closeQuickSend()
                keyboardViewModel.closeOverlay()
                keyboardViewModel.setCandidatePageExpanded(false)
                uiState.value = uiState.value.copy(
                    isCompact = true, voiceCollapsed = false, handwritingExpanded = false, clipboardImagesExpanded = false,
                )
            } else uiState.value = uiState.value.copy(isCompact = false)
            refreshKeyboardGeometry()
            applyWindowBackground()
        }
    }

    private fun showScreenKeyboardFromHardware(route: OverlayRoute? = null) {
        if (!hasHardwareKeyboard) return
        hardwareKeyboardDisplay = hardwareKeyboardDisplay.showOnScreen()
        if (route == null) keyboardViewModel.closeOverlay() else keyboardViewModel.showOverlay(route)
        applyCompactMode()
    }

    /** Collapse only the surface: the current composing text and candidate session stay alive. */
    private fun collapseScreenKeyboardToHardwareToolbar(): Boolean {
        if (!hasHardwareKeyboard || !hardwareKeyboardDisplay.screenKeyboardRequested) return false
        hardwareKeyboardDisplay = hardwareKeyboardDisplay.collapseToToolbar()
        applyCompactMode()
        requestHardwareCursorUpdates()
        return true
    }

    private fun clearHardwareSurfaceBounds() {
        hardwareToolbarBounds.value = null
        hardwareCandidateBounds = null
        hardwarePreeditBounds = null
        updateGlassSurface("hardware-toolbar", null)
        updateGlassSurface("hardware-candidates", null)
        updateGlassSurface("hardware-preedit", null)
    }

    private fun updateHardwarePreeditBounds(bounds: android.graphics.Rect?) {
        val next = bounds?.takeIf { uiState.value.isCompact && !it.isEmpty }?.let { android.graphics.Rect(it) }
        if (hardwarePreeditBounds == next) return
        hardwarePreeditBounds = next
        updateGlassSurface("hardware-preedit", next)
        if (::keyboardContainer.isInitialized) keyboardContainer.requestLayout()
    }

    private fun updateHardwareSurfaceBounds(toolbar: Boolean, bounds: android.graphics.Rect?) {
        val current = if (toolbar) hardwareToolbarBounds.value else hardwareCandidateBounds
        val next = bounds?.takeIf { (uiState.value.isCompact || uiState.value.voiceCollapsed) && !it.isEmpty }?.let { android.graphics.Rect(it) }
        if (current == next) return
        if (toolbar) hardwareToolbarBounds.value = next else hardwareCandidateBounds = next
        updateGlassSurface(if (toolbar) "hardware-toolbar" else "hardware-candidates", next)
        if (::keyboardContainer.isInitialized) keyboardContainer.requestLayout()
    }

    private fun keyboardConfigName(value: Int): String = when (value) {
        android.content.res.Configuration.KEYBOARD_UNDEFINED -> "UNDEFINED"
        android.content.res.Configuration.KEYBOARD_NOKEYS -> "NOKEYS"
        android.content.res.Configuration.KEYBOARD_QWERTY -> "QWERTY"
        android.content.res.Configuration.KEYBOARD_12KEY -> "12KEY"
        else -> "UNKNOWN($value)"
    }

    private fun moveFloatingWindow(dx: Int, dy: Int) {
        window.window?.let { win ->
            val lp = win.attributes
            if (lp.gravity != (android.view.Gravity.TOP or android.view.Gravity.START)) {
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            lp.x = (lp.x + dx).coerceAtLeast(0)
            lp.y = (lp.y + dy).coerceAtLeast(0)
            win.attributes = lp
        }
    }

    private fun updateEnterKeyText(editorInfo: EditorInfo) {
        uiState.value = uiState.value.copy(enterKeyText = enterKeyTextFor(editorInfo))
    }

    /**
     * 关掉工具面板、快捷发送表单后回车键回到当前输入框自己的样子。原来写死“发送”，
     * 切悬浮会顺带关工具面板，同一个输入框的 ↵ 就变成了发送箭头。
     */
    internal fun editorEnterKeyText(): String = currentInputEditorInfo?.let(::enterKeyTextFor) ?: "发送"

    /** 键面和回车实际做的事同一判断（[EnterKeyAction]）：写换行就换行，写发送才发送。 */
    private fun enterKeyTextFor(editorInfo: EditorInfo): String =
        EnterKeyAction.label(editorInfo.imeOptions, appSend = SettingsPreferences.isEnterSendsEnabled(this))

    override fun onFinishInput() {
        gameShowExplicit = false
        suspendCompositionForScreenOff()
        endGlidePreview()
        // 离开后的最后一帧仍不可输入，下一次 onStartInput 按新编辑器恢复正常行为。
        settingsPreviewPanel = null
        KeyboardPanelLaunch.previewVisible = false
        // 焦点离开输入框：不再弹出悬浮条，听写随输入会话一起结束（clearInputState）。
        noteVoicePillInterrupted(VoiceSessionEnd.FOCUS_LEAVES)
        mainHandler.removeCallbacks(showVoicePillAfterHide)
        mainHandler.removeCallbacks(stopVoiceIfStillHidden)
        // 没有输入框焦点时绝不开麦。
        mainHandler.removeCallbacks(resumeVoicePill)
        voicePillResumePending = false
        voicePillRestartInPlace = false
        voiceKeptAlive = false
        voicePillSelfShow = false
        debugLog("onFinishInput page=${keyboardViewModel.page.value::class.simpleName}")
        englishEditorReadCapability.clear()
        inputStallWatchdog?.stop()
        hardwareLanguageShortcuts.reset()
        clipboardKeyboard.reset()
        clipboardNavigationKeys.clear()
        hardwareCandidateNavigationKeys.clear()
        glossShiftKeys.clear()
        com.kingzcheung.xime.gloss.CandidateGlossary.physicalShiftHeld.value = false
        com.kingzcheung.xime.gloss.CandidateGlossary.t9NextHeld.value = false
        clipboardRestoreCompact = false
        microphoneRestoreCompact = false
        hardwareCursorImmediateOnly = false
        clearHardwareSurfaceBounds()
        try { currentInputConnection?.requestCursorUpdates(0) } catch (_: Exception) { }
        uiState.value = uiState.value.copy(cursorAnchor = null, editorBounds = null)
        if (!lockCompositionSuspended) discardInlinePinyinBeforeFrameworkFinish()
        super.onFinishInput()
        // 离开输入框：之后在网页等处复制的不算密码框里复制的（用户 11-09）。
        privacy.endEditor()
        if (::clipboardManager.isInitialized) clipboardManager.images.dismissPresentedPreview()
        runtimeObserver?.stop()
        inlineSuggestionManager?.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        clearInputState()
        recentClipboardItemsState.value = emptyList()
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 内存回收会让词库与语法文件的缓存页失效，之后的按键变慢；记下级别供对照。
        FileLogger.w("InputMemory", "trim level=$level")
        DictionaryPrefetch.onTrimMemory(level)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        gameShowExplicit = false
        suspendCompositionForScreenOff()
        KeyboardPanelLaunch.previewVisible = false
        DictionaryPrefetch.onHide()
        // 九键换回 26 键后收起：九键词组模型读进来的页不再算在输入法头上。
        serviceScope.launch(Dispatchers.IO) { rimeEngine.releaseT9ModelIfUnused(rimeEngine.getCurrentSchema()) }
        if (!lockCompositionSuspended) discardInlinePinyinBeforeFrameworkFinish()
        // The search bar belongs to this keyboard session; leaving restores learning.
        closeClipboardSearch(openBoard = false)
        super.onFinishInputView(finishingInput)
        softEditorBoundsOnScreen = null
        runtimeObserver?.stop()
        com.kingzcheung.xime.stats.TypingStats.flush()
        com.kingzcheung.xime.association.AssociationManager.flushLearning()
        // App 收起键盘（点了别处）：听写结束，不再自己弹悬浮条；只有收起按钮才出悬浮条（voiceKeepsListeningAfter）。
        voiceKeptAlive = !finishingInput && voiceKeepsAliveOnHide()
        if (!voiceKeptAlive) noteVoicePillInterrupted(VoiceSessionEnd.FOCUS_LEAVES)
        mainHandler.removeCallbacks(resumeVoicePill)
        if (!voiceKeptAlive) voiceRecognitionHandler.endInputSession()
        // 手写模型轻量，按"用键盘时加载、键盘收起即卸载"管理：输入会话结束
        // （收起键盘/焦点离开）即释放，:inference 侧同步卸载模型；未初始化时
        // release() 幂等空操作。下次落笔由 predict 自愈或布局重建重载。
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        // 词库在线更新：键盘收起后才查、才部署，打字时不联网也不重编词库；每天最多一次。
        com.kingzcheung.xime.settings.CellWordAutoUpdate.onKeyboardHidden(this)
    }

    /** 键盘收起后真正拆掉玻璃模糊窗口；见 [onWindowHidden]。 */
    private val hideGlassBlurWindows = Runnable {
        glassWindowVisible = false
        glassBlurWindows.values.forEach { it.setVisible(false) }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        gameShowExplicit = false
        com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner.keyboardShown = false
        backgroundMotionWindowShown.value = false
        imeWindowShown = false
        inputStallWatchdog?.stop()
        KeyboardPanelLaunch.previewVisible = false
        val keepVoice = voiceKeptAlive
        voiceKeptAlive = false
        mainHandler.removeCallbacks(showVoicePillAfterHide)
        mainHandler.removeCallbacks(stopVoiceIfStillHidden)
        if (keepVoice) {
            // 不清输入会话：听写会话以它为准，清了就会被当成换了输入框而丢弃。
            mainHandler.postDelayed(showVoicePillAfterHide, VOICE_PILL_RESHOW_DELAY_MS)
        } else {
            // 返回键、焦点离开、锁屏等：本次听写到此结束，麦克风立即释放。
            voiceRecognitionHandler.endInputSession()
            // 切去别的应用时系统缺内存就卸掉空闲的语音模型；照 AOSP 的 Android 14 起不再发缺内存的回调。
            com.kingzcheung.xime.speech.AsrBackendFactory.keyboardHidden()
        }
        // 玻璃模糊窗口晚 1 秒再拆：onWindowHidden 时系统的下滑收起动画才开始播，立刻拆掉的话，
        // 下滑那一段键盘后面没有模糊，应用的字清清楚楚透出来，看着就是「闪一下」。模糊窗口是输入法窗口的
        // 子窗口，键盘收完会跟着一起藏，晚拆不会多露一块；1 秒内又弹出来（切聊天框）就直接接着用，不重建。
        mainHandler.removeCallbacks(hideGlassBlurWindows)
        mainHandler.postDelayed(hideGlassBlurWindows, GLASS_BLUR_HIDE_DELAY_MS)
        com.kingzcheung.xime.ocr.DocumentOcrActivity.finishHiddenHost()
        clearHardwareSurfaceBounds()
        if (::clipboardManager.isInitialized) clipboardManager.images.dismissPresentedPreview()
        if (::clipboardManager.isInitialized) clipboardManager.images.setKeyboardVisible(false)
        runtimeObserver?.stop()
        clipboardSyncBridge?.setKeyboardVisible(false)
        cancelKeyboardResize()
        if (!keepVoice) clearInputState()
        recentClipboardItemsState.value = emptyList()
        // 键盘隐藏时重置快捷发送表单临时态：表单开启状态下直接隐藏键盘（未走 onClose）
        // 会把 showQuickSendForm 残留到下次弹窗，候选栏上方渲染出残留表单背景造成遮挡；
        // 回车键文案也需一并还原（打开表单时被改为"换行"）。
        closeQuickSend()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // 键盘重新弹出：上次收起时没收到的 Shift 抬起不再算按住（否则候选一直中英对调，空格上屏英文）。
        com.kingzcheung.xime.gloss.CandidateGlossary.physicalShiftHeld.value = false
        // 键盘一出来 CPU 测速就停（设置页里测到一半也停），不和打字抢 CPU。
        com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner.keyboardShown = true
        backgroundMotionWindowShown.value = true
        imeWindowShown = true
        inputStallWatchdog?.start()
        KeyboardPanelLaunch.previewVisible = settingsPreviewEditor
        mainHandler.removeCallbacks(hideGlassBlurWindows)
        glassWindowVisible = true
        glassBlurWindows.values.forEach { it.setVisible(true) }
        openPendingOcrPanel()
        startVoiceAfterPermission()
        requestHardwareCursorUpdates()
        startRuntimeObservation()
        // 键盘弹出时对比系统取色与缓存的动态主题色，壁纸取色变化则重建主题并热更新 UI。
        // 每次弹出只做两次资源读取对比，取色未变时零成本。
        KeyboardThemes.refreshDynamicSchemes(this)
        clipboardSyncBridge?.setKeyboardVisible(true)
        // 短信弹窗里点「复制」时键盘被盖住，回来时输入框没变、不走 onStartInput，键盘重新显示时补读那一条。
        if (::clipboardManager.isInitialized) runCatching { clipboardManager.readMissedCopy() }
        if (::clipboardManager.isInitialized) clipboardManager.images.setKeyboardVisible(true)
    }

    /**
     * 第一次点麦克风弹系统授权，允许后回到输入框时直接开始语音（2026-10-31 平板新装：原来要再点一次）。
     * 和点麦克风走同一条路；已经在语音中、或这个输入框不许语音时不开。
     */
    private fun startVoiceAfterPermission() {
        if (settingsPreviewEditor) return
        if (!com.kingzcheung.xime.util.PermissionHelper.takeVoiceAfterGrant(this)) return
        mainHandler.post {
            if (!settingsPreviewEditor && !uiState.value.isVoiceMode) keyboardCallbacks?.onVoiceStickyToggle?.invoke()
        }
    }

    private fun openPendingOcrPanel() {
        if (settingsPreviewEditor) return
        if (com.kingzcheung.xime.ocr.DocumentOcrActivity.takePending()) {
            showScreenKeyboardFromHardware(OverlayRoute.Ocr)
            keyboardViewModel.showOverlay(OverlayRoute.Ocr)
        }
    }

    /** 设置页要求打开的键盘调节／排列面板：只在本应用的输入框里打开，与菜单入口走同一条路。 */
    private fun openPendingKeyboardPanel() {
        if (!settingsPreviewEditor || !KeyboardPanelLaunch.previewVisible || keyboardCallbacks == null) return
        val panel = KeyboardPanelLaunch.take(settingsPreviewPanel ?: return) ?: return
        mainHandler.post {
            if (!settingsPreviewEditor || !KeyboardPanelLaunch.previewVisible || settingsPreviewPanel != panel) return@post
            when (panel) {
                KeyboardPanelLaunch.Panel.RESIZE -> if (!uiState.value.showKeyboardResize) keyboardCallbacks?.onKeyboardResize?.invoke()
                KeyboardPanelLaunch.Panel.ARRANGE -> if ((keyboardViewModel.page.value as? KeyboardPage.Overlay)?.route != OverlayRoute.ToolbarCustomize)
                    keyboardViewModel.showOverlay(OverlayRoute.ToolbarCustomize)
            }
        }
    }
    
    private fun synchronizeKeyboardInputSession() {
        val state = uiState.value
        keyboardViewModel.synchronizeInputSession(
            state.inputSessionId, state.t9ResetSignal, state.isAsciiMode, state.currentSchemaId,
        )
    }

    /**
     * A new editor generation, for a new field ([onStartInput]) and for the end of input
     * ([clearInputState]) alike: queued work of the old field is refused, its pending candidate
     * commit is dropped and its voice input session ends, releasing the microphone. Each caller
     * then gives [InputUIState.inputSessionId] a new value once its own cleanup is done.
     */
    private fun newInputGeneration(keepVoice: Boolean = false) {
        inputReadiness.newEditorSession()
        keyRouter.discardPendingCandidateCommit()
        if (!keepVoice) voiceRecognitionHandler.endInputSession()
    }

    /**
     * What the new field allows, read from [attribute] in one place: learning and plugin events
     * ([privacy]), completions ([editorRestricted], [editorSecret]) and literal field input
     * ([editorInputPolicy], whose language/schema presentation is returned).
     */
    private fun beginEditorField(attribute: EditorInfo?, restarting: Boolean): EditorSessionPresentation {
        // 敏感输入框（密码等）判定；插件派发读同一结果，这里只重置 composing 去重
        pluginEvents.onStartInput()
        privacy.beginEditor(attribute)
        // 受限输入框判定（密码/终端/NO_SUGGESTIONS）：供英文联想等补全功能短路
        editorRestricted = EditorInfoClassifier.isRestrictedEditor(attribute)
        // 秘密输入框判定（密码/终端，不含 NO_SUGGESTIONS）：英文联想/回删替换的禁用线
        editorSecret = EditorInfoClassifier.isSecretEditor(attribute)
        TerminalEditor.active = EditorInfoClassifier.isTerminalEditor(attribute)
        return editorInputPolicy.begin(attribute, restarting, uiState.value.isAsciiMode, uiState.value.currentSchemaId,
            addressEnglish = SettingsPreferences.getPrefsPublic(this).getBoolean(
                com.kingzcheung.xime.ui.keyboard.KeyboardInputPreferences.ADDRESS_FIELDS_ENGLISH_KEY, true))
    }

    private fun clearInputState() {
        deleteDragGeneration++
        deleteDragActive.value = false
        newInputGeneration()
        if (uiState.value.handwritingExpanded) uiState.value = uiState.value.copy(handwritingExpanded = false)
        if (uiState.value.voiceCollapsed) uiState.value = uiState.value.copy(voiceCollapsed = false)
        voicePillByUser = false
        // 即使 Compose 在隐藏时尚未销毁，旧手写回调也不能再写入宿主。
        uiState.value = uiState.value.copy(inputSessionId = System.nanoTime())
        closeToolPanel()
        // 输入会话结束：关闭残留的面板页面（表情/符号等 overlay），
        // 避免下次键盘弹出时在候选栏上方渲染上次的面板背景
        var page = keyboardViewModel.page.value
        while (page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay) {
            keyboardViewModel.closeOverlay()
            page = keyboardViewModel.page.value
        }
        calculatorEngine.clear()
        keyRouter.englishAutoSpace.disarm()
        if (!lockCompositionSuspended) inputCommands.submit { rimeEngine.clearQueuedComposition(); rimeEngine.clearQueuedT9Composition() }
        t9PartialSegments.clear()
        // 录音已在本方法入口停止；这里仅重置语音 UI。
        if (uiState.value.isVoiceMode || voiceRecordingStarted) {
            isTrackingVoiceButtons = false
            voiceRecordingStarted = false
            voiceAmplitudeState.floatValue = 0f
            uiState.value = uiState.value.copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceButtonState = VoiceButtonState(),
                voiceRecognitionState = RecognitionState.IDLE,
                voiceRecognizedText = "",
                voiceAmplitude = 0f
            )
            keyboardViewModel.exitVoice()
        }
        uiState.value = uiState.value.copy(
            t9ResetSignal = uiState.value.t9ResetSignal + 1,
            t9RightCandidateSelectedCount = 0,
            t9SelectedCandidatePinyin = ""
        )
        synchronizeKeyboardInputSession()
        if (!lockCompositionSuspended) candidateState.value = candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            inputText = "",
            // 编码也清掉：留着的话九键下一串借它当编码显示（11-10 录屏：英文 stamping 挂在九键气泡上不走）。
            preeditText = "",
            isComposing = false,
            isShowingRecentClipboard = false,
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            hasNextPage = false,
            hasPrevPage = false,
            englishReplaceSupported = true,
            candidateActions = emptyList()
        )
        if (!lockCompositionSuspended) endComposingInputBox()
    }

    /**
     * 输入框是否存在 IME 写入的 composing 区域（拼音编码回显 / 语音识别临时文本）。
     * Android 无法查询宿主编辑器的 composing 状态，由写入点主动标记。
     */
    private val inlineComposition = InlineCompositionTracker()
    internal val glideInputPreview by lazy {
        GlideInputPreview(
            connection = { editorConnection() }, session = { uiState.value.inputSessionId },
            baseline = { inlineComposition.text.takeIf { inlineComposition.active } },
            canStart = { !uiState.value.inputTarget.isInternal && !isSecretEditor() && !isTerminalEditor() &&
                (!inlineComposition.active || inlineComposition.text?.let { inlineComposition.caret == it.length } == true) },
            mark = { markInputBoxComposing(it) },
            owns = { inlineComposition.active && inlineComposition.text == it && inlineComposition.caret == it.length },
            clear = { endComposingInputBox() },
        )
    }
    private val glidePreviewRouter by lazy {
        GlidePreviewRouter(
            session = { uiState.value.inputSessionId },
            useInputBox = { TerminalEditor.inputTextLocation(this) == SettingsPreferences.INPUT_TEXT_INPUT_BOX },
            input = glideInputPreview::show, clearInput = glideInputPreview::end,
            candidate = { glideCandidatePreview.value = it },
        )
    }
    internal fun showGlideEnglishPreview(preview: com.kingzcheung.xime.ui.keyboard.GlideEnglishPreview, session: Long) {
        if (session != uiState.value.inputSessionId) return
        glideEnglishPreview.value = preview
        glidePreviewRouter.show(preview.text, session)
    }
    @Volatile private var glidePreviewSequence = 0L
    private var glidePreviewGesture = -1
    private var glidePreviewRevision = 0L
    internal fun showGlidePreviewCode(code: String, session: Long, gesture: Int) {
        if (session != uiState.value.inputSessionId) return
        if (gesture != glidePreviewGesture) {
            glidePreviewGesture = gesture
            glidePreviewRevision = rimeEngine.glidePreviewRevision()
        }
        val revision = glidePreviewRevision
        val seq = ++glidePreviewSequence
        if (code.isEmpty()) return
        keyRouter.postRimeJob("glide_preview") {
            if (seq != glidePreviewSequence) return@postRimeJob
            val result = rimeEngine.glidePreview(code, revision)
            if (result == null) {
                withContext(Dispatchers.Main) {
                    if (seq == glidePreviewSequence && session == uiState.value.inputSessionId &&
                        !rimeEngine.isCandidateRevisionCurrent(revision)) {
                        glideRimePreview.value = null
                        glideInputPreview.end()
                    }
                }
                return@postRimeJob
            }
            val transformed = candidateTransform.transformFor(result)
            val shown = transformed?.let { result.copy(candidates = it.candidates.toTypedArray()) } ?: result
            withContext(Dispatchers.Main) {
                if (seq != glidePreviewSequence || session != uiState.value.inputSessionId ||
                    !rimeEngine.isCandidateRevisionCurrent(revision)) return@withContext
                glideRimePreview.value = shown
                if (TerminalEditor.inputTextLocation(this@XimeInputMethodService) == SettingsPreferences.INPUT_TEXT_INPUT_BOX)
                    glideInputPreview.showComplete(shown.preeditText, session)
                else glideInputPreview.end()
            }
        }
    }
    internal fun endGlidePreview() {
        glidePreviewSequence++
        glidePreviewGesture = -1
        glideRimePreview.value = null
        glideEnglishPreview.value = null
        glidePreviewRouter.end()
    }

    internal fun holdGlidePreview(): Long? {
        val token = ++glidePreviewSequence
        return token.takeIf { glideRimePreview.value != null }
    }

    internal fun isGlidePreviewHeld(token: Long): Boolean = token == glidePreviewSequence

    internal fun finishGlidePreview(token: Long, keepComposition: Boolean) {
        if (token != glidePreviewSequence) return
        if (keepComposition) glideInputPreview.release() else glideInputPreview.end()
        glideRimePreview.value = null
        glidePreviewGesture = -1
        glidePreviewSequence++
    }

    private var lockCompositionSuspended = false

    private fun refreshLockResumedCandidates() {
        val session = uiState.value.inputSessionId
        val schema = uiState.value.currentSchemaId
        val input = candidateState.value.inputText
        keyRouter.postRimeJob("lock_resume_candidates") {
            val result = rimeEngine.readQueuedResult()
            val matches = result != null && result.inputText == input && !result.isAsciiMode
            if (!matches || result == null) return@postRimeJob
            val transformed = candidateTransform.transformFor(result)
            withContext(Dispatchers.Main) {
                if (uiState.value.inputSessionId != session || uiState.value.currentSchemaId != schema ||
                    !rimeEngine.isCandidateRevisionCurrent(result.engineRevision)) return@withContext
                sessionController.updateUIWithResult(
                    transformed?.let { result.copy(candidates = it.candidates.toTypedArray()) } ?: result,
                    transformed?.actions ?: emptyList(), updateInputBoxComposition = false)
            }
        }
    }

    private fun suspendCompositionForScreenOff() {
        if (lockCompositionSuspended) return
        val state = uiState.value
        if (!(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive &&
            state.inputProfile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN &&
            !state.isAsciiMode && !state.inputTarget.isInternal && !isSecretEditor() && !isTerminalEditor() &&
            !state.isVoiceMode && !voiceRecordingStarted && candidateState.value.isComposing &&
            inlineComposition.active && !inlineComposition.text.isNullOrEmpty() && lastEditorIdentity != null &&
            TerminalEditor.inputTextLocation(this) == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
            lockCompositionSuspended = true
        }
    }
    /** 上一个输入框（包名、fieldId、inputType），判断重启输入是否仍是同一个框。 */
    private var lastEditorIdentity: Triple<String?, Int, Int>? = null
    /**
     * 宿主最近报来的折叠光标和它之前的一个（没有或有选区时为 -1）：听写中宿主重启输入框后，用来把光标放回原处。
     * 宿主重设文字时「光标到开头」那条可能先于重启送到，所以多留一个。
     */
    private var voiceHostCaret = -1
    private var voiceHostCaretBefore = -1

    /** 标记刚向输入框写入了 composing 文本；拼音编码同时记下文字（语音 partial 不记）。 */
    internal fun markInputBoxComposing(text: String? = null) {
        inlineComposition.shown(text)
    }

    /** 输入框里的光标已挪到读音第 [offset] 个字后面（日语光标在读音中间）。 */
    internal fun moveInlineCompositionCaret(offset: Int) = inlineComposition.moveCaret(offset)

    internal fun inlinePinyinText(): String? = inlineComposition.text

    internal fun hasInlinePinyinCaretInside(): Boolean = inlineComposition.text?.let {
        inlineComposition.caret < it.length
    } == true

    internal fun keepInlineEditCaret(text: String): Boolean =
        inlineComposition.text == text && inlineComposition.caret < text.length

    /** 编辑时保留已显示文字，同时清理标志，避免稍后把选区当 composing 删除。 */
    internal fun finishComposingInputBoxKeepingText() {
        currentInputConnection?.finishComposingText()
        inlineComposition.ended()
    }

    /**
     * 框架结束或重建输入连接时会把组合区定格成普通文字。未完成的拼音编码不是用户要的文字：
     * 收起键盘、离开输入框前先撤掉，与编码显示在候选栏时一致（语音临时文本由语音收尾处理）。
     */
    private fun discardInlinePinyinBeforeFrameworkFinish() {
        if (inlineComposition.text != null) endComposingInputBox()
    }

    /** Point the native cursor into our composing text only, never into surrounding prose. */
    internal fun showInlineEditCaret(frame: com.kingzcheung.xime.rime.PinyinEditSession) {
        val code = inlineComposition.text ?: return
        if (uiState.value.inputTarget.isInternal || TerminalEditor.inputTextLocation(this) != SettingsPreferences.INPUT_TEXT_INPUT_BOX) return
        val offset = frame.inlineOffset(code) ?: return
        if (offset == inlineComposition.caret) return
        val ic = currentInputConnection ?: return
        val value = runCatching { ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
            hintMaxChars = code.length * 2 + 2
        }, 0) }.getOrNull() ?: return
        val content = value.text ?: return
        val sel = value.selectionStart
        if (value.startOffset < 0 || sel !in 0..content.length || sel != value.selectionEnd) return
        val start = sel - inlineComposition.caret
        if (start < 0 || start + code.length > content.length ||
            content.subSequence(start, start + code.length).toString() != code) return
        val position = value.startOffset + start + offset
        if (ic.setSelection(position, position)) inlineComposition.moveCaret(offset)
    }

    /** Restore the displayed caret to the engine position after editing inside code. */
    internal fun showInlinePinyinCaret(snapshot: Array<String>, sessionId: Long, schema: String) {
        if (uiState.value.inputSessionId != sessionId || uiState.value.currentSchemaId != schema || snapshot.size < 5) return
        val input = snapshot[0]
        val caret = snapshot[1].toIntOrNull() ?: return
        val confirmed = snapshot[2].toIntOrNull()?.coerceIn(0, input.length) ?: return
        if (caret !in confirmed until input.length) return
        val raw = com.kingzcheung.xime.rime.PinyinEditBuffer.normalized(input.drop(confirmed),
            com.kingzcheung.xime.rime.PinyinEditBuffer.keepsCase(schema))
        val prefix = snapshot[3]
        val groups = if (uiState.value.inputProfile.layout == com.kingzcheung.xime.settings.InputLayout.MERGED14)
            com.kingzcheung.xime.rime.merged14Groups else com.kingzcheung.xime.settings.CustomKeyboardLayouts.forSchema(schema)?.mergedGroups.orEmpty()
        val display = com.kingzcheung.xime.rime.PinyinEditDisplay(raw, snapshot[4].removePrefix(prefix).trim(), groups = groups)
        val frame = com.kingzcheung.xime.rime.PinyinEditSession(sessionId, schema, input,
            input.take(confirmed), prefix, display.text, display.displayOffset(caret - confirmed), false)
        showInlineEditCaret(frame)
    }

    internal fun syncInlinePinyinLocation() {
        if (TerminalEditor.inputTextLocation(this) == SettingsPreferences.INPUT_TEXT_INPUT_BOX)
            sessionController.refreshInputBoxComposition()
        else discardInlinePinyinBeforeFrameworkFinish()
    }

    /**
     * 宿主在拼音组合中重启同一个输入框（文字一变就 setText、改回车键等），框架已把编码定格成普通文字。
     * 原来一律撤掉编码、清空拼音，宿主每打一个字母重启一次时就是「打一两个字母全没了」。
     * 编码原样还在原处时把它重新标成组合区，拼音、候选和引擎都不动，接着打。
     * 换了框、成了密码/网址/数字等框、不在打拼音、找不到原编码时返回 false，照原来的流程重启。
     */
    private fun resumeCompositionAfterRestart(attribute: EditorInfo?, restarting: Boolean, afterLock: Boolean = false): Boolean {
        val code = inlineComposition.text
        val ic = currentInputConnection
        val last = lastEditorIdentity
        val state = uiState.value
        if ((!restarting && !afterLock) || attribute == null || ic == null || last == null || code.isNullOrEmpty() || !inlineComposition.active ||
            attribute.packageName.isNullOrEmpty() || attribute.packageName != last.first || attribute.fieldId != last.second ||
            (afterLock && attribute.inputType != last.third) ||
            editorInputPolicy.field.scoped || EditorFieldPolicy.from(attribute).scoped ||
            EditorInfoClassifier.isSecretEditor(attribute) || EditorInfoClassifier.isTerminalEditor(attribute) ||
            state.inputTarget.isInternal || state.isVoiceMode || voiceRecordingStarted || !candidateState.value.isComposing ||
            TerminalEditor.inputTextLocation(this) != SettingsPreferences.INPUT_TEXT_INPUT_BOX) return false
        val selStart = attribute.initialSelStart
        val selEnd = attribute.initialSelEnd
        val window = code.length + 1
        val before = (if (AndroidPlatform.initialSurroundingText) attribute.getInitialTextBeforeCursor(window, 0) else null)
            ?: runCatching { ic.getTextBeforeCursor(window, 0) }.getOrNull()
        val after = (if (AndroidPlatform.initialSurroundingText) attribute.getInitialTextAfterCursor(window, 0) else null)
            ?: runCatching { ic.getTextAfterCursor(window, 0) }.getOrNull()
        val region = locateResumableComposition(code, selStart, selEnd, before, after, inlineComposition.start) {
            runCatching { ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) }.getOrNull()
                ?.let { it.text to it.startOffset }
        } ?: return false
        // 新连接从未知光标开始记；重新标组合区后光标前文字混着编码，不拿来回答。
        (ic as? TrackedInputConnection)?.memory?.started(-1, -1, enabled = true)
        val adopted = runCatching {
            ic.beginBatchEdit()
            try {
                ic.setComposingRegion(region.start, region.end).also { ok ->
                    // 宿主把光标放进编码中间（早一个字）时放回拼音光标原处，免得被当成在拼音里移了光标。
                    val caret = region.start + inlineComposition.caret
                    if (ok && selStart == selEnd && selStart in region.start..region.end && selStart != caret) ic.setSelection(caret, caret)
                }
            } finally { ic.endBatchEdit() }
        }.getOrDefault(false)
        if (!adopted) return false
        lastEditorIdentity = Triple(attribute.packageName, attribute.fieldId, attribute.inputType)
        beginEditorField(attribute, restarting)
        englishEditorReadCapability.clear()
        uiState.value = uiState.value.copy(cursorAnchor = null, editorBounds = null)
        updateHardwareKeyboardConnection()
        requestHardwareCursorUpdates()
        updateEnterKeyText(attribute)
        // 只记长度与位置，不记文字内容。
        FileLogger.w("InputLifecycle", "inline composition resumed after restart: len=${code.length} " +
            "sel=$selStart/$selEnd at=${region.start}")
        return true
    }

    /**
     * 宿主在拼音组合中重启输入（setText、改 inputType 等）时，框架已把编码定格成普通文字。
     * 仍是同一个输入框、且光标处的文字正好拼回 CyIME 刚写的编码时把它删掉；对不上就不碰任何文字。
     */
    private fun removeDetachedComposition(text: String, attribute: EditorInfo?, restarting: Boolean, sameField: Boolean) {
        val ic = currentInputConnection
        val selStart = attribute?.initialSelStart ?: -1
        val selEnd = attribute?.initialSelEnd ?: -1
        val window = text.length + 1
        fun before(): CharSequence? = (if (AndroidPlatform.initialSurroundingText) attribute?.getInitialTextBeforeCursor(window, 0) else null)
            ?: runCatching { ic?.getTextBeforeCursor(window, 0) }.getOrNull()
        fun after(): CharSequence? = (if (AndroidPlatform.initialSurroundingText) attribute?.getInitialTextAfterCursor(window, 0) else null)
            ?: runCatching { ic?.getTextAfterCursor(window, 0) }.getOrNull()
        val region = if (sameField && ic != null) locateDetachedComposition(text, selStart, selEnd, before(), after()) else null
        val removed = region != null && runCatching {
            ic!!.beginBatchEdit()
            try { ic.setComposingRegion(region.start, region.end) && ic.setComposingText("", 1) } finally { ic.endBatchEdit() }
        }.getOrDefault(false)
        // 只记长度与位置，不记文字内容。
        FileLogger.w("InputLifecycle", "inline composition detached: restarting=$restarting sameField=$sameField " +
            "len=${text.length} sel=$selStart/$selEnd located=${region != null} removed=$removed")
    }

    /**
     * 清理输入框中的 composing 区域（未上屏的拼音编码 / 语音临时文本）。
     *
     * 无论输入位置设置（输入框/候选栏）都执行。
     * 注意：composing 区域不存在时 [InputConnection.setComposingText] 并非空操作——
     * 它会在光标处"插入"空串，光标处有选中文字时等于删除整个选区
     * （收起键盘/焦点切换误删选中文字的根因）。因此仅在标记过 composing 时才清空，
     * 否则只调用 finishComposingText（无 composing 时是无害空操作，仅兜底清理残留 span）。
     */
    internal fun endComposingInputBox() {
        currentInputConnection?.let {
            if (inlineComposition.active) {
                it.setComposingText("", 0)
                it.finishComposingText()
            } else {
                it.finishComposingText()
            }
        }
        inlineComposition.ended()
    }

    /**
     * 当前输入框是否受限（密码/终端/NO_SUGGESTIONS，见 EditorInfoClassifier）：
     * 英文联想等补全类功能应短路。
     */
    internal fun isEditorRestricted(): Boolean = editorRestricted

    internal fun isSecretEditor(): Boolean = editorSecret

    /** 宿主输入框是终端（见 [TerminalEditor]）；键盘自己的输入框（剪贴板搜索等）不算。 */
    internal fun isTerminalEditor(): Boolean = TerminalEditor.active && !uiState.value.inputTarget.isInternal

    /** 终端不接系统粘贴菜单：直接上屏剪贴板文字。不是终端返回 false，由调用方照常交给宿主。 */
    internal fun pasteIntoTerminal(): Boolean {
        if (!isTerminalEditor()) return false
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = runCatching { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this) }
            .getOrNull()?.toString().orEmpty()
        if (text.isNotEmpty()) {
            dismissPredictionsForPaste()
            commitPastedText(text)
            hideClipboardSuggestionsAfterPaste()
        }
        return true
    }

    /**
     * 当前宿主是否支持英文候选的"回删替换"机制。
     *
     * 英文直接上屏模式下，选中候选词需要 deleteSurroundingText 回删已上屏编码再提交候选词；
     * 终端等受限宿主对该能力（含文本探测接口）通常不支持，探针返回 null。
     * 此类宿主直接不提供英文联想候选。
     */
    private val englishEditorReadCapability = EditorReadCapability()

    internal fun supportsEnglishCandidateReplace(): Boolean {
        if (isSecretEditor()) return false
        if (uiState.value.inputTarget.isInternal) return true
        val ic = currentInputConnection ?: return false
        return englishEditorReadCapability.supports(uiState.value.inputSessionId, ic) {
            runCatching { ic.getTextBeforeCursor(1, 0) != null }.getOrDefault(false)
        }
    }

    override fun onDestroy() {
        if (EnterProbe.service?.get() === this) EnterProbe.service = null
        KeyboardPanelLaunch.previewVisible = false
        com.kingzcheung.xime.runtime.adaptation.CpuBenchmarkRunner.keyboardShown = false
        if (com.kingzcheung.xime.ui.keyboard.HandwritingGlassRegion.reporter === handwritingGlassRegionReporter)
            com.kingzcheung.xime.ui.keyboard.HandwritingGlassRegion.reporter = null
        if (com.kingzcheung.xime.ui.keyboard.CursorLineMetrics.requester === cursorLineRequester)
            com.kingzcheung.xime.ui.keyboard.CursorLineMetrics.requester = null
        mainHandler.removeCallbacks(hideGlassBlurWindows)
        glassBlurWindows.values.forEach { it.dispose() }
        glassBlurWindows.clear()
        glassSurfaceBounds.clear()
        glassSurfaceOrigins.clear()
        hardwareInputManager.unregisterInputDeviceListener(hardwareInputListener)
        inputStallWatchdog?.stop()
        com.kingzcheung.xime.association.AssociationManager.flushLearning()
        runtimeObserver?.stop()
        stopVoicePillResume()
        inputReadiness.close()
        serviceScope.cancel()
        super.onDestroy()
        sharedPrefsListener?.let {
            SettingsPreferences.getPrefsPublic(this).unregisterOnSharedPreferenceChangeListener(it)
        }
        RimeEngine.setDeploymentCallback { _, _ -> }
        stopClipboardSync()
        if (::clipboardManager.isInitialized) {
            clipboardManager.release()
        }
        _viewModelStore.clear()
        feedbackManager.release()
        // The Rime engine is process-wide (the Application prepares it too). Keep it resident: the system
        // recreates this service when another keyboard was used in between, and destroying the engine here
        // made the next keyboard open reload every dictionary behind "正在加载词库".
        AssociationManager.release()
        voiceRecognitionHandler.release()
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        ExtensionManager.release()
        com.kingzcheung.xime.association.NativeOnnxEngine.releaseSharedEnv()
        keyProcessingDispatcher.close()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
    
    internal fun hideKeyboard() {
        if (collapseScreenKeyboardToHardwareToolbar()) return
        // 语音听写中：收起成底部悬浮条，语音继续（仿 Gboard），不结束本次听写。
        if (collapseToVoicePill(VoiceSessionEnd.COLLAPSE_BUTTON)) return
        KeyboardPanelLaunch.closeTypingTrial?.invoke()
        clearInputState()
        requestHideSelf(0)
    }
    
    // 键盘被 App 收起时是否继续听写：按 voiceKeepsListeningAfter，现在一律结束（用户 11-02）。
    private var voiceKeptAlive = false
    /** 悬浮条是 App 收起键盘后自己弹出的：听写结束时一起收起。 */
    private var voicePillAuto = false
    /** 自己请求弹出悬浮条：这次显示请求不能当成用户点输入框而展开键盘。 */
    private var voicePillSelfShow = false
    /** 用户已经要求结束（返回键、输入框被清空）：随后的收起不能再续上听写。 */
    private var voiceStopRequested = false
    private var voiceFieldHadText = false

    private fun voiceKeepsAliveOnHide(): Boolean = uiState.value.let {
        it.isVoiceMode && it.voiceSticky && !it.isCompact && !voiceStopRequested && currentInputConnection != null &&
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive &&
            voiceKeepsListeningAfter(VoiceSessionEnd.APP_HIDES_KEYBOARD)
    }

    private fun stopVoiceBeforeLeaving() {
        voiceStopRequested = true
        mainHandler.removeCallbacks(showVoicePillAfterHide)
        endVoiceSession()
    }

    private val showVoicePillAfterHide = Runnable {
        if (isInputViewShown || currentInputConnection == null || !uiState.value.isVoiceMode || voiceStopRequested) return@Runnable
        // 开车连续语音：用户自己收起的悬浮条被 App 暂时收走又弹回来，听写结束后也留着。
        voicePillAuto = !(voicePillByUser && voicePillResumeEnabled())
        voicePillSelfShow = true
        uiState.value = uiState.value.copy(voiceCollapsed = true, handwritingExpanded = false)
        applyWindowBackground()
        requestShowSelf(0)
        // 系统不让弹出时，麦克风不能在没有任何界面的情况下一直开着。
        mainHandler.postDelayed(stopVoiceIfStillHidden, VOICE_PILL_SHOW_TIMEOUT_MS)
    }

    private val stopVoiceIfStillHidden = Runnable {
        if (!isInputViewShown && uiState.value.isVoiceMode) {
            voiceRecognitionHandler.endInputSession()
            restoreAfterVoiceFinish()
        }
    }

    // ---- 开车连续语音（VoicePillResume，设置里默认关） ----
    /** 悬浮条是用户自己点收起变出来的；只有这种悬浮条才续听。 */
    private var voicePillByUser = false
    /** 悬浮条所在的应用：onStartInput 时 currentInputEditorInfo 已是新输入框，记号要记打断前的应用。 */
    private var voicePillPackage: String? = null
    private val voicePillResume by lazy { VoicePillResume(this) }
    /** 这个输入框符合续听：键盘弹出时直接显示悬浮条并开麦。 */
    private var voicePillResumePending = false
    /** 悬浮条听写因发送停下、焦点还在：收尾完接着听。 */
    private var voicePillRestartInPlace = false
    /** 这段听写是自动续上的：悬浮条写明，免得用户不知道麦克风为什么开着。 */
    private val voicePillAutoResumed = androidx.compose.runtime.mutableStateOf(false)

    private fun voicePillResumeEnabled(): Boolean =
        SettingsPreferences.isSttEnabled(this) && SettingsPreferences.isSttPillResume(this)

    private fun voicePillResumeFieldAllowed(): Boolean {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val inCall = audio.mode == android.media.AudioManager.MODE_IN_CALL ||
            audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
        return voicePillResumeFieldAllows(com.kingzcheung.xime.settings.IncognitoState.sensitiveEditor,
            editorInputPolicy.locksNativeInput, inCall,
            com.kingzcheung.xime.util.PermissionHelper.hasRecordAudioPermission(this))
    }

    /** 悬浮条听写正被发送、失焦或换输入框打断：记下应用，回来时续听。用户已要求停止、熄屏都不记。 */
    private fun noteVoicePillInterrupted(end: VoiceSessionEnd) {
        val state = uiState.value
        if (!state.voiceCollapsed || !state.isVoiceMode || !state.voiceSticky || voiceStopRequested) return
        if (!(getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive) { stopVoicePillResume(); return }
        if (voicePillResumeArmsAfter(end, voicePillByUser, voicePillResumeEnabled()))
            voicePillResume.arm(voicePillPackage)
    }

    /** 用户自己停下：不再自动续听。 */
    private fun stopVoicePillResume() {
        voicePillResume.disarm()
        voicePillRestartInPlace = false
        voicePillResumePending = false
        mainHandler.removeCallbacks(resumeVoicePill)
    }

    private fun scheduleVoicePillResume() {
        mainHandler.removeCallbacks(resumeVoicePill)
        mainHandler.postDelayed(resumeVoicePill, VOICE_PILL_RESUME_DELAY_MS)
    }

    /** 真正开麦前再查一遍：窗口在显示、有输入框焦点、悬浮条还在、这个框允许。 */
    private val resumeVoicePill = Runnable {
        val state = uiState.value
        if (!isInputViewShown || currentInputConnection == null || state.isVoiceMode || !state.voiceCollapsed) return@Runnable
        if (!voicePillResumeEnabled() || !voicePillResume.appliesTo(currentInputEditorInfo?.packageName) ||
            !voicePillResumeFieldAllowed()) return@Runnable
        // 用掉这次记号；这段听写再被打断时重新记。
        voicePillResume.disarm()
        keyboardCallbacks?.onVoiceStickyToggle?.invoke()
        if (uiState.value.isVoiceMode) voicePillAutoResumed.value = true
    }

    private fun canCollapseToVoicePill(): Boolean = uiState.value.let {
        it.isVoiceMode && it.voiceSticky && !it.voiceCollapsed && !it.isCompact
    }

    private fun collapseToVoicePill(end: VoiceSessionEnd): Boolean {
        if (!voiceKeepsListeningAfter(end) || !canCollapseToVoicePill()) return false
        keyboardViewModel.closeOverlay()
        closeToolPanel()
        closeClipboardSearch(openBoard = false)
        voicePillByUser = end == VoiceSessionEnd.COLLAPSE_BUTTON
        voicePillPackage = currentInputEditorInfo?.packageName
        // 键盘先淡出下沉，再换成悬浮条淡入上浮（用户 10-20：收起、弹出、消失都要动效）。
        swapKeyboardContent {
            uiState.value = uiState.value.copy(voiceCollapsed = true, handwritingExpanded = false)
            applyWindowBackground()
        }
        return true
    }

    private fun expandFromVoicePill() {
        if (!uiState.value.voiceCollapsed) return
        voicePillAuto = false
        // 展开键盘就是要自己打字：开车连续语音到此为止。
        voicePillByUser = false
        stopVoicePillResume()
        swapKeyboardContent {
            clearHardwareSurfaceBounds()
            uiState.value = uiState.value.copy(voiceCollapsed = false)
            applyWindowBackground()
        }
    }

    /** 语音悬浮条正在屏幕上（由悬浮条自己的组合进出维护），收起窗口前据此决定要不要先播退场。 */
    internal var voicePillOnScreen = false
    private var animatedHidePending = false

    /**
     * 整块输入法内容先淡出并下沉 [CONTENT_SHIFT_DP]，执行 [change]（换成键盘或悬浮条），再淡入上浮。
     * 时长沿用统一动效：退场 180ms、进场 200ms。
     */
    private fun swapKeyboardContent(change: () -> Unit) {
        if (!::keyboardContainer.isInitialized || !isInputViewShown) { change(); return }
        val shift = CONTENT_SHIFT_DP * resources.displayMetrics.density
        keyboardContainer.animate().cancel()
        keyboardContainer.animate().alpha(0f).translationY(shift)
            .setDuration(com.kingzcheung.xime.ui.theme.CyMotion.LONG_EXIT.toLong())
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                change()
                keyboardContainer.animate().alpha(1f).translationY(0f)
                    .setDuration(com.kingzcheung.xime.ui.theme.CyMotion.MEDIUM.toLong())
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }.start()
    }

    /** 悬浮条在屏幕上时，窗口收起（点别处失焦、系统收起）先播退场再真正收起，不再一下子消失。 */
    override fun hideWindow() {
        if (!voicePillOnScreen || animatedHidePending || !::keyboardContainer.isInitialized || !isInputViewShown) {
            super.hideWindow(); return
        }
        animatedHidePending = true
        keyboardContainer.animate().cancel()
        keyboardContainer.animate().alpha(0f).translationY(CONTENT_SHIFT_DP * resources.displayMetrics.density)
            .setDuration(com.kingzcheung.xime.ui.theme.CyMotion.LONG_EXIT.toLong())
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                animatedHidePending = false
                superHideWindow()
                keyboardContainer.alpha = 1f
                keyboardContainer.translationY = 0f
            }.start()
    }

    private fun superHideWindow() = super.hideWindow()

    override fun showWindow(showInput: Boolean) {
        // 退场动画中又要弹出：取消退场，内容恢复原样。
        if (animatedHidePending && ::keyboardContainer.isInitialized) {
            animatedHidePending = false
            keyboardContainer.animate().cancel()
            keyboardContainer.alpha = 1f
            keyboardContainer.translationY = 0f
        }
        super.showWindow(showInput)
    }

    private var compositionRefreshJob: kotlinx.coroutines.Job? = null

    /** Refresh requests never run JNI or plugin transforms on Main, nor fabricate an empty state on contention. */
    internal fun updateUI() {
        val admission = inputReadiness.ticket() ?: return
        serviceScope.launch(Dispatchers.Main.immediate) {
            if (!inputReadiness.accepts(admission)) return@launch
            compositionRefreshJob?.cancel()
            val session = uiState.value.inputSessionId
            compositionRefreshJob = launch(keyProcessingDispatcher) {
                val composition = rimeEngine.readQueuedComposition() ?: return@launch
                val transformed = if (composition.input.isNotEmpty()) candidateTransform.transform(
                    inputText = composition.input, preedit = composition.preedit,
                    engineCandidates = composition.candidates.toList(), asciiMode = composition.isAsciiMode,
                    firstPage = !composition.hasPrevPage,
                ) else null
                withContext(Dispatchers.Main) {
                    if (!inputReadiness.accepts(admission) || uiState.value.inputSessionId != session ||
                        !rimeEngine.isCandidateRevisionCurrent(composition.engineRevision)) return@withContext
                    if (transformed != null) sessionController.applyComposition(
                        composition.copy(candidates = transformed.candidates.toTypedArray()), transformed.actions,
                    ) else sessionController.applyComposition(composition)
                }
            }
        }
    }

    /**
     * 用户开始输入时清除候选栏中的 inline suggestions，让位于正常输入候选。
     */
    internal fun dismissInlineSuggestions() {
        inlineSuggestionManager?.clear()
    }


    

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!AndroidPlatform.inlineSuggestions) return null
        if (inlineSuggestionManager == null) return null
        updateInlineSuggestionTheme()
        val result = inlineSuggestionManager.onCreateInlineSuggestionsRequest(uiExtras)
        return result
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!AndroidPlatform.inlineSuggestions) return false
        return inlineSuggestionManager?.onInlineSuggestionsResponse(response) ?: false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun updateInlineSuggestionTheme() {
        val state = uiState.value
        val isDark = when (state.darkMode) {
            1 -> true
            2 -> (resources.configuration.uiMode.and(
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            )) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
        val t = com.kingzcheung.xime.ui.theme.KeyboardThemes
        inlineSuggestionManager?.apply {
            val c = t.getCandidateTextColor(state.themeId, isDark)
            candidateTextColorArgb = (c.alpha * 255).toInt() shl 24 or
                (c.red * 255).toInt() shl 16 or
                (c.green * 255).toInt() shl 8 or
                (c.blue * 255).toInt()
            val label = c.copy(alpha = 0.6f)
            labelTextColorArgb = (label.alpha * 255).toInt() shl 24 or
                (label.red * 255).toInt() shl 16 or
                (label.green * 255).toInt() shl 8 or
                (label.blue * 255).toInt()
            isDarkTheme = isDark
        }
    }

    private var handwritingNormalInsetDp = 0
    private var handwritingOverlayActive = false

    override fun onComputeInsets(outInsets: Insets) {
        val state = uiState.value
        if ((state.handwritingExpanded || handwritingOverlayActive) &&
            (keyboardViewModel.page.value.underlyingPage() as? KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING) {
            val windowHeight = window.window?.decorView?.height ?: resources.displayMetrics.heightPixels
            val reserve = if (state.isFloatingMode) 0 else (handwritingNormalInsetDp * resources.displayMetrics.density).roundToInt()
            outInsets.contentTopInsets = (windowHeight - reserve).coerceAtLeast(0)
            outInsets.visibleTopInsets = outInsets.contentTopInsets
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
            return
        }
        if (state.showKeyboardResize) {
            // 调节层是全屏交互画布，但不能把宿主 App 顶到只剩一条缝。
            // 整个 IME 窗口可触摸；内容 inset 仍报告为“悬浮”，底层页面保持原位。
            outInsets.contentTopInsets = resources.displayMetrics.heightPixels
            outInsets.visibleTopInsets = resources.displayMetrics.heightPixels
            if (settingsPreviewEditor) {
                // 设置页的返回、收起和说明区域属于 Activity，键盘只接收自己边框及把手上的操作。
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                val bounds = settingsResizeBounds
                if (bounds != null) outInsets.touchableRegion.set(bounds) else outInsets.touchableRegion.setEmpty()
            } else outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
            return
        }
        if (state.isCompact || state.voiceCollapsed) {
            val height = window.window?.decorView?.height ?: resources.displayMetrics.heightPixels
            // A toolbar resting on the bottom edge (Samsung bar or the Gboard pill) is reported as
            // occupied space, so apps lift their input field above it rather than under it.
            val bottomReserve = hardwareToolbarBounds.value?.takeIf {
                (state.voiceCollapsed && hardwareToolbarPosition.value.yFraction >= 1f) ||
                    height - it.bottom <= (24 * resources.displayMetrics.density).roundToInt()
            }?.top ?: height
            outInsets.contentTopInsets = bottomReserve.coerceIn(0, height)
            outInsets.visibleTopInsets = bottomReserve.coerceIn(0, height)
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
            // Union visible surfaces, never their bounding rectangle: the transparent
            // area between the toolbar and caret must keep passing touches to the app.
            hardwareToolbarBounds.value?.let { outInsets.touchableRegion.op(it, android.graphics.Region.Op.UNION) }
            hardwarePreeditBounds?.let { outInsets.touchableRegion.op(it, android.graphics.Region.Op.UNION) }
            hardwareCandidateBounds?.let { outInsets.touchableRegion.op(it, android.graphics.Region.Op.UNION) }
            return
        } else if (state.isFloatingMode) {
            outInsets.apply {
                contentTopInsets = resources.displayMetrics.heightPixels
                visibleTopInsets = resources.displayMetrics.heightPixels
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                val decor = window.window?.decorView ?: return
                if (currentEffectiveKeyboardHeight <= 0) {
                    val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
                    val kbH = KeyboardHeightProfiles.floating(
                        this@XimeInputMethodService, isLandscape,
                        ((resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt() -
                            tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)).coerceAtLeast(1),
                    )
                    currentEffectiveKeyboardHeight = kbH + resources.configuration.floatingFooterHeightDp
                }
                val density = resources.displayMetrics.density
                val hostLocation = IntArray(2)
                if (::keyboardContainer.isInitialized) keyboardContainer.getLocationInWindow(hostLocation)
                val hostWidth = if (::keyboardContainer.isInitialized && keyboardContainer.width > 0)
                    keyboardContainer.width else decor.width
                val hostHeight = if (::keyboardContainer.isInitialized && keyboardContainer.height > 0)
                    keyboardContainer.height else decor.height
                // 卡片已测量到就直接用它；只有还没测到时才去算一次兜底尺寸（resolveKeyboardSize 不便宜）。
                val measuredCard = floatingCardBounds
                if (measuredCard != null) {
                    if (com.kingzcheung.xime.ui.keyboard.isFloatingRotated(state.floatingRotation)) {
                        // 卡片按当前角度转过去，用十几条横条近似；只在卡片或角度变了才重算。
                        touchableRegion.set(rotatedTouchRegion(measuredCard, state.floatingRotation))
                    } else touchableRegion.set(measuredCard)
                } else {
                    val minimumBottom = maxOf(
                        tryGetVisibleNavBarHeightDp(this@XimeInputMethodService, window.window) * density,
                        bottomInsetPxState.value.toFloat(),
                    )
                    val fallbackSize = resolveKeyboardSize((hostWidth / density).roundToInt(),
                        (hostHeight / density).roundToInt(), kotlin.math.ceil(minimumBottom / density).toInt(), state)
                    val fallback = com.kingzcheung.xime.ui.keyboard.floatingCardRect(
                        hostWidth.toFloat(), hostHeight.toFloat(), fallbackSize.width * density,
                        (if (currentFloatingCardHeightDp > 0) currentEffectiveKeyboardHeight
                            else fallbackSize.height + resources.configuration.floatingFooterHeightDp) * density,
                        state.floatingOffsetX * density, state.floatingOffsetY * density, minimumBottom,
                    )
                    touchableRegion.set(
                        hostLocation[0] + fallback.left.roundToInt(),
                        hostLocation[1] + fallback.top.roundToInt(),
                        hostLocation[0] + fallback.right.roundToInt(),
                        hostLocation[1] + fallback.bottom.roundToInt(),
                    )
                }
                floatingCardBounds?.takeUnless { com.kingzcheung.xime.ui.keyboard.isFloatingRotated(state.floatingRotation) }
                    ?.let { card -> splitKeyboardGap.hole(card.bottom.toFloat()) }?.let {
                    touchableRegion.op(it.left.roundToInt(), it.top.roundToInt(),
                        it.right.roundToInt(), it.bottom.roundToInt(), android.graphics.Region.Op.DIFFERENCE)
                }
                // 锁定按钮和亮着的四角把手各自收触摸，中间的空白仍交给底下的应用。
                floatingCornerHandleBounds.forEach { touchableRegion.op(it, android.graphics.Region.Op.UNION) }
            }
        } else {
            // 非浮动模式：窗口全屏，容器物理高度 = Compose 内容总高（含底部留白），
            // 容器在窗口内底部对齐（gravity BOTTOM）。
            // contentTopInsets 直接用容器顶部在窗口中的 y 同步计算：
            // 容器高度变化（面板撑高/收起）→ View relayout → traversal → 本方法
            // 自动以新几何重算并上报，无需 hack；窗口全屏时 super 会误判键盘占满
            // 全屏导致布局下沉，故必须显式报告容器顶部。
            if (::keyboardContainer.isInitialized && keyboardContainer.height > 0) {
                val loc = IntArray(2)
                keyboardContainer.getLocationInWindow(loc)
                val targetHeight = keyboardContainer.layoutParams.height
                val topPx = if (targetHeight > 0 && targetHeight != keyboardContainer.height) {
                    ((window.window?.decorView?.height ?: resources.displayMetrics.heightPixels) - targetHeight).coerceAtLeast(0)
                } else loc[1].coerceAtLeast(0)
                val card = fixedCardBounds
                // 键盘比窗口窄、输入框整个在卡片左边或右边（如旁边的小窗）：没盖住它，跟悬浮键盘一样报「不占地方」，
                // 小窗和应用都留在原处。系统只收一条顶线、不收左右范围，报了顶线就会把整屏宽度都当成被盖住。
                val editor = softEditorBoundsOnScreen
                val editorBeside = card != null && editor != null && !hasHardwareKeyboard && run {
                    val decorOnScreen = IntArray(2)
                    window.window?.decorView?.getLocationOnScreen(decorOnScreen)
                    narrowKeyboardLeavesEditorUncovered(card.left, card.right, keyboardContainer.width,
                        SettingsPreferences.isKeyboardBlankFilled(this),
                        editor.left - decorOnScreen[0], editor.right - decorOnScreen[0])
                }
                val reportedTop = if (editorBeside)
                    window.window?.decorView?.height ?: resources.displayMetrics.heightPixels else topPx
                outInsets.contentTopInsets = reportedTop
                outInsets.visibleTopInsets = reportedTop
                // Transparent side gutters must not swallow touches to the host app.
                val splitHole = card?.let { splitKeyboardGap.hole(it.bottom.toFloat()) }
                if (card != null && (card.width() < keyboardContainer.width || splitHole != null)) {
                    // 透明和触摸范围不改变固定键盘的避让高度。宿主底部操作栏也必须
                    // 避让整张卡片（包含工具栏/候选栏），不能仅依赖编辑框的自动平移。
                    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                    // 「填满留空处」时两侧铺了键盘背景，点按不再让给后面的应用。
                    if (SettingsPreferences.isKeyboardBlankFilled(this)) outInsets.touchableRegion.set(
                        loc[0], topPx, loc[0] + keyboardContainer.width, card.bottom)
                    else outInsets.touchableRegion.set(card.left, topPx, card.right, card.bottom)
                    // 分体中缝不拦截触摸，点到的是下面的应用。
                    splitHole?.let {
                        outInsets.touchableRegion.op(it.left.roundToInt(), it.top.roundToInt(),
                            it.right.roundToInt(), it.bottom.roundToInt(), android.graphics.Region.Op.DIFFERENCE)
                    }
                } else {
                    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
                }
            } else {
                super.onComputeInsets(outInsets)
            }
        }
    }

    /** 插件下行事件投递器（input_changed / text_committed，敏感输入豁免）。 */
    internal val pluginEvents = PluginEventDispatcher(this)

    /** 候选词变换协调器（插件 candidate_transform 能力，hotPath：key-processing 线程同步调用）。 */
    internal val candidateTransform = CandidateTransformCoordinator(this)

    override fun commitText(text: String) {
        commitTextAndPredict(text, isPaste = false)
    }

    /**
     * 粘贴性质上屏：保留文本提交事件并带 is_paste 标记，但不触发打字联想。
     * text_committed 事件语义是"文本上屏"
     * （照常投递给所有订阅插件），是否把粘贴计入打字量由插件自行决定
     * （typing-stats 过滤，用户反馈"一天一万多字"的主要来源即长文本粘贴）。
     */
    internal fun commitPastedText(text: String) {
        commitTextAndPredict(text, isPaste = true)
    }

    internal fun dismissPredictionsForPaste() {
        predictionManager.invalidatePendingPredictions()
        predictionManager.clearLetterWordContext()
        candidateState.value = candidateState.value.copy(
            associationCandidates = emptyList(), pendingEnglishText = "",
        )
    }

    /**
     * 用系统剪贴板粘贴过（屏幕 Ctrl+V、编辑面板或手势的粘贴、实体键盘 Ctrl+V）：候选栏的剪贴板胶囊收起，
     * 粘贴的那条记作用过，回到这个输入框也不再出现，和点胶囊上屏一样（用户 11-08：粘贴后上面仍显示复制的内容）。
     * 30 秒内还复制过别的、没粘的那几条照旧留在候选栏。只在主线程调用。
     */
    internal fun hideClipboardSuggestionsAfterPaste() {
        val state = candidateState.value
        if (!state.isShowingRecentClipboard) return
        if (::clipboardManager.isInitialized) {
            val pasted = runCatching { clipboardManager.getCurrentClipboardText() }.getOrNull()
            when {
                !pasted.isNullOrEmpty() -> clipboardManager.markConsumed(pasted)
                // 剪贴板里是图片（胶囊只在图片比文字新时显示它）：粘的就是这张，下次弹出不再预览。
                state.clipboardImage != null -> clipboardManager.images.dismissPreview(state.clipboardImage.uri)
            }
        }
        candidateState.value = state.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            clipboardImage = null,
            clipboardExtractedCount = 0,
            isShowingRecentClipboard = false,
        )
    }

    internal fun submitText(text: String, caret: CommitCaret = CommitCaret.NONE): TextCommitResult {
        InputCommandOwner.requireOwner().requireCurrent(inputReadiness, uiState.value.inputSessionId)
        return commitTextAndPredict(text, false, caret = caret)
    }

    /**
     * 通讯录的人名、号码：按敏感文字上屏（不发 text_committed、不进打字统计、不记联想），
     * 清掉联想上下文，这次和下一次上屏之间不配对学习，上屏后也不出联想。
     */
    internal fun submitPrivateText(text: String, caret: CommitCaret = CommitCaret.NONE): TextCommitResult {
        InputCommandOwner.requireOwner().requireCurrent(inputReadiness, uiState.value.inputSessionId)
        val result = commitTextSilently(text, isSensitive = true, caret = caret)
        if (result.accepted) {
            predictionManager.invalidatePendingPredictions()
            predictionManager.clearCommittedText()
            candidateState.value = candidateState.value.copy(associationCandidates = emptyList())
        }
        return result
    }

    internal fun commitTextAndPredict(text: String, isPaste: Boolean, allowPrediction: Boolean = true,
        caret: CommitCaret = CommitCaret.NONE): TextCommitResult {
        // Invalidate before delivery: a queued prediction or English completion must
        // not reappear after a paste, including rejected/internal-editor pastes.
        if (isPaste) dismissPredictionsForPaste()
        val result = commitTextSilently(text, isPaste, caret = caret)
        if (result != TextCommitResult.ACCEPTED_HOST || isPaste || !allowPrediction) return result
        if (isSecretEditor() || editorInputPolicy.directInput) return result
        if (!canPredictAfter(text)) {
            predictionManager.invalidatePendingPredictions()
            candidateState.value = candidateState.value.copy(associationCandidates = emptyList(), pendingEnglishText = "")
            return result
        }
        if (isChineseMode) {
            val predictionAdmission = inputReadiness.ticket()
            val predictionEditor = uiState.value.inputSessionId
            val predictionRevision = predictionManager.reservePrediction()
            mainHandler.post {
                if (predictionAdmission != null && inputReadiness.accepts(predictionAdmission) &&
                    uiState.value.inputSessionId == predictionEditor && !uiState.value.isAsciiMode &&
                    predictionManager.requestRevision == predictionRevision) {
                    getPredictionFromPlugin(predictionManager.lastCommittedText, endsWithWholeWord(text))
                }
                predictionManager.releasePrediction(predictionRevision)
            }
        }
        return result
    }

    /**
     * 快捷发送表单内退格：按焦点路由到文本框/触发编码框，删除光标前字符或选区。
     * 与原行为对齐：表单显示即处理（未聚焦时删文本框），焦点在编码框时删编码框。
     * 需在主线程调用；返回 false 表示表单未显示（调用方继续常规退格流程）。
     */
    internal fun deleteInQuickSendForm(): Boolean {
        if (!uiState.value.showQuickSendForm) return false
        val et = if (uiState.value.quickSendFormFocused && uiState.value.quickSendCodeFocused)
            QuickSendFormCodeEditTextHolder.editText
        else QuickSendFormEditTextHolder.editText
        et?.let { box ->
            val text = box.text ?: return@let
            // 和应用里一样：开着「输入成对符号」时光标夹在一对空符号中间，两边一起删。
            if (box.selectionStart == box.selectionEnd && uiState.value.inputTarget.editText() === box &&
                SettingsPreferences.isPairedSymbolsEnabled(this) && deleteEmptyPairAroundCursor()) return@let
            val range = internalBackspaceRange(text, box.selectionStart, box.selectionEnd) ?: return@let
            text.delete(range.first, range.last + 1)
            try { box.setSelection(range.first) } catch (_: Exception) {}
        }
        return true
    }

    /**
     * 静默上屏：与 [commitText] 相同的落盘路径（内部编辑器重定向、InputConnection、
     * text_committed 事件、联想上下文/输入统计），但不触发联想推理。
     * 手写叠写自动上屏/替换使用——笔画替换频率高，逐次推理既浪费又会闪烁候选栏。
     * [isPaste] 标记粘贴性质上屏，透传到 text_committed payload（见 commitPastedText）。
     * 需在主线程调用。
     */
    internal fun commitTextSilently(text: String, isPaste: Boolean = false, isSensitive: Boolean = false,
        caret: CommitCaret = CommitCaret.NONE): TextCommitResult {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Editor commits belong to Main" }
        if (InputCommandOwner.current.get() == null) {
            check(com.kingzcheung.xime.rime.RimeCommandContext.validity.get() == null) {
                "Queued editor delivery lost its command owner"
            }
            // Explicit boundary for synchronous Main callbacks (clipboard/handwriting/voice).
            // Queued keyboard commands use submitText(), which requires an existing owner.
            val admission = inputReadiness.ticket() ?: return TextCommitResult.NO_CONNECTION
            val editor = uiState.value.inputSessionId
            val owner = InputCommandOwner(admission, editor) {
                if (!inputReadiness.accepts(admission) || uiState.value.inputSessionId != editor)
                    throw kotlinx.coroutines.CancellationException("Expired direct editor action")
            }
            return owner.runInline { commitTextSilently(text, isPaste, isSensitive, caret) }
        }
        InputCommandOwner.requireOwner().requireCurrent(inputReadiness, uiState.value.inputSessionId)
        // 「我的短语」的换行在短语表里是 U+2028，上屏换回换行；粘贴的原文不动。
        val delivered = if (isPaste) text else com.kingzcheung.xime.settings.PhraseLineBreaks.typed(text)
        val target = uiState.value.inputTarget
        if (target.isInternal) {
            val editor = target.editText() ?: return TextCommitResult.NO_CONNECTION
            val editable = editor.text ?: return TextCommitResult.NO_CONNECTION
            val start = editor.selectionStart.coerceIn(0, editable.length)
            var end = editor.selectionEnd.coerceIn(start, editable.length)
            val overtype = caret.overtype
            if (overtype != null && start == end && editable.startsWith(overtype, end)) end += overtype.length
            editable.replace(start, end, delivered)
            editor.setSelection(start + delivered.length - caret.caretBack.coerceIn(0, delivered.length))
            return TextCommitResult.ACCEPTED_INTERNAL
        }
        val connection = currentInputConnection ?: return TextCommitResult.NO_CONNECTION
        val committed = when {
            caret != CommitCaret.NONE -> connection.commitTextWithCaret(delivered, caret)
            // 多行短语一行一行上屏（见 commitPhraseLines）。
            com.kingzcheung.xime.settings.PhraseLineBreaks.STORED in text && !isPaste -> connection.commitPhraseLines(delivered)
            else -> connection.commitText(delivered, 1)
        }
        if (!committed) return TextCommitResult.REJECTED
        if (inlineComposition.text != null) inlineComposition.ended()
        if (isSensitive || isSecretEditor()) return TextCommitResult.ACCEPTED_HOST
        pluginEvents.onTextCommitted(delivered, isPaste)
        typingStatsSource().let { source ->
            com.kingzcheung.xime.stats.TypingStats.onCommit(delivered, isPaste, source, softKeyboard = !hasHardwareKeyboard,
                combo = typingStatsCombo(source, !hasHardwareKeyboard))
        }
        if (isChineseMode) {
            val before = runCatching { connection.getTextBeforeCursor(25, 0)?.toString() }.getOrNull()
            if (before != null) predictionManager.replaceCommittedText(before)
            else predictionManager.appendCommittedText(delivered)
            if (!isPaste) predictionManager.recordInput(delivered)
        }
        return TextCommitResult.ACCEPTED_HOST
    }

    /** 打字统计按输入方式分类：只看当前状态，不看文字。 */
    private fun typingStatsSource(): com.kingzcheung.xime.stats.TypingStatsData.Source {
        val state = uiState.value
        val profile = state.inputProfile
        return when {
            state.isVoiceMode -> com.kingzcheung.xime.stats.TypingStatsData.Source.VOICE
            profile.mode == com.kingzcheung.xime.settings.InputMode.HANDWRITING ||
                (keyboardViewModel.page.value.underlyingPage() as? KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING ->
                com.kingzcheung.xime.stats.TypingStatsData.Source.HANDWRITING
            state.isAsciiMode || profile.language.typesLettersDirectly -> com.kingzcheung.xime.stats.TypingStatsData.Source.ENGLISH
            profile.layout.kind == com.kingzcheung.xime.settings.LayoutKind.T9 -> com.kingzcheung.xime.stats.TypingStatsData.Source.T9
            profile.scheme == com.kingzcheung.xime.settings.InputScheme.DOUBLE_PINYIN -> com.kingzcheung.xime.stats.TypingStatsData.Source.DOUBLE_PINYIN
            profile.scheme == com.kingzcheung.xime.settings.InputScheme.PINYIN -> com.kingzcheung.xime.stats.TypingStatsData.Source.PINYIN
            else -> com.kingzcheung.xime.stats.TypingStatsData.Source.OTHER
        }
    }

    private var typingStatsComboProfile: com.kingzcheung.xime.settings.InputProfile? = null
    private var typingStatsComboKey = -1
    private var typingStatsComboValue: com.kingzcheung.xime.stats.TypingStatsCombo? = null

    /** 打字统计按「语言 · 方案 · 布局」分开记；同一状态连续上屏时复用上一次的结果，不在打字路径上拼字符串。 */
    private fun typingStatsCombo(source: com.kingzcheung.xime.stats.TypingStatsData.Source,
        softKeyboard: Boolean): com.kingzcheung.xime.stats.TypingStatsCombo {
        val profile = uiState.value.inputProfile
        val key = source.ordinal * 2 + if (softKeyboard) 1 else 0
        typingStatsComboValue?.let { if (profile === typingStatsComboProfile && key == typingStatsComboKey) return it }
        val layout = profile.layout
        val combo = com.kingzcheung.xime.stats.TypingStatsCombo.of(source,
            languageId = profile.language.id, languageName = profile.language.displayName,
            schemeId = profile.scheme.id, schemeName = profile.scheme.displayName,
            layoutId = layout.id, layoutName = layout.displayName,
            // 用户自建的布局名字是用户写的，不进统计；内置字母布局（Colemak 等）照常记名字。
            customLayout = layout.id.startsWith("custom_pinyin_"),
            lettersDirect = profile.language.typesLettersDirectly && profile.scheme == com.kingzcheung.xime.settings.InputScheme.DIRECT,
            hardwareKeyboard = !softKeyboard)
        typingStatsComboProfile = profile
        typingStatsComboKey = key
        typingStatsComboValue = combo
        return combo
    }

    /**
     * 键盘这一下要改的输入框：焦点在键盘自己的输入框（新增固定、剪贴板搜索、工具面板）时是那个框，否则是应用的。
     * 清空、撤回、左右滑删除、Ctrl 键、自定义手势都走这里，不能把操作落到后面应用的输入框
     * （用户 11-03：新增固定里上滑清空没反应，其实清的是后面应用的框）。主线程调用。
     */
    internal fun editorConnection(): InputConnection? {
        val target = uiState.value.inputTarget
        if (!target.isInternal) return currentInputConnection
        return target.editText()?.let(schemaController::internalConnectionFor)
    }

    /**
     * 编码光标在最前面时按删除：删输入框里编码前面的一个字，编码不动。输入框里显示编码时，
     * 组合区起点就是光标，按组合区之前删；编码显示在候选栏时就是删光标前的字。
     */
    internal fun deleteBeforeComposition() {
        if (uiState.value.inputTarget.isInternal) return
        currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
    }

    /** 退格：开着「输入成对符号」且光标夹在一对空符号中间时两边一起删，否则照常发退格键。 */
    internal fun sendDeleteKey() {
        if (SettingsPreferences.isPairedSymbolsEnabled(this) && !isTerminalEditor() && deleteEmptyPairAroundCursor()) return
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    internal fun deleteKaomojiBeforeCursor(picked: String?): String? {
        val state = candidateState.value
        if (inputReadiness.ticket() == null || state.isComposing || state.inputText.isNotEmpty() ||
            state.preeditText.isNotEmpty() || state.pendingEnglishText.isNotEmpty() || t9PartialSegments.isNotEmpty()) return null
        val target = uiState.value.inputTarget
        val limit = KaomojiData.maxFaceLength
        if (target.isInternal) {
            val editor = target.editText() ?: return null
            val text = editor.text ?: return null
            val end = editor.selectionEnd
            if (end !in 1..text.length || end != editor.selectionStart) return null
            val before = text.subSequence((end - limit).coerceAtLeast(0), end)
            val face = KaomojiData.faceBeforeCursor(before, picked) ?: return null
            text.delete(end - face.length, end)
            return face
        }
        val connection = currentInputConnection ?: return null
        // Check the host's live selection and text instead of the prediction cache.
        val host = (connection as? TrackedInputConnection)?.base ?: connection
        val deleted = runCatching {
            val surrounding = host.readSurroundingWindow(limit, 0, 0)
            val before = if (surrounding != null) {
                val end = surrounding.selectionEnd
                if (end != surrounding.selectionStart || end !in 0..surrounding.text.length) return null
                surrounding.text.subSequence(0, end)
            } else {
                if (!host.getSelectedText(0).isNullOrEmpty()) return null
                host.getTextBeforeCursor(limit, 0)
            }
            val face = KaomojiData.faceBeforeCursor(before, picked) ?: return null
            face.takeIf { connection.deleteSurroundingText(it.length, 0) }
        }.getOrNull() ?: return null
        predictionManager.invalidatePendingPredictions()
        candidateState.value = candidateState.value.copy(associationCandidates = emptyList())
        return deleted
    }

    private fun deleteEmptyPairAroundCursor(): Boolean {
        val target = uiState.value.inputTarget
        if (target.isInternal) {
            val editor = target.editText() ?: return false
            val editable = editor.text ?: return false
            val start = editor.selectionStart
            if (start <= 0 || start != editor.selectionEnd || start >= editable.length) return false
            if (!PairedSymbols.isEmptyPairAround(editable.subSequence(start - 1, start),
                    editable.subSequence(start, editable.length))) return false
            editable.delete(start - 1, start + 1)
            return true
        }
        return currentInputConnection?.deleteEmptyPairAroundCursor() == true
    }

    /** 当前编辑目标的绝对光标位置，供手写候选验证文字所属位置。 */
    internal fun currentEditorCursorPosition(): Int? {
        val editor = uiState.value.inputTarget.editText()
        if (editor != null) return editor.selectionEnd.takeIf { it >= 0 }
        val extracted = currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        return extracted?.takeIf { it.startOffset >= 0 && it.selectionEnd >= 0 }
            ?.let { it.startOffset + it.selectionEnd }
    }

    /**
     * 光标前文本与 expected 相同时替换为 replacement，返回是否替换成功。
     * 焦点在输入法内部编辑器时作用于对应 EditText，否则宿主 InputConnection。
     * 不匹配时不做任何操作（调用方决定降级策略）。需在主线程调用。
     */
    internal fun replaceBeforeCursor(expected: String, replacement: String): Boolean {
        val target = uiState.value.inputTarget
        if (target.isInternal) {
            val et = target.editText() ?: return false
            if (et.selectionStart != et.selectionEnd) return false
            val selStart = et.selectionStart.coerceAtLeast(0)
            val start = (selStart - expected.length).coerceAtLeast(0)
            val before = et.text?.substring(start, selStart)
            if (before != expected) return false
            et.text?.replace(start, selStart, replacement)
            try { et.setSelection(start + replacement.length) } catch (_: Exception) {}
            return true
        }
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        val before = runCatching {
            ic.getTextBeforeCursor(expected.length, 0)?.toString()
        }.getOrNull()
        if (before != expected) return false
        var replaced = false
        ic.beginBatchEdit()
        try {
            if (!ic.deleteSurroundingText(expected.length, 0)) return false
            replaced = if ('\n' in replacement) ic.commitPhraseLines(replacement) else ic.commitText(replacement, 1)
        } finally {
            ic.endBatchEdit()
        }
        return replaced
    }

    
    
}
