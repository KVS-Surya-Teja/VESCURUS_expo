package com.example.vescurus

import com.example.vescurus.history.*
import com.example.vescurus.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** Archival/transport fixtures only: no camera, cooking, model or physical-peer claim. */
class HistoryReplicaStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun store()=HistoryReplicaStore(temporary.newFolder())
    private fun send(from: HistoryReplicaStore,to: HistoryReplicaStore) {
        from.transfers().forEach { (_,files) -> files.forEach { transfer -> from.chunks(transfer).forEach { to.accept(it) } } }
    }
    @Test fun bidirectionalUnionPreservesEverySessionAndDoesNotDeleteAbsentRecords() {
        val first=store();val second=store()
        val many=(1..120).map { CookingSession(id="session-$it",historyTitle="Saved $it",completedAt=it.toLong()) }
        first.addLocal(many);second.addLocal(listOf(CookingSession(id="other-phone",completedAt=200)))
        send(first,second);send(second,first)
        first.addLocal(emptyList());second.addLocal(emptyList())
        assertEquals(121,first.sessions().size);assertEquals(first.sessions().map { it.id }.toSet(),second.sessions().map { it.id }.toSet())
    }
    @Test fun longVerbatimConversationStreamsInBoundedMessagesAndSurvivesRestart() {
        val first=store();val destination=temporary.newFolder();val second=HistoryReplicaStore(destination)
        val text="Real fixture text. ".repeat(180_000)
        val session=CookingSession(id="long-session",conversation=listOf(SessionChatMessage(id="prompt",sender="user",message=text,createdAt=123)))
        first.addLocal(listOf(session))
        val chunks=first.chunks(first.transfers().single().second.single()).toList()
        assertTrue(chunks.size>40);assertTrue(chunks.all { it.dataBase64.length<100_000 })
        chunks.forEach(second::accept)
        assertEquals(text,HistoryReplicaStore(destination).sessions().single().conversation.single().message)
    }
    @Test fun photoUsesVerifiedLocalPathAndAcknowledgesOnlyWhenWholePhotoIsDurable() {
        val first=store();val destination=temporary.newFolder();val second=HistoryReplicaStore(destination)
        val image=temporary.newFile("fixture-photo.bin").apply { writeBytes(ByteArray(150_000) { (it%251).toByte() }) }
        first.addLocal(listOf(CookingSession(id="photo-session",completedAt=1,finalPhotoPath=image.absolutePath,finalPhotoCapturedAt=2)))
        val (digest,files)=first.transfers().single()
        files.filter { it.kind=="record" }.forEach { transfer -> first.chunks(transfer).forEach { assertTrue(second.accept(it).isEmpty()) } }
        assertNull(second.sessions().single().finalPhotoPath)
        val photoChunks=first.chunks(files.single { it.kind=="photo" }).toList()
        photoChunks.dropLast(1).forEach { assertTrue(second.accept(it).isEmpty()) }
        assertEquals(listOf(digest),second.accept(photoChunks.last()))
        val local=File(second.sessions().single().finalPhotoPath!!)
        assertTrue(local.canonicalPath.startsWith(destination.canonicalPath+File.separator))
        assertNotEquals(image.absolutePath,local.absolutePath);assertArrayEquals(image.readBytes(),local.readBytes())
        assertEquals(listOf(digest),second.accept(photoChunks.last()))
    }
    @Test fun interruptedTransferResumesAfterProcessRestartAndRejectsChangedBytes() {
        val first=store();val destination=temporary.newFolder()
        first.addLocal(listOf(CookingSession(id="interrupted",conversation=listOf(SessionChatMessage(sender="assistant",message="x".repeat(150_000))))))
        val chunks=first.chunks(first.transfers().single().second.single()).toList()
        HistoryReplicaStore(destination).accept(chunks.first())
        val resumed=HistoryReplicaStore(destination)
        assertThrows(IllegalArgumentException::class.java) { resumed.accept(chunks.first().copy(dataBase64=Base64.getEncoder().encodeToString(ByteArray(HistoryReplicaStore.CHUNK_BYTES)))) }
        chunks.forEach(resumed::accept)
        assertEquals("interrupted",HistoryReplicaStore(destination).sessions().single().id)
    }
    @Test fun staleDuplicateAndTimerRevisionsCannotGrowArchiveOrReplaceNewerWork() {
        val directory=temporary.newFolder();val first=HistoryReplicaStore(directory)
        val initial=CookingSession(id="one",version=1,updatedAt=1)
        first.addLocal(listOf(initial))
        repeat(29) { tick -> first.addLocal(listOf(initial.copy(activeCookingMs=(tick+1)*1000L,stepElapsedMs=(tick+1)*1000L))) }
        assertEquals(1,File(directory,"records").listFiles()!!.size)
        repeat(50) { revision -> first.addLocal(listOf(initial.copy(version=revision+2L,updatedAt=revision+2L,historyTitle="Revision $revision"))) }
        assertEquals(2,File(directory,"records").listFiles()!!.size)
        first.addLocal(listOf(initial));assertEquals("Revision 49",first.sessions().single().historyTitle)
        assertEquals(2,File(directory,"records").listFiles()!!.size)
    }
    @Test fun malformedPathsAndUnsolicitedPhotoChunksAreRejectedWithoutTouchingOtherFiles() {
        val first=store()
        assertThrows(IllegalArgumentException::class.java) { first.accept(HistoryChunk("record","../escape",1,0,"eA==")) }
        assertThrows(IllegalArgumentException::class.java) { first.accept(HistoryChunk("photo","a".repeat(64),1,0,"eA==")) }
        assertTrue(first.sessions().isEmpty())
    }
    @Test fun oneCorruptRecordDoesNotHideHealthyOfflineHistory() {
        val directory=temporary.newFolder();val first=HistoryReplicaStore(directory)
        first.addLocal(listOf(CookingSession(id="healthy")))
        File(directory,"records/"+"a".repeat(64)+".json").writeText("broken")
        val reopened=HistoryReplicaStore(directory)
        assertEquals("healthy",reopened.sessions().single().id);assertNotNull(reopened.recoveryMessage)
    }
}
