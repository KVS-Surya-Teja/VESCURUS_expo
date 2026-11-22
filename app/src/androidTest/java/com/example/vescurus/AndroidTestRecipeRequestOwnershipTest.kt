package com.example.vescurus

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.model.BUILT_IN_RECIPES
import com.example.vescurus.model.Recipe
import com.example.vescurus.model.RecipeStep
import com.example.vescurus.ui.CookViewModel
import com.example.vescurus.ui.CookingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Deliberately late local planner responses: no network or physical phone input. */
@RunWith(AndroidJUnit4::class)
class AndroidTestRecipeRequestOwnershipTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: kotlin.Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!main(condition) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, main(condition))
    }
    private class LatePlanner {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val recipe = Recipe(
            id = "ai-late-test-recipe", name = "Late recipe from previous request",
            description = "This response must never enter the wrong cooking state.", totalTimeMs = 60_000,
            steps = listOf(RecipeStep(0, 60_000, "Fixture instruction")),
        )
        suspend fun plan(): Recipe = withContext(NonCancellable) {
            started.complete(Unit)
            release.await()
            returned.complete(Unit)
            recipe
        }
    }
    private fun create(planner: LatePlanner, directory: File, store: ViewModelStore): CookViewModel = main {
        val app = instrumentation.targetContext.applicationContext as Application
        CookViewModel(app, directory, recipePlanner = { _, _ -> planner.plan() }).also { store.put("cook", it) }
    }
    private fun directory() = File(instrumentation.targetContext.cacheDir, "planner-owner-${UUID.randomUUID()}")

    @Test fun switchingSessionsCancelsBusyStateAndRejectsLatePlannerReply() {
        val planner = LatePlanner()
        val store = ViewModelStore()
        val directory = directory()
        val vm = create(planner, directory, store)
        try {
            awaitCondition("Session failed to load") { !vm.isLoading }
            val oldSession = main {
                vm.confirmIngredient("Tomato")
                vm.planRecipeForIngredients()
                vm.sessionId
            }
            awaitCondition("Injected planner never started") { planner.started.isCompleted && vm.isFetchingRecipes }
            val newSession = main {
                vm.archiveDraftAndStartNew()
                assertFalse("An abandoned request must not block the next session", vm.isFetchingRecipes)
                assertTrue("New session must start with a clean conversation", vm.messages.isEmpty())
                assertNull(vm.selectedRecipe)
                vm.sessionId
            }
            assertNotEquals(oldSession, newSession)
            planner.release.complete(Unit)
            awaitCondition("Late fake response did not finish") { planner.returned.isCompleted }
            main {
                assertEquals(newSession, vm.sessionId)
                assertNull("Old planner must not select a recipe for the new session", vm.selectedRecipe)
                assertFalse(vm.isFetchingRecipes)
                assertTrue("Old planner must not append a reply (and trigger speech)", vm.messages.isEmpty())
                assertTrue("Confirmed draft ingredient must be retained", vm.sessions.any {
                    it.id == oldSession && it.entries.singleOrNull()?.label == "Tomato"
                })
            }
        } finally {
            planner.release.complete(Unit)
            main { store.clear() }
        }
    }

    @Test fun startingCookingRejectsPendingRecipeReplacementAndAnnouncement() {
        val planner = LatePlanner()
        val store = ViewModelStore()
        val vm = create(planner, directory(), store)
        try {
            awaitCondition("Session failed to load") { !vm.isLoading }
            val original = BUILT_IN_RECIPES.first()
            main {
                vm.confirmIngredient("Tomato")
                vm.selectRecipe(original)
                vm.planRecipeForIngredients()
            }
            awaitCondition("Injected planner never started") { planner.started.isCompleted && vm.isFetchingRecipes }
            main { vm.startCooking() }
            planner.release.complete(Unit)
            awaitCondition("Planner did not finish") { planner.returned.isCompleted && !vm.isFetchingRecipes }
            main {
                assertEquals(CookingState.COOKING, vm.cookingState)
                assertEquals(original.id, vm.selectedRecipe?.id)
                assertFalse("Rejected planned recipe must not be announced", vm.messages.any {
                    it.message.contains(planner.recipe.name) || it.message.contains(planner.recipe.description)
                })
            }
        } finally {
            planner.release.complete(Unit)
            main { store.clear() }
        }
    }
}
