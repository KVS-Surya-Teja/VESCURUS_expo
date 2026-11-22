package com.example.vescurus

import android.app.Application
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.model.Role
import com.example.vescurus.session.EntryStatus
import com.example.vescurus.session.EvidenceSource
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
    @Test fun selectedRecipeStillAllowsCameraIngredientReview() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val dir = File(app.cacheDir, "recognition-ui-${System.currentTimeMillis()}")
        lateinit var vm: CookViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = CookViewModel(app, dir) }
        compose.waitUntil(15000) { !vm.isLoading }
        compose.runOnIdle { vm.selectRecipe(com.example.vescurus.model.BUILT_IN_RECIPES.first()) }
        val detection = com.example.vescurus.model.DetectionResult(
            label = "Carrot", confidence = .93f,
            box_2d = com.example.vescurus.model.BoundingBox(.1f, .1f, .8f, .8f), preparation = "diced"
        )
        val observation = com.example.vescurus.model.Observation(
            sessionId = vm.session.id, streamId = "test-stream", frameId = 1,
            captureUptimeMs = 1, width = 100, height = 100, engine = "test-fixture",
            status = "SUCCESS", inferenceMs = 1, sourceAgeMs = 0, jpegBase64 = "",
            detections = listOf(detection)
        )
        compose.setContent {
            VESCURUSTheme {
                CookScreen(ConnectionStatus.CONNECTED, "", listOf(detection), null, vm, {}, observation = observation)
            }
        }
        compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag("start-cooking"))
        compose.onAllNodesWithText("Tomato & onion sauté").assertCountEquals(1)
        compose.onNodeWithTag("cook-scroll").performScrollToNode(hasText("Carrot · 93% · Review"))
        compose.onNodeWithText("Carrot · 93% · Review").performClick()
        compose.onNodeWithTag("ingredient-name").assertTextContains("Carrot")
        compose.onNodeWithTag("ingredient-preparation").assertTextContains("diced")
        compose.onNodeWithTag("save-ingredient").performClick()
        compose.onNodeWithTag("ingredient-amount").assertIsDisplayed()
        compose.onNodeWithTag("save-ingredient").performClick()
        compose.runOnIdle {
            assertEquals("Carrot", vm.session.entries.single().label)
            assertNull(vm.session.entries.single().quantity)
            assertEquals("diced", vm.session.entries.single().ingredientPreparation)
            assertEquals("local-tomato-onion", vm.session.recipe?.id)
        }
        compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag("next-ingredient"))
        compose.onNodeWithTag("next-ingredient").assertIsDisplayed()
        compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag("find-recipes"))
        compose.onNodeWithTag("find-recipes").assertIsDisplayed()
    }
    private fun reviewShell(command: String) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun pixelLauncherAnrVisible(): Boolean {
        fun containsTitle(node: AccessibilityNodeInfo): Boolean {
            val text=node.text?.toString()?.replace('\u2019','\'')?.replace(Regex("\\s+")," ")
            if(text=="Pixel Launcher isn't responding")return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsTitle)==true }
        }
        return InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let(::containsTitle)==true
    }
    private fun captureReview(name: String) {
        if(InstrumentationRegistry.getArguments().getString("uiReview")!="true")return
        compose.waitForIdle()
        require(name.matches(Regex("[a-z-]+")))
        reviewShell("mkdir -p /sdcard/Download/vescurus-ui")
        if(InstrumentationRegistry.getArguments().getString("clearPixelLauncherAnr")=="true" && pixelLauncherAnrVisible()) {
            // Preserve the obstruction as evidence; never dismiss an app ANR or other dialog.
            reviewShell("screencap -p /sdcard/Download/vescurus-ui/$name-launcher-anr.png")
            Log.w("VescurusUiReview","Known Pixel Launcher ANR obstructed $name; stopping only com.google.android.apps.nexuslauncher")
            reviewShell("am force-stop com.google.android.apps.nexuslauncher")
            compose.waitUntil(5000) { !pixelLauncherAnrVisible() }
            compose.waitForIdle()
        }
        // Connected tests can uninstall the app before the workflow collects artifacts.
        // The CI-only shell capture survives that cleanup and contains isolated UI fixtures.
        reviewShell("screencap -p /sdcard/Download/vescurus-ui/$name.png")
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
        compose.onNodeWithTag("chat-dictate").assertIsDisplayed()
        fun scroll(tag: String) { compose.onNodeWithTag("cook-scroll").performScrollToNode(hasTestTag(tag)) }
        fun add(name: String,amount: String) {
            scroll("add-ingredient");compose.onNodeWithTag("add-ingredient").performClick()
            compose.onNodeWithTag("ingredient-name").performTextInput(name)
            if(amount.isNotEmpty())compose.onNodeWithTag("ingredient-amount").performTextInput(amount)
            compose.onNodeWithTag("save-ingredient").performClick()
        }
        compose.onNodeWithTag("cook-tab-ingredients").performClick()
        add("Tomato","200");add("Onion","100")
        compose.runOnIdle { assertEquals(2,vm.session.entries.size) }
        // Unknown amounts stay unknown. Correction/removal are secondary menu actions,
        // while recording an ingredient must not imply transfer into the dish.
        add("Carrot","")
        lateinit var correctionId: String
        compose.runOnIdle {
            val entry=vm.session.entries.last();correctionId=entry.id
            assertNull(entry.quantity);assertEquals(EntryStatus.IDENTIFIED,entry.status)
        }
        scroll("ingredient-actions-$correctionId")
        compose.onNodeWithTag("correct-$correctionId").assertDoesNotExist()
        compose.onNodeWithTag("ingredient-actions-$correctionId").performClick()
        compose.onNodeWithTag("correct-$correctionId").performClick()
        compose.onNodeWithTag("ingredient-amount").performTextInput("50")
        compose.onNodeWithTag("save-ingredient").performClick()
        compose.runOnIdle {
            val entry=vm.session.entries.first { it.id==correctionId }
            assertEquals(50.0,entry.quantity!!.value,0.0)
            assertEquals(EvidenceSource.MANUAL,entry.quantitySource)
            assertNotEquals(EntryStatus.TRANSFER_CONFIRMED,entry.status)
        }
        scroll("ingredient-actions-$correctionId")
        compose.onNodeWithTag("ingredient-actions-$correctionId").performClick()
        compose.onNodeWithTag("undo-$correctionId").performClick()
        compose.runOnIdle {
            assertEquals(EntryStatus.UNDONE,vm.session.entries.first { it.id==correctionId }.status)
            assertEquals(2,vm.session.entries.count { it.status!=EntryStatus.UNDONE })
        }
        compose.onNodeWithTag("cook-tab-recipes").performClick()
        scroll("offline-guides");compose.onNodeWithTag("offline-guides").performClick()
        scroll("recipe-local-tomato-onion");compose.onNodeWithTag("recipe-local-tomato-onion").performClick()
        compose.onNodeWithTag("find-recipes").assertDoesNotExist()
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
            assertEquals(2,reloaded.history.single().entries.count { it.status!=EntryStatus.UNDONE })
            assertEquals(EntryStatus.UNDONE,reloaded.history.single().entries.first { it.id==correctionId }.status)
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
                // Match the shell's safe content area without starting its peer connection.
                Box(Modifier.fillMaxSize().background(Color(0xFF020617)).windowInsetsPadding(WindowInsets.safeDrawing)) {
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
