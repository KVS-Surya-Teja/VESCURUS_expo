package com.example.vescurus.ui.camera

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.ui.components.ProductionDetectionOverlay

@Composable
fun ProductionGuideScreen(
    viewModel: ProductionGuideViewModel,
    onCaptureRequested: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Here we would have the CameraPreview from the previous implementation
        // For this milestone, we focus on the Overlay and Interaction logic
        
        if (state is ProductionGuideViewModel.UiState.Success) {
            val data = (state as ProductionGuideViewModel.UiState.Success).data
            ProductionDetectionOverlay(
                detections = data.detections,
                selectedId = viewModel.selectedObjectId,
                onObjectSelected = { viewModel.selectObject(it) }
            )
        }

        // Action HUD
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Info Card for selected object
                AnimatedVisibility(visible = viewModel.selectedObjectId != null) {
                    SelectedObjectCard(viewModel, state)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Main Capture Button
                FloatingActionButton(
                    onClick = onCaptureRequested,
                    containerColor = GoldPrimary,
                    contentColor = Color.Black,
                    shape = RoundedCornerShape(50)
                ) {
                    if (state is ProductionGuideViewModel.UiState.Processing) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.Camera, contentDescription = "Capture")
                    }
                }
            }
        }
        
        // Error Snackbar
        if (state is ProductionGuideViewModel.UiState.Error) {
            val message = (state as ProductionGuideViewModel.UiState.Error).message
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun SelectedObjectCard(viewModel: ProductionGuideViewModel, state: ProductionGuideViewModel.UiState) {
    val id = viewModel.selectedObjectId ?: return
    if (state !is ProductionGuideViewModel.UiState.Success) return
    
    val detection = state.data.detections.find { it.id == id } ?: return

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "DETECTED INGREDIENT", color = GoldPrimary, fontSize = 10.sp)
                    Text(text = detection.label, color = Color.White, fontSize = 18.sp)
                }
                
                Button(
                    onClick = { viewModel.clearSelection() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Confirm")
                }
            }
            
            if (detection.alternatives.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Alternatives:", color = Color.Gray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    detection.alternatives.forEach { alt ->
                        SuggestionChip(
                            onClick = { /* In production: update label */ },
                            label = { Text("${alt.label} (${(alt.confidence*100).toInt()}%)") }
                        )
                    }
                }
            }
        }
    }
}
