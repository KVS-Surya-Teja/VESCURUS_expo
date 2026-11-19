package com.example.vescurus

import com.example.vescurus.model.BoundingBox
import com.example.vescurus.model.EGG_RECIPES
import com.example.vescurus.model.canonicalizeIngredientLabel
import com.example.vescurus.model.normalizeDetectionBox
import org.junit.Assert.*
import org.junit.Test

class VescurusV0UnitTest {

    @Test
    fun testEggCanonicalization() {
        assertEquals("Egg", canonicalizeIngredientLabel("egg"))
        assertEquals("Egg", canonicalizeIngredientLabel("Egg"))
        assertEquals("Egg", canonicalizeIngredientLabel("boiled egg"))
        assertEquals("Egg", canonicalizeIngredientLabel("raw egg"))
        assertEquals("Egg", canonicalizeIngredientLabel("eggs"))
        assertEquals("Unsupported object", canonicalizeIngredientLabel("phone"))
        assertEquals("Unsupported object", canonicalizeIngredientLabel("pen"))
    }

    @Test
    fun testNormalizeDetectionBox() {
        // 0..1000 coordinate format
        val box1000 = BoundingBox(top = 250f, left = 300f, bottom = 700f, right = 650f)
        val normalized1000 = normalizeDetectionBox(box1000)
        assertNotNull(normalized1000)
        assertEquals(0.25f, normalized1000!!.top, 0.001f)
        assertEquals(0.30f, normalized1000.left, 0.001f)
        assertEquals(0.70f, normalized1000.bottom, 0.001f)
        assertEquals(0.65f, normalized1000.right, 0.001f)

        // 0..1 format
        val box1 = BoundingBox(top = 0.2f, left = 0.3f, bottom = 0.8f, right = 0.7f)
        val normalized1 = normalizeDetectionBox(box1)
        assertNotNull(normalized1)
        assertEquals(0.2f, normalized1!!.top, 0.001f)
        assertEquals(0.3f, normalized1.left, 0.001f)
        assertEquals(0.8f, normalized1.bottom, 0.001f)
        assertEquals(0.7f, normalized1.right, 0.001f)

        // Inverted box should be null
        val invalidBox = BoundingBox(top = 0.8f, left = 0.7f, bottom = 0.2f, right = 0.3f)
        assertNull(normalizeDetectionBox(invalidBox))
    }

    @Test
    fun testEggRecipesCompleteness() {
        assertEquals(4, EGG_RECIPES.size)
        val recipeIds = EGG_RECIPES.map { it.id }.toSet()
        assertTrue("Must contain omelette", recipeIds.contains("omelette"))
        assertTrue("Must contain scrambled", recipeIds.contains("scrambled"))
        assertTrue("Must contain sunny_side", recipeIds.contains("sunny_side"))
        assertTrue("Must contain pancake", recipeIds.contains("pancake"))

        // Verify all steps are ordered and have valid TTS
        for (recipe in EGG_RECIPES) {
            assertTrue("Recipe ${recipe.name} must have steps", recipe.steps.isNotEmpty())
            var prevEnd = 0L
            for (step in recipe.steps) {
                assertTrue("Step instruction must not be blank", step.instruction.isNotBlank())
                assertTrue("End time must be after start time", step.endTimeMs > step.startTimeMs)
                assertTrue("Start time must be >= previous end time", step.startTimeMs >= prevEnd)
                prevEnd = step.endTimeMs
                if (step.ttsPrompt != null) {
                    assertTrue("TTS prompt must not be blank", step.ttsPrompt.isNotBlank())
                }
            }
            assertTrue("Total time must match or exceed last step", recipe.totalTimeMs >= prevEnd)
        }
    }

    @Test
    fun testOverlayProjectionMath() {
        val screenW = 1080f
        val screenH = 2400f
        val sourceAspect = 3f / 4f // 0.75

        val screenAspect = screenW / screenH // 0.45
        assertTrue(screenAspect <= sourceAspect)

        val displayedW = screenH * sourceAspect // 1800
        val cropX = (displayedW - screenW) / 2f // 360

        // Object in center of camera (0.5, 0.5)
        val normCenterX = 0.5f
        val normCenterY = 0.5f

        val pixelX = normCenterX * displayedW - cropX
        val pixelY = normCenterY * screenH

        // X must project to center of screen (540)
        assertEquals(540f, pixelX, 0.001f)
        // Y must project to center of screen (1200)
        assertEquals(1200f, pixelY, 0.001f)
    }
}
