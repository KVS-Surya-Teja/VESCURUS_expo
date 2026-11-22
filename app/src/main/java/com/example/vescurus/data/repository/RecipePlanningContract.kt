package com.example.vescurus.data.repository

import com.example.vescurus.model.Recipe
import com.example.vescurus.model.RecipeStep
import com.example.vescurus.model.RecipeTarget
import com.example.vescurus.session.CookingSession
import com.example.vescurus.session.EntryStatus
import kotlinx.serialization.json.*
import java.util.UUID

class RecipePlanningQuestion(val question: String) : Exception("Recipe clarification required")

val recipeResponseSchema = """
{"type":"OBJECT","properties":{
 "name":{"type":"STRING"},"description":{"type":"STRING"},
 "servings":{"type":"INTEGER","nullable":true},
 "pantryAdditions":{"type":"ARRAY","items":{"type":"STRING"},"maxItems":8},
 "steps":{"type":"ARRAY","maxItems":20,"items":{"type":"OBJECT","properties":{
  "instruction":{"type":"STRING"},"durationSeconds":{"type":"INTEGER"}},
  "required":["instruction","durationSeconds"]}},
 "needsClarification":{"type":"STRING"}},
 "required":["name","description","servings","pantryAdditions","steps"]}
""".trimIndent()

/** Pure parser: provider prose cannot replace confirmed quantities, evidence or source attribution. */
object RecipePlanningParser {
    fun parse(text: String, session: CookingSession): Recipe {
        require(text.length <= 35_000) { "Recipe response was too large." }
        val root = Json.parseToJsonElement(text).jsonObject
        fun string(value: JsonElement?, max: Int): String {
            val item = value as? JsonPrimitive
            require(item != null && item.isString) { "Expected text." }
            return item.content.trim().also { require(it.isNotEmpty() && it.length <= max) { "Invalid text length." } }
        }
        root["needsClarification"]?.takeUnless { it == JsonNull }?.let {
            throw RecipePlanningQuestion(string(it, 400))
        }
        val name = string(root["name"], 100)
        val description = string(root["description"], 600)
        val pantry = root.getValue("pantryAdditions").jsonArray
        require(pantry.size <= 8) { "Too many extra ingredients." }
        val extras = pantry.map { string(it, 200) }
        val items = root.getValue("steps").jsonArray
        require(items.size in 3..20) { "Invalid recipe step count." }
        var elapsed = 0L
        fun integer(value: JsonElement?, min: Int, max: Int): Int {
            val number = value as? JsonPrimitive
            require(number != null && !number.isString) { "Expected number." }
            val n = number.doubleOrNull
            require(n != null && n.isFinite() && n % 1.0 == 0.0 && n >= min && n <= max) { "Invalid number." }
            return n.toInt()
        }
        val steps = items.map {
            val item = it.jsonObject
            val instruction = string(item["instruction"], 1000)
            val duration = integer(item["durationSeconds"], 10, 3600) * 1000L
            val start = elapsed
            elapsed += duration
            require(elapsed <= 10_800_000L) { "Recipe duration is too long." }
            RecipeStep(start, elapsed, instruction, instruction)
        }
        val servingsValue = root.getValue("servings")
        val servings = if (servingsValue == JsonNull) null else integer(servingsValue, 1, 20)
        val entries = session.entries.filter { it.status != EntryStatus.UNDONE }
        require(entries.isNotEmpty()) { "Confirm an ingredient first." }
        return Recipe(
            id = "ai-${UUID.randomUUID()}", name = name,
            description = description + if (extras.isEmpty()) "" else "\nOptional pantry additions: ${extras.joinToString("; ")}",
            totalTimeMs = elapsed, steps = steps, servings = servings,
            source = "AI-generated cooking suggestion", sourceUrl = null,
            targets = entries.map { entry ->
                RecipeTarget(entry.label, entry.quantity?.value, entry.quantity?.unit?.label.orEmpty(),
                    entry.quantity?.let { "${it.value} ${it.unit.label} ${entry.label}" } ?: "${entry.label} — amount not recorded")
            }
        )
    }
}
