package com.example.vescurus.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectionSemanticsTest {

    @Test
    fun `canonicalizes case-insensitive egg forms`() {
        assertEquals("egg", canonicalizeIngredientLabel("Egg"))
        assertEquals("egg", canonicalizeIngredientLabel("EGG"))
        assertEquals("egg", canonicalizeIngredientLabel("  egg  "))
        assertEquals("egg", canonicalizeIngredientLabel("hen's egg"))
    }

    @Test
    fun `canonicalizes green chili variants`() {
        assertEquals("green chili", canonicalizeIngredientLabel("green chili"))
        assertEquals("green chili", canonicalizeIngredientLabel("Green Chilli"))
        assertEquals("green chili", canonicalizeIngredientLabel("chili"))
        assertEquals("green chili", canonicalizeIngredientLabel("chilli"))
    }

    @Test
    fun `canonicalizes red chilli powder variants`() {
        assertEquals("red chilli powder", canonicalizeIngredientLabel("red chilli"))
        assertEquals("red chilli powder", canonicalizeIngredientLabel("red chili"))
        assertEquals("red chilli powder", canonicalizeIngredientLabel("chilli powder"))
        assertEquals("red chilli powder", canonicalizeIngredientLabel("chili powder"))
    }

    @Test
    fun `black pepper matches pepper alone`() {
        assertEquals("black pepper", canonicalizeIngredientLabel("pepper"))
        assertEquals("black pepper", canonicalizeIngredientLabel("BLACK PEPPER"))
    }

    @Test
    fun `unsupported label surfaces explicitly`() {
        assertEquals("Unsupported object", canonicalizeIngredientLabel("unsupported thing"))
    }

    @Test
    fun `unknown labels return null`() {
        assertNull(canonicalizeIngredientLabel("sushi"))
        assertNull(canonicalizeIngredientLabel(""))
        assertNull(canonicalizeIngredientLabel("random rock"))
    }
}
