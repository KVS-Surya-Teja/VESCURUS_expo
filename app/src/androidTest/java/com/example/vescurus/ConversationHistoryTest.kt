package com.example.vescurus

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.data.local.SessionStore
import com.example.vescurus.ui.CookViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Real Android persistence with deterministic local responders, never fake exhibition history. */
class ConversationHistoryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result=runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(message: String, block: () -> Boolean) {
        val deadline=SystemClock.elapsedRealtime()+8000
        while(!main(block) && SystemClock.elapsedRealtime()<deadline) Thread.sleep(30)
        assertTrue(message,main(block))
    }
    @Test fun failedReplyRetriesWithoutDuplicatePromptAndConversationSurvivesReloadAndDraftResume() {
        val app=instrumentation.targetContext.applicationContext as Application
        val directory=File(app.cacheDir,"conversation-${System.nanoTime()}")
        val owner=ViewModelStore()
        var calls=0
        val vm=main { CookViewModel(app,directory,assistantResponder={
            calls++
            if(calls==1)throw IOException("deterministic local test failure")
            "Use a gentle heat and keep it mild."
        }).also { owner.put("cook",it) } }
        try {
            await("Initial load") { !vm.isLoading }
            main { vm.sendMessage("Keep it mild and ready in 20 minutes") }
            await("Failure exposes a retry") { !vm.isGenerating && vm.failedChatQuestion!=null }
            main { vm.retryAssistantReply() }
            await("Retry finished and saved") { calls==2 && !vm.isGenerating && !vm.isSaving }
            val original=main {
                assertNull(vm.failedChatQuestion)
                assertEquals(1,vm.messages.count { it.sender=="User" })
                assertEquals(1,vm.session.planningNotes.size)
                assertEquals(3,vm.session.conversation.size)
                assertTrue(vm.session.conversation.all { it.createdAt>0 })
                vm.session
            }
            assertEquals(original,SessionStore(directory).load().active)
            main { owner.clear() }
            val reloaded=main { CookViewModel(app,directory,assistantResponder={"Unused"}).also { owner.put("cook",it) } }
            await("Reloaded complete transcript") { !reloaded.isLoading && !reloaded.isSaving }
            main {
                assertEquals(original.conversation.map { it.id },reloaded.messages.map { it.id })
                assertEquals(original.conversation.map { it.message },reloaded.messages.map { it.message })
                reloaded.archiveDraftAndStartNew()
                assertTrue(reloaded.messages.isEmpty())
                assertEquals(original.conversation,reloaded.sessions.single { it.id==original.id }.conversation)
                reloaded.resumeDraft(original.id)
                assertEquals(original.conversation.map { it.message },reloaded.messages.map { it.message })
            }
        } finally { main { owner.clear() } }
    }
    @Test fun lateReplyCannotEnterAnotherSessionsSavedConversation() {
        val app=instrumentation.targetContext.applicationContext as Application
        val directory=File(app.cacheDir,"conversation-late-${System.nanoTime()}")
        val owner=ViewModelStore()
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val returned=CompletableDeferred<Unit>()
        val vm=main { CookViewModel(app,directory,assistantResponder={
            withContext(NonCancellable) { started.complete(Unit);release.await();returned.complete(Unit);"Old reply" }
        }).also { owner.put("cook",it) } }
        try {
            await("Initial load") { !vm.isLoading }
            main { vm.sendMessage("Old session question") }
            await("Request started") { started.isCompleted }
            val old=main { val id=vm.sessionId;vm.archiveDraftAndStartNew();id }
            release.complete(Unit)
            await("Late request returned") { returned.isCompleted }
            main {
                assertTrue(vm.messages.isEmpty());assertTrue(vm.session.conversation.isEmpty())
                assertNull(vm.failedChatQuestion)
                assertEquals(listOf("Old session question"),vm.sessions.single { it.id==old }.conversation.map { it.message })
            }
        } finally { release.complete(Unit);main { owner.clear() } }
    }
}
