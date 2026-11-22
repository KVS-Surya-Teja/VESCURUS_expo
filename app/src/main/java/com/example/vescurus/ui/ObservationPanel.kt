package com.example.vescurus.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.Observation

@Composable
fun ObservationPanel(
    observation: Observation?,
    modifier: Modifier = Modifier,
    isAnalyzing: Boolean = false
) {
    val bitmap = remember(observation?.streamId, observation?.frameId) {
        observation?.let { runCatching {
            val bytes = Base64.decode(it.jpegBase64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() }
    }
    val successful = observation?.status == "SUCCESS"
    val statusText = when {
        isAnalyzing -> "Analyzing scene…"
        observation == null -> "Ready to analyze"
        successful && bitmap == null -> "Image unavailable"
        successful && (observation?.detections?.any { it.supported && it.confidence >= .5f } == true) -> "Ingredients found"
        successful -> "No ingredients found"
        observation.status == "POOR_IMAGE" -> "Try better lighting"
        observation.status == "QUOTA_OR_RATE_LIMIT" -> "Try again shortly"
        observation.status == "TIMEOUT" -> "Analysis timed out"
        else -> "Analysis unavailable"
    }
    val accent = when {
        isAnalyzing -> GoldPrimary
        successful && bitmap != null -> Color(0xFF6EE7B7)
        observation != null -> Color(0xFFFBBF7D)
        else -> Color(0xFF94A3B8)
    }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF0B1422))
            .border(1.dp, Color.White.copy(alpha = .10f), shape)
    ) {
        if (observation != null && bitmap != null) {
            Image(bitmap.asImageBitmap(), "Analyzed ingredient image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (observation.status == "SUCCESS") DetectionOverlay(observation.detections, observation.width, observation.height, fitImage=true)
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 14.dp).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = GoldPrimary, strokeWidth = 2.dp)
                } else {
                    Box(
                        Modifier.size(30.dp).border(1.dp, GoldPrimary.copy(alpha = .55f), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(8.dp).background(GoldPrimary.copy(alpha = .75f), CircleShape))
                    }
                }
                Text(
                    if (isAnalyzing) "Looking at ingredients" else if (observation == null) "Analyze a scene on Guide" else "Try another scene",
                    color = Color(0xFFB6C3D4), fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Keep this overlay inside the image bounds; boxes still use the full Fit projection.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
        )
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (isAnalyzing && bitmap != null) {
                CircularProgressIndicator(Modifier.size(10.dp), color = accent, strokeWidth = 1.5.dp)
            } else {
                Box(Modifier.size(6.dp).background(accent, CircleShape))
            }
            Text(statusText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
