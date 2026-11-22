package com.example.vescurus

import com.example.vescurus.data.repository.*
import com.example.vescurus.session.*
import org.junit.Assert.*
import org.junit.Test

class RecipePlanningContractTest {
    private val session = CookingSession(id = "test", entries = listOf(
        IngredientEntry(sessionId = "test", identities = listOf("Apple"), identitySource = EvidenceSource.MANUAL,
            ingredientPreparation = "sliced")
    ))
    private val valid = """{"name":"Warm apple slices","description":"A simple apple dish.","servings":null,
      "pantryAdditions":[],"steps":[{"instruction":"Warm a pan.","durationSeconds":30},
      {"instruction":"Add the apple slices and a splash of water.","durationSeconds":120},
      {"instruction":"Check texture and serve.","durationSeconds":30}],"calories":999,"source":"Spoonacular",
      "targets":[{"ingredient":"Apple","amount":300,"unit":"g"}]}"""

    @Test fun validRecipeKeepsConfirmedUnknownAmountsAndHonestSource() {
        val result = RecipePlanningParser.parse(valid, session)
        assertEquals(180_000L, result.totalTimeMs)
        assertEquals(3, result.steps.size)
        assertNull(result.targets.single().amount)
        assertNull(result.calories); assertNull(result.proteinG)
        assertNull(result.sourceUrl); assertEquals("AI-generated cooking suggestion", result.source)
        assertEquals("sliced", session.entries.single().ingredientPreparation)
    }

    @Test fun combinedMassIsNotDistributed() {
        val batch = session.copy(entries = listOf(session.entries.single().copy(identities = listOf("Apple", "Pear"),
            quantity = Quantity(100.0, QuantityUnit.GRAMS), quantitySource = EvidenceSource.MANUAL)))
        val target = RecipePlanningParser.parse(valid, batch).targets.single()
        assertEquals("Apple + Pear", target.ingredient)
        assertEquals(100.0, target.amount!!, 0.0)
    }

    @Test fun clarificationIsReadBeforeEmptyRecipeFields() {
        try {
            RecipePlanningParser.parse("""{"name":"","description":"","servings":null,"pantryAdditions":[],"steps":[],"needsClarification":"Would you prefer a raw apple dish?"}""", session)
            fail("Expected clarification")
        } catch (question: RecipePlanningQuestion) {
            assertEquals("Would you prefer a raw apple dish?", question.question)
        }
    }

    @Test fun invalidBoundsAndMalformedResponsesAreRejected() {
        listOf("not json", valid.replace("\"durationSeconds\":30", "\"durationSeconds\":0"),
            valid.replace("\"servings\":null", "\"servings\":21"),
            valid.replace("\"durationSeconds\":120", "\"durationSeconds\":\"120\""),
            valid.replace("\"Warm apple slices\"", "\"${"x".repeat(101)}\"")).forEach {
            try { RecipePlanningParser.parse(it, session); fail("Accepted invalid recipe") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
