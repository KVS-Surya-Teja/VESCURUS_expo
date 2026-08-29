package com.example.vescurus.data.repository

import com.example.vescurus.data.remote.GeminiClient
import com.example.vescurus.domain.model.DetectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the JSON parser and sanitizer. No network, no Android
 * SDK — the SDK-backed `GeminiClient` is instantiated but never invoked.
 */
class GeminiRepositoryImplTest {

    private val repo = GeminiRepositoryImpl(GeminiClient(apiKey = "test-dummy"))

    // --- parseFlexibleResponse ---

    @Test
    fun `parses canonical response with array-shaped boxes`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "id": "e1", "label": "egg", "confidence": 0.9,
              "box_2d": [100, 200, 500, 600], "alternatives": [], "is_supported": true }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()

        val parsed = repo.parseFlexibleResponse(json)
        assertEquals(1, parsed.detections.size)
        val d = parsed.detections[0]
        assertEquals("egg", d.label)
        assertEquals(100f, d.box_2d.ymin, 0.0001f)
        assertEquals(600f, d.box_2d.xmax, 0.0001f)
    }

    @Test
    fun `parses response with object-shaped boxes`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.8,
              "box_2d": { "ymin": 0.1, "xmin": 0.2, "ymax": 0.5, "xmax": 0.6 } }
          ],
          "overall_confidence": 0.8
        }
        """.trimIndent()

        val parsed = repo.parseFlexibleResponse(json)
        assertEquals(1, parsed.detections.size)
        assertEquals(0.1f, parsed.detections[0].box_2d.ymin, 0.0001f)
    }

    @Test
    fun `fallback ids are unique when input omits ids`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.9, "box_2d": [0, 0, 100, 100] },
            { "label": "egg", "confidence": 0.8, "box_2d": [200, 200, 300, 300] },
            { "label": "egg", "confidence": 0.7, "box_2d": [400, 400, 500, 500] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()

        val parsed = repo.parseFlexibleResponse(json)
        val ids = parsed.detections.map { it.id }
        assertEquals("3 detections must all have distinct fallback ids", 3, ids.toSet().size)
    }

    @Test
    fun `drops detections with missing label or box`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "confidence": 0.9, "box_2d": [0, 0, 100, 100] },
            { "label": "egg", "confidence": 0.8 },
            { "label": "egg", "confidence": 0.7, "box_2d": [10, 10, 20, 20] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()

        val parsed = repo.parseFlexibleResponse(json)
        assertEquals("only the valid entry should survive", 1, parsed.detections.size)
    }

    @Test
    fun `drops box arrays that are not exactly 4 elements`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.9, "box_2d": [0, 0, 100] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()

        val parsed = repo.parseFlexibleResponse(json)
        assertEquals(0, parsed.detections.size)
    }

    @Test
    fun `empty detections list is parsed successfully`() {
        val json = """{ "request_id": "v1", "detections": [], "overall_confidence": 0.0 }""".trimIndent()
        val parsed = repo.parseFlexibleResponse(json)
        assertTrue(parsed.detections.isEmpty())
        assertEquals(0f, parsed.overall_confidence, 0.0001f)
    }

    // --- sanitizeDetectionResponse (EGG_ONLY mode) ---

    @Test
    fun `sanitize EGG_ONLY normalizes 0 to 1000 coordinates into 0 to 1`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.95, "box_2d": [250, 300, 700, 650] }
          ],
          "overall_confidence": 0.95
        }
        """.trimIndent()
        val parsed = repo.parseFlexibleResponse(json)
        val sanitized = repo.sanitizeDetectionResponse(parsed, DetectionMode.EGG_ONLY)
        val d = sanitized.detections[0]
        assertEquals(0.25f, d.box_2d.ymin, 0.001f)
        assertEquals(0.30f, d.box_2d.xmin, 0.001f)
        assertEquals(0.70f, d.box_2d.ymax, 0.001f)
        assertEquals(0.65f, d.box_2d.xmax, 0.001f)
    }

    @Test
    fun `sanitize EGG_ONLY drops non-egg labels`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.9, "box_2d": [100, 100, 500, 500] },
            { "label": "tomato", "confidence": 0.9, "box_2d": [100, 100, 500, 500] },
            { "label": "onion", "confidence": 0.9, "box_2d": [100, 100, 500, 500] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()
        val parsed = repo.parseFlexibleResponse(json)
        val sanitized = repo.sanitizeDetectionResponse(parsed, DetectionMode.EGG_ONLY)
        assertEquals(1, sanitized.detections.size)
        assertEquals("egg", sanitized.detections[0].label)
    }

    @Test
    fun `sanitize drops NaN and Infinite coordinates`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.9,
              "box_2d": { "ymin": "NaN", "xmin": 0, "ymax": 1, "xmax": 1 } }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()
        // NaN as a JSON string won't parse as float; verify graceful drop.
        val parsed = repo.parseFlexibleResponse(json)
        val sanitized = repo.sanitizeDetectionResponse(parsed, DetectionMode.EGG_ONLY)
        assertEquals(0, sanitized.detections.size)
    }

    @Test
    fun `sanitize drops inverted boxes where ymax lt ymin`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.9,
              "box_2d": [800, 100, 200, 500] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()
        val parsed = repo.parseFlexibleResponse(json)
        val sanitized = repo.sanitizeDetectionResponse(parsed, DetectionMode.EGG_ONLY)
        assertEquals(0, sanitized.detections.size)
    }

    @Test
    fun `sanitize drops confidence out of 0 to 1 range`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 1.5, "box_2d": [0, 0, 100, 100] },
            { "label": "egg", "confidence": -0.1, "box_2d": [0, 0, 100, 100] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()
        val parsed = repo.parseFlexibleResponse(json)
        val sanitized = repo.sanitizeDetectionResponse(parsed, DetectionMode.EGG_ONLY)
        assertEquals(0, sanitized.detections.size)
    }

    // --- sanitizeDetectionResponse (GENERAL_INGREDIENTS mode) ---

    @Test
    fun `sanitize GENERAL_INGREDIENTS keeps every canonical vocabulary label`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.9, "box_2d": [0, 0, 100, 100] },
            { "label": "onion", "confidence": 0.8, "box_2d": [100, 100, 200, 200] },
            { "label": "tomato", "confidence": 0.7, "box_2d": [200, 200, 300, 300] },
            { "label": "banana", "confidence": 0.6, "box_2d": [300, 300, 400, 400] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()
        val parsed = repo.parseFlexibleResponse(json)
        val sanitized = repo.sanitizeDetectionResponse(parsed, DetectionMode.GENERAL_INGREDIENTS)
        assertEquals(4, sanitized.detections.size)
    }

    @Test
    fun `sanitize GENERAL_INGREDIENTS drops labels outside the canonical vocabulary`() {
        val json = """
        {
          "request_id": "v1",
          "detections": [
            { "label": "egg", "confidence": 0.9, "box_2d": [0, 0, 100, 100] },
            { "label": "sushi", "confidence": 0.9, "box_2d": [100, 100, 200, 200] },
            { "label": "hamburger", "confidence": 0.9, "box_2d": [200, 200, 300, 300] }
          ],
          "overall_confidence": 0.9
        }
        """.trimIndent()
        val parsed = repo.parseFlexibleResponse(json)
        val sanitized = repo.sanitizeDetectionResponse(parsed, DetectionMode.GENERAL_INGREDIENTS)
        assertEquals(1, sanitized.detections.size)
        assertEquals("egg", sanitized.detections[0].label)
    }

    // --- extractJson ---

    @Test
    fun `extractJson strips markdown code fences`() {
        val text = """```json
        { "request_id": "v1", "detections": [], "overall_confidence": 0.0 }
        ```""".trimIndent()
        val extracted = repo.extractJson(text)
        assertTrue("extracted must be valid JSON object", extracted.startsWith("{") && extracted.endsWith("}"))
    }

    @Test(expected = IllegalStateException::class)
    fun `extractJson throws on text with no JSON object`() {
        repo.extractJson("no braces here at all")
    }
}
