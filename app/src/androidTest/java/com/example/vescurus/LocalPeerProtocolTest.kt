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
        val guide=VescurusConnectionManager();val cook=VescurusConnectionManager()
        val session=SessionContext(UUID.randomUUID().toString(),1,"Protocol fixture — no cooking claim")
        try {
            instrumentation.runOnMainSync {
                guide.start(context,Role.GUIDE)
                prefs.edit().putString("guidePairCode",guide.pairCode.value).commit()
                cook.start(context,Role.COOK);cook.updateSession(session);cook.connectDirectly("127.0.0.1",8766)
            }
            await { cook.status.value==ConnectionStatus.CONNECTED && guide.sessionContext.value==session }
            instrumentation.runOnMainSync { cook.updateSession(session.copy(version=2,step=1,paused=true)) }
            await { guide.sessionContext.value?.version==2L && guide.sessionContext.value?.paused==true }
            val observation=Observation(session.sessionId,"protocol-fixture",1,SystemClock.elapsedRealtime(),2,2,"PROTOCOL_TEST","SUCCESS",0,0,"fixture",emptyList())
            instrumentation.runOnMainSync { guide.broadcastObservation(observation) }
            await { cook.observation.value?.frameId==1L }
            assertEquals("protocol-fixture",cook.observation.value?.streamId)
            repeat(3) {
                instrumentation.runOnMainSync { cook.connectDirectly("127.0.0.1",8766) }
                await { cook.status.value==ConnectionStatus.CONNECTED && guide.sessionContext.value?.version==2L }
            }
            Thread.sleep(17000) // Verify heartbeats beyond request/handshake timeouts and delayed old retries.
            assertEquals(ConnectionStatus.CONNECTED,cook.status.value)
            assertEquals(session.sessionId,guide.sessionContext.value?.sessionId)
        } finally {
            instrumentation.runOnMainSync { cook.stop();guide.stop() }
            if(previousCode==null)prefs.edit().remove("guidePairCode").commit() else prefs.edit().putString("guidePairCode",previousCode).commit()
        }
    }
}
