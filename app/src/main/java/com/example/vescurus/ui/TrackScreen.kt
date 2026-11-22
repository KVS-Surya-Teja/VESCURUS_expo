package com.example.vescurus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.session.EntryStatus
import com.example.vescurus.session.EvidenceSource
import java.text.DateFormat
import java.util.Date

@Composable
fun TrackScreen(viewModel: CookViewModel, onResumeDraft: () -> Unit = {}, allowResume: Boolean = true) {
    val muted = Color(0xFF94A3B8)
    LazyColumn(
        Modifier.fillMaxSize().testTag("history-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (viewModel.isDemo) "PRACTICE HISTORY" else "HISTORY",
                    color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                Text("Your cooking journal", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    when {
                        viewModel.isDemo -> "Practice sessions, kept separate from your cooking history."
                        !allowResume -> "Cooking sessions saved on this phone."
                        else -> "Pick up a draft or revisit something you made."
                    },
                    color = muted, fontSize = 14.sp
                )
            }
        }
        viewModel.storageMessage?.let { message ->
            item {
                SessionCard("Your changes need saving") {
                    Text(message, color = GoldPrimary, fontSize = 13.sp)
                    TextButton(onClick = viewModel::retrySave) { Text("Retry save") }
                }
            }
        }
        if (viewModel.sessions.isEmpty()) {
            item {
                SessionCard(if (viewModel.isLoading) "Loading your journal…" else "Your first session starts here") {
                    Text("Finished sessions and saved drafts will appear here.", color = muted, fontSize = 14.sp)
                }
            }
        }
        items(viewModel.sessions.reversed(), key = { it.id }) { session ->
            var detailsExpanded by rememberSaveable(session.id) { mutableStateOf(false) }
            val entries = session.entries.filter { it.status != EntryStatus.UNDONE }
            SessionCard(
                session.recipe?.name ?: "Ingredient preparation",
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(session.completedAt ?: session.updatedAt))
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HistoryBadge(if (session.completedAt == null) "Draft" else "Completed", if (session.completedAt == null) GoldPrimary else Color(0xFF86EFAC))
                    if (session.demonstration) HistoryBadge("Practice", Color(0xFFC4B5FD))
                }
                if (session.completedAt == null && allowResume) {
                    OutlinedButton(
                        onClick = {
                            viewModel.resumeDraft(session.id)
                            if (viewModel.sessionId == session.id) onResumeDraft()
                        },
                        enabled = !viewModel.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Continue cooking") }
                } else if (session.completedAt == null) {
                    Text("Continue this draft in Cooking phone mode.", color = muted, fontSize = 12.sp)
                }
                if (entries.isEmpty()) {
                    Text("No ingredients recorded.", color = muted, fontSize = 13.sp)
                }
                entries.forEach { entry ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Text(entry.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(
                                entry.quantity?.let { "${formatQuantity(it.value)} ${it.unit.label}" } ?: "Not recorded",
                                color = if (entry.quantity == null) muted else GoldPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.widthIn(max = 130.dp)
                            )
                        }
                        val status = when (entry.status) {
                            EntryStatus.TRANSFER_CONFIRMED -> "Added to recipe · confirmed by you"
                            EntryStatus.PREPARED -> "Prepared"
                            EntryStatus.IDENTIFIED -> "Identified"
                            EntryStatus.UNDONE -> "Removed"
                        }
                        val quantitySource = when (entry.quantitySource) {
                            EvidenceSource.MANUAL -> "Manual quantity"
                            EvidenceSource.SCALE -> "Scale measurement"
                            EvidenceSource.VISION_CONFIRMED -> "Image-confirmed quantity"
                            EvidenceSource.MIXED -> "Mixed quantity sources"
                            null -> "Quantity not recorded"
                        }
                        Text("$status · $quantitySource", color = muted, fontSize = 12.sp)
                        if (entry.identities.size > 1 && entry.quantity != null) {
                            Text("Batch total · individual amounts not recorded", color = muted, fontSize = 12.sp)
                        }
                    }
                }
                session.recipe?.let { recipe ->
                    if (recipe.calories != null || recipe.proteinG != null || recipe.carbsG != null || recipe.fatsG != null) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                        Text(nutritionText(recipe), color = muted, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { detailsExpanded = !detailsExpanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(if (detailsExpanded) "Hide record details" else "Record details", color = muted, fontSize = 12.sp)
                    Icon(if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                }
                AnimatedVisibility(detailsExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Session ${session.id.take(8)} · revision ${session.version}", color = muted, fontSize = 11.sp)
                        val corrections = session.entries.sumOf { it.audits.size }
                        val removed = session.entries.count { it.status == EntryStatus.UNDONE }
                        Text("$corrections recorded changes · $removed removed entries", color = muted, fontSize = 11.sp)
                        Text("Saved on this phone. Export full records from Settings.", color = muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBadge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
