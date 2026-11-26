package com.kingzcheung.xime.ui.render

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardLayout
import com.kingzcheung.xime.ui.keyboard.LocalKeyboardInputPreferences
import com.kingzcheung.xime.ui.keyboard.rememberKeyboardInputPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 双指快打：左手按住 A 还没抬，右手点的 O 先抬起（用户 11-26 触点日志 xiao 成了 xioa）。
 * 出字要按按下的先后，不按松手的先后。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class KeyRolloverTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val typed = mutableListOf<String>()

    @Before fun showKeyboard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        KeysConfigHelper.loadConfig(app)
        KeyboardThemes.initFromConfig(app)
        AppFonts.initialize(app)
        val vm = KeyboardViewModel(app)
        rule.setContent {
            CompositionLocalProvider(LocalKeyboardInputPreferences provides rememberKeyboardInputPreferences()) {
                KeyboardLayout({ typed += it }, vm, KeyboardCallbacks(onKeyPress = { _, _ -> }, onCandidateSelect = {}),
                    KeyboardUiState(currentSchemaId = "rime_ice", isAsciiMode = true), true,
                    Modifier.requiredSize(350.dp, 228.dp))
            }
        }
    }

    private fun at(tag: String): Offset = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center

    @Test fun earlierPressedLetterEntersFirstWhenTheLaterOneIsReleasedFirst() {
        val a = at("qwerty-key:a"); val o = at("qwerty-key:o")
        rule.onRoot().performTouchInput {
            down(0, a); advanceEventTime(76)
            down(1, o); advanceEventTime(67)
            up(1); advanceEventTime(16)
            up(0)
        }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("a", "o"), typed)
    }

    @Test fun settledLetterIsNotEnteredAgainOnRelease() {
        val x = at("qwerty-key:x"); val i = at("qwerty-key:i"); val a = at("qwerty-key:a")
        rule.onRoot().performTouchInput {
            down(0, x); advanceEventTime(60); up(0); advanceEventTime(60)
            down(1, a); advanceEventTime(45)
            down(2, i); advanceEventTime(53)
            up(2); advanceEventTime(68)
            up(1)
        }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("x", "a", "i"), typed)
    }

    @Test fun letterStillHeldWhenSpaceIsPressedEntersBeforeSpace() {
        val a = at("qwerty-key:a"); val space = at("space-key")
        rule.onRoot().performTouchInput {
            down(0, a); advanceEventTime(80)
            down(1, space); advanceEventTime(40)
            up(1); advanceEventTime(40)
            up(0)
        }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("a", "space"), typed)
    }

    @Test fun sameKeyPressedByTwoFingersEntersTwice() {
        val b = at("qwerty-key:b")
        rule.onRoot().performTouchInput {
            down(0, b); advanceEventTime(30)
            down(1, b); advanceEventTime(30)
            up(1); advanceEventTime(30)
            up(0)
        }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("b", "b"), typed)
    }

    @Test fun letterThatAlreadyMovedWaitsForItsRelease() {
        // 手指挪过了触摸阈值，可能正要滑，不提前结算：照旧松手时才算。
        val a = at("qwerty-key:a"); val o = at("qwerty-key:o")
        rule.onRoot().performTouchInput {
            down(0, a); advanceEventTime(30)
            moveTo(0, a + Offset(viewConfiguration.touchSlop * 2, 0f)); advanceEventTime(30)
            down(1, o); advanceEventTime(40)
            up(1); advanceEventTime(30)
            up(0)
        }
        rule.mainClock.advanceTimeBy(100)
        assertEquals(listOf("o", "a"), typed)
    }
}
