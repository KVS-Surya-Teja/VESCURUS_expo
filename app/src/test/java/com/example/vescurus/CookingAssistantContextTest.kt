package com.example.vescurus

import com.example.vescurus.model.BUILT_IN_RECIPES
import com.example.vescurus.session.*
import com.example.vescurus.ui.ChatMessage
import com.example.vescurus.ui.cookingAssistantPrompt
import org.junit.Assert.*
import org.junit.Test

class CookingAssistantContextTest {
    @Test fun followUpIncludesConversationAndCurrentStep() {
        val recipe = BUILT_IN_RECIPES.first()
        val session = CookingSession(recipe = recipe, state = "COOKING", step = 1, paused = true)
        val prompt = cookingAssistantPrompt(session, listOf(
            ChatMessage("User", "Can I use olive oil?"),
            ChatMessage("AI suggestion", "Yes, use a little olive oil."),
            ChatMessage("User", "How much of it?")
        ))
        assertTrue(prompt.contains("Can I use olive oil?"))
        assertTrue(prompt.contains("Yes, use a little olive oil."))
        assertTrue(prompt.contains("How much of it?"))
        assertTrue(prompt.contains(recipe.steps[1].instruction))
        assertTrue(prompt.contains("COOKING (paused)"))
    }

    @Test fun contextPreservesUnitsUnknownAmountsAndCombinedBatches() {
        val base = CookingSession()
        val entries = listOf(
            IngredientEntry(sessionId = base.id, identities = listOf("Tomato", "Onion"),
                identitySource = EvidenceSource.MIXED, quantity = Quantity(150.0, QuantityUnit.GRAMS),
                quantitySource = EvidenceSource.MANUAL),
            IngredientEntry(sessionId = base.id, identities = listOf("Oil"),
                identitySource = EvidenceSource.MANUAL, quantity = Quantity(10.0, QuantityUnit.MILLILITRES),
                quantitySource = EvidenceSource.MANUAL),
            IngredientEntry(sessionId = base.id, identities = listOf("Lime"),
                identitySource = EvidenceSource.MANUAL, quantity = Quantity(1.0, QuantityUnit.COUNT),
                quantitySource = EvidenceSource.MANUAL),
            IngredientEntry(sessionId = base.id, identities = listOf("Salt"), identitySource = EvidenceSource.MANUAL)
        )
        val prompt = cookingAssistantPrompt(base.copy(entries = entries), emptyList())
        assertTrue(prompt.contains("Tomato + Onion: 150.0 g"))
        assertTrue(prompt.contains("one combined batch, not a quantity for each ingredient"))
        assertTrue(prompt.contains("Oil: 10.0 ml"))
        assertTrue(prompt.contains("Lime: 1.0 count"))
        assertTrue(prompt.contains("Salt: quantity unknown"))
        assertFalse(prompt.contains("scale record"))
    }

    @Test fun removedIngredientsAndServiceErrorsDoNotBecomeCookingContext() {
        val base = CookingSession()
        val removed = IngredientEntry(sessionId = base.id, identities = listOf("Removed ingredient"),
            identitySource = EvidenceSource.MANUAL, status = EntryStatus.UNDONE)
        val prompt = cookingAssistantPrompt(base.copy(entries = listOf(removed)), listOf(
            ChatMessage("System", "An obsolete service error"),
            ChatMessage("User", "What can I make?")
        ))
        assertFalse(prompt.contains("Removed ingredient"))
        assertFalse(prompt.contains("An obsolete service error"))
        assertTrue(prompt.contains("None confirmed yet."))
        assertTrue(prompt.contains("No camera image is attached"))
    }

    @Test fun longConversationsKeepRecentTurnsWithinABoundedPrompt() {
        val messages = (1..20).map { ChatMessage("User", "Question-$it-end") }
        val prompt = cookingAssistantPrompt(CookingSession(), messages)
        assertFalse(prompt.contains("Question-8-end"))
        assertTrue(prompt.contains("Question-9-end"))
        assertTrue(prompt.contains("Question-20-end"))
    }
}
