package com.example.vescurus

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.model.BUILT_IN_RECIPES
import com.example.vescurus.model.Role
import com.example.vescurus.model.SessionContext
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.network.VescurusConnectionManager
import com.example.vescurus.session.CookingSession
import com.example.vescurus.session.SessionChatMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Real loopback TCP and separate private archives; fixtures are not physical-phone or cooking evidence. */
@RunWith(AndroidJUnit4::class)
class HistoryReplicationProtocolTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block: () -> T): T {
        var result: kotlin.Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }
    private class PeerContext(base: Context, val directory: File, private val prefix: String) : ContextWrapper(base) {
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            preferenceNames += isolated
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun clearFixturePreferences() {
            preferenceNames.forEach { baseContext.getSharedPreferences(it, 0).edit().clear().commit() }
        }
    }
    private fun serverReady(): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:8766/health").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.responseCode == 200
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun fixturePhoto(context: PeerContext, color: Int): File {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        try { bitmap.eraseColor(color); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)) }
        finally { bitmap.recycle() }
        return File(context.filesDir, "test-fixture-finished-photo.jpg").apply { writeBytes(bytes.toByteArray()) }
    }
    private fun assertReplicas(
        guide: VescurusConnectionManager, cook: VescurusConnectionManager,
        fixtures: List<CookingSession>, photoBytes: Map<String, ByteArray>,
        guideContext: PeerContext, cookContext: PeerContext,
    ) {
        val guideRecords = guide.mirroredHistory.value
        val cookRecords = cook.mirroredHistory.value
        assertEquals(fixtures.map { it.id }.toSet(), guideRecords.map { it.id }.toSet())
        assertEquals(fixtures.size, guideRecords.size)
        assertEquals(fixtures.map { it.id }.toSet(), cookRecords.map { it.id }.toSet())
        assertEquals(fixtures.size, cookRecords.size)
        fixtures.forEach { original ->
            val left = guideRecords.single { it.id == original.id }
            val right = cookRecords.single { it.id == original.id }
            assertEquals("Full timestamped chat must survive unchanged", original.conversation, left.conversation)
            assertEquals(original.conversation, right.conversation)
            assertEquals(original.copy(finalPhotoPath = null), left.copy(finalPhotoPath = null))
            assertEquals(original.copy(finalPhotoPath = null), right.copy(finalPhotoPath = null))
            val leftPhoto = File(checkNotNull(left.finalPhotoPath))
            val rightPhoto = File(checkNotNull(right.finalPhotoPath))
            assertNotEquals("Peers must not accidentally share a photo file", leftPhoto.canonicalPath, rightPhoto.canonicalPath)
            assertTrue(leftPhoto.canonicalPath.startsWith(guideContext.filesDir.canonicalPath + File.separator))
            assertTrue(rightPhoto.canonicalPath.startsWith(cookContext.filesDir.canonicalPath + File.separator))
            assertArrayEquals(photoBytes.getValue(original.id), leftPhoto.readBytes())
            assertArrayEquals(photoBytes.getValue(original.id), rightPhoto.readBytes())
        }
        assertNull(guide.historySyncError.value)
        assertNull(cook.historySyncError.value)
    }

    @Test fun pairedArchivesMirrorChatsAndPhotosAcrossReconnectAndOfflineRestart() {
        val base = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "history-protocol-$token").apply { mkdirs() }
        val guideContext = PeerContext(base, File(root, "guide"), "history-guide-$token")
        val cookContext = PeerContext(base, File(root, "cook"), "history-cook-$token")
        val guidePhoto = fixturePhoto(guideContext, Color.MAGENTA)
        val cookPhoto = fixturePhoto(cookContext, Color.CYAN)
        val now = 1_800_000_000_000L
        val guideRecord = CookingSession(
            id = "guide-fixture-$token", version = 7, createdAt = now, updatedAt = now + 1_000,
            completedAt = now + 1_000, recipe = BUILT_IN_RECIPES.first(), state = "DONE", historyTitle = "Guide archive fixture",
            finalPhotoPath = guidePhoto.absolutePath, finalPhotoCapturedAt = now + 900,
            conversation = listOf(
                SessionChatMessage("guide-question", "User", "Fixture question: may I change the method?\nPlease keep my correction.", now + 100),
                // More than one protocol chunk: full chat must never become a summary or truncated preview.
                SessionChatMessage("guide-answer", "AI suggestion", "Fixture response — " + "preserve this exact conversation. ".repeat(2_500), now + 200),
            ),
        )
        val cookRecord = CookingSession(
            id = "cook-fixture-$token", version = 4, createdAt = now + 2_000, updatedAt = now + 3_000,
            completedAt = now + 3_000, recipe = BUILT_IN_RECIPES[1], state = "DONE", historyTitle = "Cook archive fixture",
            finalPhotoPath = cookPhoto.absolutePath, finalPhotoCapturedAt = now + 2_900,
            conversation = listOf(
                SessionChatMessage("cook-question", "User", "Fixture: keep the apple quantity unknown, not zero.", now + 2_100),
                SessionChatMessage("cook-answer", "AI suggestion", "Fixture acknowledgment: quantity remains unknown. café / తెలుగు", now + 2_200),
            ),
        )
        val fixtures = listOf(guideRecord, cookRecord)
        val photoBytes = mapOf(guideRecord.id to guidePhoto.readBytes(), cookRecord.id to cookPhoto.readBytes())
        val session = SessionContext("live-fixture-$token", 1, "Protocol fixture; no cooking claim")
        var store = ViewModelStore()
        var guide = VescurusConnectionManager()
        var cook = VescurusConnectionManager()
        fun ownManagers() = main { store.put("guide", guide); store.put("cook", cook) }
        fun converged(): Boolean = guide.mirroredHistory.value.size == 2 && cook.mirroredHistory.value.size == 2 &&
            guide.historySyncPending.value == 0 && cook.historySyncPending.value == 0
        fun awaitConnected() = await("Loopback pairing and archive acknowledgments did not converge") {
            cook.status.value == ConnectionStatus.CONNECTED && guide.sessionContext.value == session && converged()
        }
        try {
            ownManagers()
            main {
                guide.start(guideContext, Role.GUIDE)
                guide.updateLocalHistory(listOf(guideRecord))
                cook.start(cookContext, Role.COOK)
                cook.updateLocalHistory(listOf(cookRecord))
            }
            await("Guide server or independent archive stores failed to initialize") {
                guide.pairCode.value.length == 6 && guide.historyLoaded.value && cook.historyLoaded.value && serverReady()
            }
            main {
                cookContext.getSharedPreferences("vescurus", 0).edit().putString("guidePairCode", guide.pairCode.value).commit()
                cook.updateSession(session)
                cook.connectDirectly("127.0.0.1", 8766)
            }
            awaitConnected()
            assertReplicas(guide, cook, fixtures, photoBytes, guideContext, cookContext)

            main { cook.stop() }
            await("Guide did not observe disconnection") { guide.status.value != ConnectionStatus.CONNECTED }
            main {
                cook.start(cookContext, Role.COOK)
                cook.updateSession(session)
                cook.connectDirectly("127.0.0.1", 8766)
            }
            awaitConnected()
            assertReplicas(guide, cook, fixtures, photoBytes, guideContext, cookContext)

            main { cook.stop(); guide.stop(); store.clear() }
            store = ViewModelStore()
            guide = VescurusConnectionManager()
            cook = VescurusConnectionManager()
            ownManagers()
            // Both new managers load disk archives while no Guide socket server is running.
            main { guide.start(guideContext, Role.COOK); cook.start(cookContext, Role.COOK) }
            await("Offline restart lost either private archive") {
                guide.historyLoaded.value && cook.historyLoaded.value &&
                    guide.mirroredHistory.value.size == 2 && cook.mirroredHistory.value.size == 2
            }
            assertNotEquals(ConnectionStatus.CONNECTED, guide.status.value)
            assertNotEquals(ConnectionStatus.CONNECTED, cook.status.value)
            assertReplicas(guide, cook, fixtures, photoBytes, guideContext, cookContext)
            main { guide.start(guideContext, Role.GUIDE) }
            await("Restarted Guide server failed to listen") { serverReady() && guide.pairCode.value.length == 6 }
            main { cook.updateSession(session); cook.connectDirectly("127.0.0.1", 8766) }
            awaitConnected()
            assertReplicas(guide, cook, fixtures, photoBytes, guideContext, cookContext)
            assertEquals(0, guide.historySyncPending.value)
            assertEquals(0, cook.historySyncPending.value)
        } finally {
            main { cook.stop(); guide.stop(); store.clear() }
            guideContext.clearFixturePreferences()
            cookContext.clearFixturePreferences()
        }
    }
}
