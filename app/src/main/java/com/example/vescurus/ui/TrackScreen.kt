package com.example.vescurus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.session.EntryStatus
import com.example.vescurus.session.EvidenceSource
import com.example.vescurus.model.Recipe
import coil.compose.AsyncImage
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun TrackScreen(viewModel: CookViewModel, onResumeDraft: () -> Unit = {}, allowResume: Boolean = true) {
    val muted = Color(0xFF94A3B8)
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTitle by rememberSaveable { mutableStateOf("") }
    if (editingId != null) {
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("Name your dish") },
            text = {
                OutlinedTextField(
                    value = editingTitle, onValueChange = { editingTitle = it.take(100) },
                    label = { Text("Dish name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("history-title-input")
                )
            },
            confirmButton = {
                TextButton(enabled = editingTitle.isNotBlank(), onClick = {
                    editingId?.let { viewModel.renameCompletedSession(it, editingTitle.trim()) }
                    editingId = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingId = null }) { Text("Cancel") } }
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("history-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (viewModel.isDemo) "PRACTICE HISTORY" else "TRACK",
                    color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                )
                Text("Your cooking journal", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
                SessionCard(if (viewModel.isLoading) "Loading your journal…" else "Nothing saved yet") {
                    Text("Recipes you finish or save for later will appear here.", color = muted, fontSize = 14.sp)
                }
            }
        }
        items(viewModel.sessions.reversed(), key = { it.id }) { session ->
            val entries = session.entries.filter { it.status != EntryStatus.UNDONE }
            val ingredientCount = entries.flatMap { it.identities }.distinct().size
            var showIngredients by rememberSaveable(session.id) { mutableStateOf(false) }
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(session.completedAt ?: session.updatedAt))
            val activeTime = if(session.completedAt != null && session.cookingStartedAt != null) {
                val seconds = session.activeCookingMs.coerceAtLeast(0) / 1000
                if(seconds < 60) "${seconds}s active cooking" else "${seconds / 60} min ${seconds % 60}s active cooking"
            } else null
            JournalCard(
                session.historyTitle?.takeIf { it.isNotBlank() } ?: session.recipe?.name ?: "Ingredients",
                listOfNotNull(date, activeTime).joinToString(" · "),
                photoPath = session.finalPhotoPath.takeIf { session.completedAt != null },
                onRename = if(session.completedAt == null) null else ({
                    editingTitle = session.historyTitle ?: session.recipe?.name.orEmpty()
                    editingId = session.id
                })
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HistoryBadge(if (session.completedAt == null) "Saved for later" else "Finished", if (session.completedAt == null) GoldPrimary else Color(0xFF86EFAC))
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
                    Text("Open this on your cooking phone to continue.", color = muted, fontSize = 12.sp)
                }
                if (entries.isNotEmpty()) TextButton(
                    onClick = { showIngredients = !showIngredients },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("$ingredientCount ingredient${if(ingredientCount == 1) "" else "s"}", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    Icon(if(showIngredients) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if(showIngredients) "Hide ingredients" else "Show ingredients")
                }
                AnimatedVisibility(showIngredients) {
                Column {
                entries.forEach { entry ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Text(entry.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(
                                entry.quantity?.let { "${formatQuantity(it.value)} ${it.unit.label}" } ?: "Not entered",
                                color = if (entry.quantity == null) muted else GoldPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.widthIn(max = 130.dp)
                            )
                        }
                        entry.ingredientPreparation?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = muted, fontSize = 12.sp)
                        }
                        val status = when (entry.status) {
                            EntryStatus.TRANSFER_CONFIRMED -> "Added to recipe"
                            EntryStatus.PREPARED -> "Prepared"
                            EntryStatus.IDENTIFIED -> "Not added yet"
                            EntryStatus.UNDONE -> "Removed"
                        }
                        val quantitySource = when (entry.quantitySource) {
                            EvidenceSource.MANUAL -> "Amount entered by you"
                            EvidenceSource.SCALE -> "Scale measurement"
                            EvidenceSource.VISION_CONFIRMED -> "Recorded amount"
                            EvidenceSource.MIXED -> "Mixed quantity sources"
                            null -> ""
                        }
                        Text(if (quantitySource.isBlank()) status else "$status · $quantitySource", color = muted, fontSize = 12.sp)
                        if (entry.identities.size > 1 && entry.quantity != null) {
                            Text("Batch total · individual amounts not recorded", color = muted, fontSize = 12.sp)
                        }
                    }
                }
                }
                }
                session.recipe?.let { recipe ->
                    if (session.completedAt != null && (recipe.calories != null || listOf(recipe.proteinG,recipe.carbsG,recipe.fatsG).any { it != null && it.isFinite() && it >= 0f })) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
                        HistoryNutrition(recipe)
                    }
                }

            }
        }
    }
}

@Composable
private fun HistoryNutrition(recipe: Recipe) {
    val muted = Color(0xFF94A3B8)
    val macros = listOf(
        Triple("Protein", recipe.proteinG, Color(0xFF86EFAC)),
        Triple("Carbs", recipe.carbsG, GoldPrimary),
        Triple("Fat", recipe.fatsG, Color(0xFFC4B5FD))
    ).filter { it.second?.let { grams -> grams.isFinite() && grams >= 0f } == true }
    if(recipe.calories == null && macros.isEmpty()) {
        Text("Nutrition unavailable for this recipe", color = muted, fontSize = 12.sp)
        return
    }
    Text("Recipe nutrition estimate", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    recipe.calories?.takeIf { it >= 0 }?.let {
        Text("≈ $it kcal per serving", color = GoldPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
    val largest = macros.maxOfOrNull { it.second ?: 0f } ?: 0f
    macros.forEach { (name, grams, color) ->
        val amount = grams ?: 0f
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = muted, fontSize = 12.sp)
                Text("≈ ${formatQuantity(amount.toDouble())} g", color = Color.White, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .06f))) {
                if (largest > 0f && amount > 0f) Box(
                    Modifier.fillMaxWidth((amount / largest).coerceIn(0f, 1f)).fillMaxHeight().background(color)
                )
            }
        }
    }
    Text("Per serving estimate · your portions may differ", color = muted, fontSize = 12.sp)
}

@Composable
private fun JournalCard(title: String, subtitle: String, photoPath: String?, onRename: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        color = Color(0xFF101827), border = BorderStroke(1.dp, Color.White.copy(alpha = .07f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            photoPath?.let { path ->
                if(File(path).isFile) AsyncImage(
                    model = File(path), contentDescription = "Your finished dish", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp))
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if(onRename != null) IconButton(onClick = onRename) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit dish name", tint = GoldPrimary, modifier = Modifier.size(19.dp))
                }
            }
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp)
            content()
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
