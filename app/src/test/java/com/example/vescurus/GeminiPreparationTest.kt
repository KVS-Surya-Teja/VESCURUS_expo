package com.example.vescurus

import com.example.vescurus.model.GeminiResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiPreparationTest {
    private fun parse(field: String = "") = GeminiResponseParser.parse("""
        {"detections":[{"label":"carrot","confidence":0.9,"box_2d":[10,20,300,400],"is_supported":true$field}],"overall_confidence":0.9}
    """).detections.single()

    @Test fun oldResponsesRemainCompatible() { assertNull(parse().preparation) }

    @Test fun optionalPreparationIsNormalized() {
        assertEquals("julienne", parse(""", "preparation": " Julienne " """).preparation)
    }

    @Test fun uncertainOrUnsafeClaimsAreDiscarded() {
        val quote = '"'
        val values = listOf("unknown", "safe to eat", "fully cooked", "x".repeat(200))
            .map { "$quote$it$quote" } + listOf("null", "42", "{}")
        values.forEach {
            assertNull(parse(""", "preparation": $it""").preparation)
        }
    }
}
