package com.example.vescurus

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vescurus.network.*
import com.example.vescurus.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Real TCP on the phone, explicit protocol fixture. Not two-phone or model-inference evidence. */
@RunWith(AndroidJUnit4::class) class LocalPeerProtocolTest {
    private fun await(condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(!condition() && SystemClock.elapsedRealtime()<end)Thread.sleep(50)
        assertTrue("Peer state did not converge within 15 seconds",condition())
    }
    @Test fun pairingFrameIdentitySessionSyncAndRepeatedReconnectUseRealSocket() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("vescurus",0)
        val previousCode=prefs.getString("guidePairCode",null)
        val previousOwnCode=prefs.getString("ownPairCode",null)
        // An already-paired physical phone must not take over this isolated loopback fixture.
        var testCode: String
        do { testCode=(100000+java.security.SecureRandom().nextInt(900000)).toString() } while(testCode==previousOwnCode)
        prefs.edit().putString("ownPairCode",testCode).commit()
        val guide=VescurusConnectionManager();val cook=VescurusConnectionManager()
        val session=SessionContext(UUID.randomUUID().toString(),1,"Protocol fixture — no cooking claim")
        try {
            instrumentation.runOnMainSync {
                guide.start(context,Role.GUIDE)
                prefs.edit().putString("guidePairCode",guide.pairCode.value).commit()
            }
            val probe=HttpClient(CIO) { install(WebSockets) }
            try {
                runBlocking {
                    withTimeout(10000) {
                        probe.webSocket("ws://127.0.0.1:8766/ws") {
                            assertEquals("READY|2",(incoming.receive() as Frame.Text).readText())
                            send("ANALYZE|${session.sessionId}|unpaired-request")
                            delay(250)
                            assertEquals(0L,guide.analysisRequest.value)
                        }
                        probe.webSocket("ws://127.0.0.1:8766/ws") {
                            incoming.receive()
                            send("PAIR|"+Json.encodeToString(PairRequest(testCode,session)))
                            assertTrue((incoming.receive() as Frame.Text).readText().startsWith("SYNC|"))
                            send("ANALYZE|stale-session|request-one")
                            delay(250)
                            assertEquals(0L,guide.analysisRequest.value)
                            send("ANALYZE|${session.sessionId}|request-one")
                            send("ANALYZE|${session.sessionId}|request-one")
                            delay(250)
                            assertEquals("Repeated command must trigger only once",1L,guide.analysisRequest.value)
                        }
                    }
                }
            } finally { probe.close() }
            instrumentation.runOnMainSync {
                cook.start(context,Role.COOK);cook.updateSession(session);cook.connectDirectly("127.0.0.1",8766)
            }
            await { cook.status.value==ConnectionStatus.CONNECTED && guide.sessionContext.value==session }
            instrumentation.runOnMainSync { cook.updateSession(session.copy(version=2,step=1,paused=true)) }
            await { guide.sessionContext.value?.version==2L && guide.sessionContext.value?.paused==true }
            val observation=Observation(session.sessionId,"protocol-fixture",1,SystemClock.elapsedRealtime(),2,2,"PROTOCOL_TEST","SUCCESS",0,0,"fixture",emptyList())
            instrumentation.runOnMainSync { guide.broadcastObservation(observation) }
            await { cook.observation.value?.frameId==1L }
            assertEquals("protocol-fixture",cook.observation.value?.streamId)
            val beforeRequest=guide.analysisRequest.value
            instrumentation.runOnMainSync { cook.sendSnapshotCommand() }
            await { guide.analysisRequest.value==beforeRequest+1 }
            assertNull("New recognition clears the previous analyzed result",cook.observation.value)
            val jpeg="/9j/"+"A".repeat(80)+"/9k="
            val photo=VideoFrame(session.sessionId,"finished-photo-fixture",50,SystemClock.elapsedRealtime(),2,2,jpeg)
            instrumentation.runOnMainSync { cook.requestFinishedPhoto("wrong-session") }
            instrumentation.runOnMainSync { guide.broadcastVideo(photo) }
            Thread.sleep(250)
            assertNull("Unrequested session must not accept a photo",cook.finishedPhoto.value)
            instrumentation.runOnMainSync { cook.requestFinishedPhoto(session.sessionId) }
            // Request and camera traffic travel on different coroutines; wait for the command to arrive.
            Thread.sleep(250)
            instrumentation.runOnMainSync { guide.broadcastVideo(photo.copy(sessionId="previous-session",frameId=51,captureUptimeMs=SystemClock.elapsedRealtime())) }
            Thread.sleep(250)
            assertNull("A pending request cannot accept a previous session's frame",cook.finishedPhoto.value)
            val requested=photo.copy(frameId=51,captureUptimeMs=SystemClock.elapsedRealtime())
            instrumentation.runOnMainSync { guide.broadcastVideo(requested) }
            await { cook.finishedPhoto.value?.frameId==51L }
            assertEquals(session.sessionId,cook.finishedPhoto.value?.sessionId)
            assertEquals(requested.jpegBase64,cook.finishedPhoto.value?.jpegBase64)
            instrumentation.runOnMainSync { guide.broadcastVideo(requested.copy(frameId=52,captureUptimeMs=SystemClock.elapsedRealtime())) }
            Thread.sleep(250)
            assertEquals("One request accepts one frame",51L,cook.finishedPhoto.value?.frameId)
            repeat(3) {
                instrumentation.runOnMainSync { cook.connectDirectly("127.0.0.1",8766) }
                await { cook.status.value==ConnectionStatus.CONNECTED && guide.sessionContext.value?.version==2L }
            }
            Thread.sleep(17000) // Verify heartbeats beyond request/handshake timeouts and delayed old retries.
            assertEquals(ConnectionStatus.CONNECTED,cook.status.value)
            assertEquals(session.sessionId,guide.sessionContext.value?.sessionId)
            // Switching the camera phone's role stops its server while Cook is still connected.
            // This must clear stale results, avoid a shutdown-thread crash and permit restart.
            repeat(2) { cycle ->
                val before = observation.copy(streamId="before-stop-$cycle",captureUptimeMs=SystemClock.elapsedRealtime())
                instrumentation.runOnMainSync { guide.broadcastObservation(before) }
                await { cook.observation.value?.streamId==before.streamId }
                instrumentation.runOnMainSync { guide.stop() }
                await { cook.status.value!=ConnectionStatus.CONNECTED }
                assertNull(cook.observation.value)
                instrumentation.runOnMainSync { guide.start(context,Role.GUIDE) }
                await { cook.status.value==ConnectionStatus.CONNECTED && guide.sessionContext.value?.version==2L }
                val after = observation.copy(streamId="after-restart-$cycle",captureUptimeMs=SystemClock.elapsedRealtime())
                instrumentation.runOnMainSync { guide.broadcastObservation(after) }
                await { cook.observation.value?.streamId==after.streamId }
            }
        } finally {
            instrumentation.runOnMainSync { cook.stop();guide.stop() }
            if(previousCode==null)prefs.edit().remove("guidePairCode").commit() else prefs.edit().putString("guidePairCode",previousCode).commit()
            if(previousOwnCode==null)prefs.edit().remove("ownPairCode").commit() else prefs.edit().putString("ownPairCode",previousOwnCode).commit()
        }
    }
}
