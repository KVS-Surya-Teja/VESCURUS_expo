package com.example.vescurus

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real installed open-source engine, no cloud and no cooking/measurement claim. */
@RunWith(AndroidJUnit4::class)
class OfflineVoiceTest {
    @Test fun installedEngineGeneratesAudibleOfflineSpeech() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val engine="dev.vescurus.voice"
        assumeTrue("Optional offline voice engine is not installed",runCatching { context.packageManager.getPackageInfo(engine,0) }.isSuccess)
        val ready=CountDownLatch(1);val done=CountDownLatch(1)
        val initialized=AtomicInteger(TextToSpeech.ERROR);val failed=AtomicInteger(0)
        lateinit var tts: TextToSpeech
        instrumentation.runOnMainSync { tts=TextToSpeech(context,{ initialized.set(it);ready.countDown() },engine) }
        try {
            assertTrue("Voice initialization timed out",ready.await(30,TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS,initialized.get())
            val selected=tts.voices.firstOrNull { it.name=="kitten-nano-en-v0_8-fp32#5#expr-voice-4-f" }
            assumeTrue("Optional Kitten Nano voice model is not installed",selected!=null)
            assertEquals("Speaker 5 could not be selected",TextToSpeech.SUCCESS,tts.setVoice(selected!!))
            tts.setSpeechRate(1.1f)
            tts.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.countDown() }
                @Deprecated("Required Android callback") override fun onError(id: String?) { failed.set(1);done.countDown() }
            })
            val file=File(context.cacheDir,"offline-voice-sample.wav")
            val started=android.os.SystemClock.elapsedRealtime()
            assertEquals(TextToSpeech.SUCCESS,tts.synthesizeToFile(
                "Add the tomatoes gently, stir slowly, and check the pan before you continue.",Bundle(),file,"offline-voice-check"))
            assertTrue("Voice generation timed out",done.await(60,TimeUnit.SECONDS))
            assertEquals("Voice generation failed",0,failed.get())
            val elapsed=android.os.SystemClock.elapsedRealtime()-started
            val bytes=file.readBytes()
            File(context.cacheDir,"offline-voice-metrics.json").writeText(
                org.json.JSONObject().put("generationMs",elapsed).put("audioBytes",bytes.size)
                    .put("engine",engine).put("voice",selected.name).put("speechRate",1.1).toString())
            assertTrue("Generated speech was empty",bytes.size>48000)
            assertEquals("RIFF",String(bytes,0,4,Charsets.US_ASCII))
            assertTrue("Generated speech was silent",bytes.drop(44).any { it.toInt()!=0 })
        } finally { tts.stop();tts.shutdown() }
    }
}
