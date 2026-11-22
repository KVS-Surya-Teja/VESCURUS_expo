package com.example.vescurus.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.model.Observation

@Composable
fun ObservationPanel(observation: Observation?, modifier: Modifier = Modifier) {
    val bitmap = remember(observation?.streamId, observation?.frameId) {
        observation?.let { runCatching {
            val bytes = Base64.decode(it.jpegBase64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() }
    }
    Box(modifier.clipToBounds().background(Color.Black)) {
        if (observation != null && bitmap != null) {
            Image(bitmap.asImageBitmap(), "Analyzed snapshot frame ${observation.frameId}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            if (observation.status == "SUCCESS") DetectionOverlay(observation.detections, observation.width, observation.height, fitImage=true)
        }
        Text(observation?.let { "Snapshot #${it.frameId} | ${it.status.lowercase().replace('_',' ')} | ${it.inferenceMs} ms" }
            ?: "No current analyzed snapshot", color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .8f)).padding(6.dp))
    }
}
