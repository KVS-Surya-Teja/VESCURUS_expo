package com.example.vescurus.ui.previews

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.*
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.ui.CookChatBubble
import com.example.vescurus.ui.ChatMessage
import com.example.vescurus.ui.RecipeSelectionCard

// ---------------------------------------------------------------------------
// Preview fixtures — no ViewModel, no camera, no network
// ---------------------------------------------------------------------------

private val PREVIEW_RECIPE = EGG_RECIPES.first() // Omelette

// --- Cook: Waiting for Guide connection ---
@Preview(name = "Cook – Waiting for Guide", showBackground = true, backgroundColor = 0xFF020617)
@Composable
fun PreviewCookWaiting() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020617)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Video feed placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("WAITING FOR VIDEO...", color = Color.White.copy(alpha = 0.2f), fontSize = 10.sp, letterSpacing = 2.sp)
        }
        // Status label
        Text(
            "STATUS: Searching for Guide...",
            color = Color.Yellow,
            fontSize = 10.sp,
            modifier = Modifier.padding(12.dp)
        )
        // Idle body
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = GoldPrimary, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Scan any ingredients to begin...", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

// --- Cook: Connected — detecting (spinner shown) ---
@Preview(name = "Cook – Connected Detecting", showBackground = true, backgroundColor = 0xFF020617)
@Composable
fun PreviewCookConnectedDetecting() {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020617))) {
        Box(
            modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("GUIDE FEED", color = Color.White.copy(alpha = 0.1f), fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = GoldPrimary, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Scan any ingredients to begin...", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

// --- Cook: Ingredient awaiting confirmation (recipe selection shown) ---
@Preview(name = "Cook – Recipe Selection (Egg detected)", showBackground = true, backgroundColor = 0xFF020617)
@Composable
fun PreviewCookRecipeSelection() {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020617))) {
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("GUIDE FEED", color = Color.White.copy(alpha = 0.1f), fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "DETECTED: EGG",
                color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp
            )
            Text(
                text = "What would you like to cook?",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(EGG_RECIPES) { recipe ->
                    RecipeSelectionCard(
                        recipe = recipe,
                        isSelected = recipe.id == EGG_RECIPES.first().id,
                        onClick = {}
                    )
                }
            }
        }
    }
}

// --- Cook: Quantity unknown / uncertain identity ---
@Preview(name = "Cook – Low Confidence (Quantity Unknown)", showBackground = true, backgroundColor = 0xFF020617)
@Composable
fun PreviewCookQuantityUnknown() {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020617))) {
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("GUIDE FEED", color = Color.White.copy(alpha = 0.1f), fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text("?", color = GoldPrimary, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Ingredient detected (low confidence).\nQuantity unknown.",
                    color = Color.White, fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {},
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
                    ) { Text("Dismiss") }
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
                    ) { Text("Confirm") }
                }
            }
        }
    }
}

// --- Cook: Active recipe step ---
@Preview(name = "Cook – Active Recipe Step", showBackground = true, backgroundColor = 0xFF020617)
@Composable
fun PreviewCookActiveStep() {
    val recipe = PREVIEW_RECIPE
    val progressMs = 45000L
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020617))) {
        Box(
            modifier = Modifier.fillMaxWidth().height(140.dp).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("GUIDE FEED", color = Color.White.copy(alpha = 0.1f), fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Column(modifier = Modifier.padding(16.dp)) {
            val progress = progressMs.toFloat() / recipe.totalTimeMs.toFloat()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(recipe.name.uppercase(), color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${(progress * 100).toInt()}%", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = GoldPrimary,
                trackColor = Color.DarkGray
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                LazyColumn(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recipe.steps) { step ->
                        val isPassed = progressMs >= step.endTimeMs
                        val isActive = progressMs >= step.startTimeMs && progressMs < step.endTimeMs
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (isActive) Modifier.background(Color(0xFF22C55E).copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(8.dp) else Modifier
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(
                                if (isActive) Color(0xFF22C55E) else if (isPassed) Color.Black else Color.Gray, CircleShape
                            ))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = step.instruction,
                                color = when {
                                    isActive -> Color.White
                                    isPassed -> Color.Gray.copy(alpha = 0.5f)
                                    else -> Color.Gray
                                },
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Sample chat
            CookChatBubble(ChatMessage("AI", "Great! Onions should be translucent in about 60 seconds."))
            Spacer(modifier = Modifier.height(8.dp))
            CookChatBubble(ChatMessage("User", "How long should I stir?"))
        }
    }
}

// --- Cook: Connection lost mid-session ---
@Preview(name = "Cook – Connection Lost", showBackground = true, backgroundColor = 0xFF020617)
@Composable
fun PreviewCookConnectionLost() {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF020617))) {
        Box(
            modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("GUIDE DISCONNECTED", color = Color.Red.copy(alpha = 0.5f), fontSize = 12.sp, letterSpacing = 2.sp)
        }
        Text(
            "STATUS: Connection lost — reconnecting...",
            color = Color.Yellow,
            fontSize = 10.sp,
            modifier = Modifier.padding(12.dp)
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Waiting for Guide to reconnect...", color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}

// --- Cook: API failure ---
@Preview(name = "Cook – API Failure", showBackground = true, backgroundColor = 0xFF020617)
@Composable
fun PreviewCookApiFailure() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF020617)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("⚠", color = Color.Red, fontSize = 48.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Recipe fetch failed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Gemini API quota exceeded. Using offline recipes.",
                color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)) {
                Text("Retry", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
