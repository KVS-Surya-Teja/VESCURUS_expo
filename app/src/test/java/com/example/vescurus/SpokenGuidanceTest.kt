package com.example.vescurus

import com.example.vescurus.model.spokenGuidance
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenGuidanceTest {
    @Test fun readsCookingUnitsWithoutChangingAmounts() {
        assertEquals("Use 200 grams tomato and 10 milliliters oil at 180 degrees Celsius.",
            spokenGuidance("Use 200 g tomato and 10 ml oil at 180°C."))
    }
    @Test fun readsLinkWordsAndLeavesOutVisualMarkup() {
        assertEquals("Stir gently. Read the recipe.",
            spokenGuidance("**Stir gently.** Read [the recipe](https://example.com)."))
    }
    @Test fun preservesUncertaintyAndWholeIngredientWords() {
        assertEquals("2 eggs; the amount is unknown. Check before continuing.",
            spokenGuidance("2 eggs; the amount is unknown.\n- Check before continuing."))
    }
}
