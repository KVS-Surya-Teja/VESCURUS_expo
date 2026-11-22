package com.example.vescurus

import com.example.vescurus.model.*
import org.junit.Assert.*
import org.junit.Test

class ObservationRegressionTest {
    private fun observation(frame: Long = 1, stream: String = "g1", session: String = "s1", age: Long = 100) =
        Observation(session, stream, frame, 1000, 640, 480, "GEMINI/test", "SUCCESS", 100, age, "jpeg")
    @Test fun normalizationDoesNotUseSubstrings() {
        assertEquals("Eggplant", canonicalizeIngredientLabel("eggplant"))
        assertEquals("Penne", canonicalizeIngredientLabel("penne"))
        assertEquals("Jalapeno", canonicalizeIngredientLabel("jalapeno"))
        assertEquals("Egg", canonicalizeIngredientLabel("raw egg"))
    }
    @Test fun duplicateAndOutOfOrderResultsRejected() {
        val gate = ObservationGate()
        assertTrue(gate.accept(observation(2), "s1"))
        assertFalse(gate.accept(observation(2), "s1"))
        assertFalse(gate.accept(observation(1), "s1"))
        assertTrue(gate.accept(observation(3), "s1"))
    }
    @Test fun oldSessionAndRetiredStreamRejected() {
        val gate = ObservationGate()
        assertFalse(gate.accept(observation(session = "old"), "s1"))
        assertTrue(gate.accept(observation(4), "s1"))
        assertTrue(gate.accept(observation(1, stream = "g2"), "s1"))
        assertFalse(gate.accept(observation(5), "s1"))
    }
    @Test fun sourceAndReceptionAgeBothCount() {
        val gate = ObservationGate()
        val o = observation(age = 3000)
        assertTrue(gate.fresh(o, 80000, 91999))
        assertFalse(gate.fresh(o, 80000, 92000))
        assertFalse(gate.accept(observation(age = 15000), "s1"))
    }
    @Test fun successfulEmptyIsDifferentFromMalformedResponse() {
        assertTrue(GeminiResponseParser.parse("""{"detections":[],"overall_confidence":0}""").detections.isEmpty())
        assertThrows(Exception::class.java) { GeminiResponseParser.parse("{}") }
        assertThrows(Exception::class.java) { GeminiResponseParser.parse("not json") }
    }
    @Test fun providerCoordinatesAndSupportArePreserved() {
        val result = GeminiResponseParser.parse("""{"detections":[{"label":"eggplant","confidence":0.9,"box_2d":[100,200,700,900],"is_supported":false}],"overall_confidence":0.9}""")
        assertEquals("Eggplant", result.detections.single().label)
        assertFalse(result.detections.single().is_supported)
        assertEquals(.2f, result.detections.single().box_2d.xmin, .0001f)
    }
    @Test fun malformedBoxIsNotAnEmptySuccess() {
        assertThrows(Exception::class.java) { GeminiResponseParser.parse("""{"detections":[{"label":"tomato","confidence":0.9,"box_2d":[700,200,100,900],"is_supported":true}],"overall_confidence":0.9}""") }
    }
    @Test fun snapshotCarriesItsOwnFrameAndDimensions() {
        val o = observation(3).copy(width = 480, height = 640, jpegBase64 = "own-image")
        val encoded = kotlinx.serialization.json.Json.encodeToString(Observation.serializer(), o)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(Observation.serializer(), encoded)
        assertEquals(3L, decoded.frameId); assertEquals(480, decoded.width)
        assertEquals("own-image", decoded.jpegBase64)
    }
    @Test fun androidParserAcceptsActualRecordedProviderResponse() {
        val text = javaClass.getResourceAsStream("/vision/actual-gemini-mixed-response.json")!!.bufferedReader().use { it.readText() }
        val response = GeminiResponseParser.parse(text)
        assertEquals(setOf("Eggplant", "Tomato", "Banana"), response.detections.map { it.label }.toSet())
        assertTrue(response.detections.all { it.box_2d.ymin >= 0 && it.box_2d.ymax <= 1 })
    }
    @Test fun fitProjectionKeepsEverySourceCornerVisible() {
        val p=imageProjection(480,640,900f,630f,true)
        assertEquals(472.5f,p.width,.01f);assertEquals(213.75f,p.offsetX,.01f)
        assertTrue(p.offsetX>=0 && p.offsetY>=0)
        assertTrue(p.offsetX+p.width<=900 && p.offsetY+p.height<=630)
        val landscape=imageProjection(640,480,400f,400f,true)
        assertEquals(50f,landscape.offsetY,.01f)
    }
}
