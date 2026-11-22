package com.example.vescurus

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.data.local.LocalState
import com.example.vescurus.data.local.SessionStore
import com.example.vescurus.model.BUILT_IN_RECIPES
import com.example.vescurus.model.RecipeNutrient
import com.example.vescurus.session.CookingSession
import com.example.vescurus.session.SessionChatMessage
import com.example.vescurus.ui.CookViewModel
import com.example.vescurus.ui.TrackScreen
import com.example.vescurus.ui.theme.VESCURUSTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated record fixtures; no network, camera claims or production history mutation. */
class TrackMirroringUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun guideShowsNewestPeerAndPriorLocalRecordsWithReadOnlyConversation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val dir = File(app.cacheDir, "mirror-ui-${System.nanoTime()}")
        val original = CookingSession(id = "local-record", completedAt = 1000, updatedAt = 1000,
            historyTitle = "Original local dish", version = 1)
        val newer = original.copy(version = 2, updatedAt = 2000, historyTitle = "Updated dish")
        val peer = CookingSession(id = "peer-record", completedAt = 3000, updatedAt = 3000,
            historyTitle = "Peer dish", recipe = BUILT_IN_RECIPES.first().copy(source = "Nutrition test fixture",
                nutrients = listOf(RecipeNutrient("Vitamin B12", .003, "µg"), RecipeNutrient("Calcium", 1000.0, "mg"))),
            conversation = listOf(
                SessionChatMessage(sender = "User", message = "Can I make it milder?", createdAt = 1000),
                SessionChatMessage(sender = "Assistant", message = "Use less chilli.", createdAt = 2000)))
        SessionStore(dir).save(LocalState(history = listOf(original)))
        lateinit var vm: CookViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = CookViewModel(app, dir) }
        compose.setContent {
            VESCURUSTheme {
                TrackScreen(vm, allowResume = false, allowRename = false, mirroredSessions = listOf(newer, peer))
            }
        }
        compose.waitUntil(15000) { !vm.isLoading }
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("Updated dish"))
        compose.onAllNodesWithText("Updated dish").assertCountEquals(1)
        compose.onNodeWithText("Original local dish").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Edit dish name").assertCountEquals(0)
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("Vitamins & minerals"))
        compose.onNodeWithText("Vitamins & minerals").performClick()
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("≈ 0.003 µg"))
        compose.onNodeWithText("≈ 0.003 µg").assertIsDisplayed()
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("≈ 1000 mg"))
        compose.onNodeWithText("≈ 1000 mg").assertIsDisplayed()
        compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("history-conversation-peer-record"))
        compose.onNodeWithText("Can I make it milder?").assertDoesNotExist()
        compose.onNodeWithTag("history-conversation-peer-record").performClick()
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("Can I make it milder?"))
        compose.onNodeWithText("Can I make it milder?").assertIsDisplayed()
        compose.onNodeWithTag("history-list").performScrollToNode(hasText("Use less chilli."))
        compose.onNodeWithText("Use less chilli.").assertIsDisplayed()
    }
}
