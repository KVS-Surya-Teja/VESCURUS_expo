package com.example.vescurus

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.model.Role
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.ui.*
import com.example.vescurus.ui.theme.VESCURUSTheme
import com.example.vescurus.data.local.SessionStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Compose UI and Android storage. Quantities are explicitly test/manual data. */
@RunWith(AndroidJUnit4::class)
class ExhibitionFlowTest {
    @get:Rule val compose=createComposeRule()
    private fun captureReview(name: String) {
        if(InstrumentationRegistry.getArguments().getString("uiReview")!="true")return
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        require(name.matches(Regex("[a-z-]+")))
        // Connected tests can uninstall the app before the workflow collects artifacts.
        // The CI-only shell capture survives that cleanup and contains isolated UI fixtures.
        listOf("mkdir -p /sdcard/Download/vescurus-ui", "screencap -p /sdcard/Download/vescurus-ui/$name.png").forEach { command ->
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }
    @Test fun nonEggSessionCompletesAndSurvivesPhoneStorageReload() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val dir=File(app.cacheDir,"ui-test-${System.currentTimeMillis()}")
        lateinit var vm: CookViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm=CookViewModel(app,dir);vm.setDemonstration(true) }
        compose.setContent { VESCURUSTheme { MainAppShell(Role.DEMONSTRATE,{},vm) } }
        compose.waitUntil(15000) { !vm.isLoading && !vm.isSaving }
        compose.runOnIdle { assertEquals(vm.session.id,SessionStore(dir).load(true).active.id) }
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
        compose.onNodeWithTag("chat-send").assertIsNotEnabled()
        fun scroll(tag: String) { compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag(tag)) }
        fun add(name: String,amount: String) {
            scroll("add-ingredient");compose.onNodeWithTag("add-ingredient").performClick()
            compose.onNodeWithTag("ingredient-name").performTextInput(name)
            compose.onNodeWithTag("ingredient-amount").performTextInput(amount)
            compose.onNodeWithTag("save-ingredient").performClick()
        }
        compose.onNodeWithTag("cook-tab-ingredients").performClick()
        add("Tomato","200");add("Onion","100")
        compose.runOnIdle { assertEquals(2,vm.session.entries.size) }
        compose.onNodeWithTag("cook-tab-recipes").performClick()
        scroll("offline-guides");compose.onNodeWithTag("offline-guides").performClick()
        scroll("recipe-local-tomato-onion");compose.onNodeWithTag("recipe-local-tomato-onion").performClick()
        captureReview("recipe-ready")
        scroll("start-cooking");compose.onNodeWithTag("start-cooking").performClick()
        captureReview("cooking-step")
        compose.runOnIdle { vm.setStepTimer(1) }
        compose.waitUntil(6000) { vm.timerExpired }
        compose.runOnIdle { assertEquals(0,vm.currentStep);assertEquals(CookingState.COOKING,vm.cookingState) }
        scroll("pause-cooking");compose.onNodeWithTag("pause-cooking").performClick()
        compose.onNodeWithTag("complete-step").assertIsNotEnabled()
        compose.onNodeWithTag("pause-cooking").performClick()
        repeat(4) { scroll("complete-step");compose.onNodeWithTag("complete-step").performClick() }
        scroll("save-session");compose.onNodeWithTag("save-session").performClick()
        compose.waitUntil(10000) { !vm.isSaving }
        compose.runOnIdle {
            assertEquals(1,vm.sessions.size);assertNotNull(vm.sessions.single().completedAt)
            val reloaded=SessionStore(dir).load(true)
            assertEquals(vm.session.id,reloaded.active.id)
            assertEquals(2,reloaded.history.single().entries.size)
            assertTrue(vm.exportJson().contains("MANUAL"))
        }
        compose.onNodeWithTag("nav-track").performClick()
        compose.onNodeWithText("Tomato & onion sauté",substring=true).assertExists()
        captureReview("history")
    }

    @Test fun cookKeepsCameraAndAssistantReachable() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val dir=File(app.cacheDir,"cook-layout-test-${System.currentTimeMillis()}")
        lateinit var vm: CookViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm=CookViewModel(app,dir) }
        compose.setContent {
            VESCURUSTheme {
                CookScreen(
                    status=ConnectionStatus.IDLE,
                    diagnostics="No peer connected",
                    detections=emptyList(),
                    latestFrame=null,
                    viewModel=vm,
                    onSnapshot={}
                )
            }
        }
        compose.waitUntil(15000) { !vm.isLoading }
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
        val bounds=compose.onRoot().getUnclippedBoundsInRoot()
        val roomForCamera=bounds.bottom-bounds.top>=500.dp
        if(roomForCamera) {
            compose.onNodeWithTag("camera-live").assertIsDisplayed().assertIsSelected()
            captureReview("cook-home")
            compose.onNodeWithTag("camera-analyzed").performClick().assertIsSelected()
            compose.onNodeWithTag("camera-expand").performClick()
            compose.onNodeWithTag("camera-live").assertDoesNotExist()
            compose.onNodeWithTag("chat-input").assertIsDisplayed()
            compose.onNodeWithTag("camera-expand").performClick()
            compose.onNodeWithTag("camera-live").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("camera-live").assertDoesNotExist()
            captureReview("cook-compact")
        }
        compose.onNodeWithTag("cook-tab-ingredients").performClick()
        compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag("add-ingredient"))
        compose.onNodeWithTag("add-ingredient").assertIsDisplayed()
        compose.onNodeWithTag("cook-tab-assistant").performClick()
        compose.onNodeWithTag("chat-input").assertIsDisplayed()
    }
}
