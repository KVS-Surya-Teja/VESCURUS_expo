package com.example.vescurus

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.test.espresso.Espresso
import com.example.vescurus.model.Role
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.ui.*
import com.example.vescurus.ui.theme.VESCURUSTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Compose at compact portrait and landscape constraints; no fabricated camera/scale input. */
class ResponsivePresentationTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("uiReview") != "true") return
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "mkdir -p /sdcard/Download/vescurus-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "screencap -p /sdcard/Download/vescurus-ui/$name.png")).use { it.readBytes() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun startupRolesAndGuideFitBothOrientations() {
        var size by mutableStateOf(DpSize(360.dp, 720.dp))
        var screen by mutableIntStateOf(0)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                VESCURUSTheme {
                    when (screen) {
                        0 -> SplashScreen {}
                        1 -> RoleSelectionScreen {}
                        else -> GuideContent(ConnectionStatus.CONNECTED, "", emptyList())
                    }
                }
            }
        }
        for (viewport in listOf(DpSize(360.dp,720.dp), DpSize(800.dp,360.dp))) {
            compose.runOnIdle { size = viewport; screen = 0 }
            compose.mainClock.advanceTimeBy(2500)
            compose.onNodeWithContentDescription("VESCURUS").assertIsDisplayed()
            capture(if (viewport.width > viewport.height) "startup-landscape" else "startup-portrait")
            compose.runOnIdle { screen = 1 }
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("role-guide").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("role-cook").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { screen = 2 }
            compose.onNodeWithText("Guide camera").assertIsDisplayed()
            compose.onNodeWithText("Cook controls this camera").assertIsDisplayed()
            capture(if (viewport.width > viewport.height) "guide-landscape" else "guide-portrait")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun cameraHudComposerAndIngredientActionsSurviveViewportChange() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        lateinit var vm: CookViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = CookViewModel(app, File(app.cacheDir,"responsive-${System.nanoTime()}"))
        }
        var size by mutableStateOf(DpSize(360.dp,720.dp))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                VESCURUSTheme {
                    CookScreen(ConnectionStatus.IDLE,"",emptyList(),null,vm,{})
                }
            }
        }
        compose.waitUntil(15000) { !vm.isLoading }
        compose.onNodeWithTag("ai-hud-toggle").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("chat-input").performTextInput("Keep it mild")
        Espresso.closeSoftKeyboard()
        for (viewport in listOf(DpSize(800.dp,360.dp), DpSize(360.dp,720.dp))) {
            compose.runOnIdle { size = viewport }
            compose.onNodeWithTag("camera-live").assertIsDisplayed()
            compose.onNodeWithTag("ai-hud-toggle").assertIsDisplayed().assertIsOff()
            compose.onNodeWithTag("chat-input").assertIsDisplayed().assertTextContains("Keep it mild")
            capture(if (viewport.width > viewport.height) "cook-landscape" else "cook-portrait")
            compose.onNodeWithTag("cook-tab-ingredients").performClick()
            compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag("add-ingredient"))
            compose.onNodeWithTag("add-ingredient").assertIsDisplayed()
            compose.onNodeWithTag("cook-tab-assistant").performClick()
        }
    }
    @OptIn(ExperimentalTestApi::class)
    @Test fun trackAndSettingsStayReachableInBothOrientations() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        lateinit var vm: CookViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = CookViewModel(app, File(app.cacheDir,"shell-responsive-${System.nanoTime()}"))
        }
        var size by mutableStateOf(DpSize(360.dp,720.dp))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                VESCURUSTheme { MainAppShell(Role.COOK, {}, vm) }
            }
        }
        compose.waitUntil(15000) { !vm.isLoading }
        for (viewport in listOf(DpSize(800.dp,360.dp), DpSize(360.dp,720.dp))) {
            compose.runOnIdle { size = viewport }
            compose.onNodeWithTag("nav-track").performClick()
            compose.onNodeWithText("Your cooking journal").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("chat-input").assertIsDisplayed()
            compose.onNodeWithTag("nav-settings").performClick()
            compose.onNodeWithTag("switch-role").performScrollTo().assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("chat-input").assertIsDisplayed()
            compose.onNodeWithTag("cook-tab-ingredients").performClick()
            compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag("add-ingredient"))
            compose.onNodeWithTag("add-ingredient").assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("chat-input").assertIsDisplayed()
        }
    }

}
