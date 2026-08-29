package com.example.vescurus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.model.EGG_RECIPES
import com.example.vescurus.ui.theme.GoldPrimary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrackScreen(viewModel: CookViewModel) {
    val history by viewModel.history.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
            .padding(16.dp)
    ) {
        Text(
            text = "NUTRITION & COOKING LOG",
            color = GoldPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
        Text(
            text = "Recent Sessions",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No cooking sessions recorded yet.\nComplete a recipe to track macros!",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history, key = { it.id }) { item ->
                    val micros = remember(item.recipeId) {
                        EGG_RECIPES.find { it.id == item.recipeId }?.micros.orEmpty()
                    }
                    HistoryRow(item = item, micros = micros)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: com.example.vescurus.model.CookHistoryItem, micros: String) {
    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.recipeName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Text(
                    text = "${item.calories} kcal",
                    color = GoldPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            val dateStr = remember(item.timestamp) {
                SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                    .format(Date(item.timestamp))
            }
            Text(text = dateStr, color = Color.Gray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MacroChip("Protein", "${item.proteinG.trimZero()} g", Color(0xFF93C5FD))
                MacroChip("Carbs", "${item.carbsG.trimZero()} g", Color(0xFFFDE047))
                MacroChip("Fats", "${item.fatsG.trimZero()} g", Color(0xFF86EFAC))
            }
            if (micros.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "MICROS",
                    color = GoldPrimary.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = micros,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun MacroChip(label: String, value: String, color: Color) {
    Column {
        Text(text = label.uppercase(), color = Color.Gray, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(text = value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun Float.trimZero(): String {
    val i = this.toInt()
    return if (this == i.toFloat()) i.toString() else "%.1f".format(this)
}
