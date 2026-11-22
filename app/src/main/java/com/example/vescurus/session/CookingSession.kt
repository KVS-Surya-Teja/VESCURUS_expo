package com.example.vescurus.session

import com.example.vescurus.model.Recipe
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class QuantityUnit(val label: String) { GRAMS("g"), MILLILITRES("ml"), COUNT("count") }
@Serializable enum class EvidenceSource { MANUAL, SCALE, VISION_CONFIRMED, MIXED }
@Serializable enum class EntryStatus { IDENTIFIED, PREPARED, TRANSFER_CONFIRMED, UNDONE }
@Serializable data class Quantity(val value: Double, val unit: QuantityUnit) {
    init { require(value.isFinite() && value > 0 && value <= 10_000_000) }
}
@Serializable data class ScaleEvidence(val deviceId: String, val bootId: String, val sampleSeq: Long,
    val baselineRevision: Long, val calibrationRevision: Long, val baselineGrams: Double, val sampleGrams: Double)
@Serializable data class VisionEvidence(val sessionId: String, val streamId: String, val frameId: Long, val label: String)
@Serializable data class EntryAudit(val at: Long, val action: String, val previousLabel: String, val previousQuantity: Quantity?, val previousStatus: EntryStatus, val previousPreparation: String? = null)
@Serializable data class IngredientEntry(
    val id: String = UUID.randomUUID().toString(), val sessionId: String,
    val identities: List<String>, val identitySource: EvidenceSource,
    val quantity: Quantity? = null, val quantitySource: EvidenceSource? = null,
    val scale: ScaleEvidence? = null, val vision: VisionEvidence? = null,
    val status: EntryStatus = if (quantity == null) EntryStatus.IDENTIFIED else EntryStatus.PREPARED,
    val createdAt: Long = System.currentTimeMillis(), val audits: List<EntryAudit> = emptyList(),
    val ingredientPreparation: String? = null
) {
    val label: String get() = identities.joinToString(" + ")
}
@Serializable data class CookingSession(
    val schemaVersion: Int = 1, val id: String = UUID.randomUUID().toString(),
    val version: Long = 0, val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt, val entries: List<IngredientEntry> = emptyList(),
    val recipe: Recipe? = null, val state: String = "IDLE", val step: Int = 0,
    val paused: Boolean = false, val stepElapsedMs: Long = 0, val stepTimerSeconds: Long? = null,
    val completedAt: Long? = null, val demonstration: Boolean = false,
    val planningNotes: List<String> = emptyList(),
    val historyTitle: String? = null, val cookingStartedAt: Long? = null,
    val activeCookingMs: Long = 0, val finalPhotoPath: String? = null,
    val finalPhotoCapturedAt: Long? = null
) {
    val ingredientNames: List<String> get() = entries.filter { it.status != EntryStatus.UNDONE }.flatMap { it.identities }.distinct()
}

/** Pure owner-side mutations. A visible label never commits an entry. */
object SessionReducer {
    fun add(s: CookingSession, entry: IngredientEntry, now: Long): CookingSession {
        require(s.completedAt == null && entry.sessionId == s.id && entry.identities.isNotEmpty())
        if (s.entries.any { it.id == entry.id }) return s
        return s.copy(entries = s.entries + entry, version = s.version + 1, updatedAt = now)
    }
    fun edit(s: CookingSession, id: String, identities: List<String>, quantity: Quantity?, source: EvidenceSource?, now: Long, preparation: String? = null): CookingSession {
        require(identities.isNotEmpty())
        return change(s,id,now,"correct") { it.copy(identities = identities, quantity = quantity, quantitySource = source,
            identitySource = EvidenceSource.MANUAL, ingredientPreparation=preparation, scale = if (quantity == it.quantity) it.scale else null,
            vision = null, status = if (quantity == null) EntryStatus.IDENTIFIED else EntryStatus.PREPARED) }
    }
    fun undo(s: CookingSession, id: String, now: Long) = change(s,id,now,"undo") { it.copy(status = EntryStatus.UNDONE) }
    fun transfer(s: CookingSession, id: String, now: Long) = change(s,id,now,"transfer confirmed") { it.copy(status = EntryStatus.TRANSFER_CONFIRMED) }
    private fun change(s: CookingSession,id: String,now: Long,action: String, block: (IngredientEntry)->IngredientEntry): CookingSession {
        require(s.completedAt == null)
        val old = s.entries.find { it.id == id } ?: return s
        if (old.status == EntryStatus.UNDONE) return s
        val changed = block(old)
        if (changed == old) return s
        val audit = EntryAudit(now,action,old.label,old.quantity,old.status,old.ingredientPreparation)
        return s.copy(entries = s.entries.map { if (it.id == id) changed.copy(audits = old.audits + audit) else it }, version=s.version+1,updatedAt=now)
    }
}
