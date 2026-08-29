package com.example.vescurus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.domain.model.IngredientDetection

@OptIn(ExperimentalTextApi::class)
@Composable
fun ProductionDetectionOverlay(
    detections: List<IngredientDetection>,
    selectedId: String?,
    onObjectSelected: (String) -> Unit
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(detections) {
                detectTapGestures { offset ->
                    // Find the smallest box that contains the tap
                    val clicked = detections
                        .filter { det ->
                            val box = det.box_2d
                            val top = box.ymin * size.height
                            val left = box.xmin * size.width
                            val bottom = box.ymax * size.height
                            val right = box.xmax * size.width
                            offset.x in left..right && offset.y in top..bottom
                        }
                        .minByOrNull { det ->
                            val box = det.box_2d
                            (box.xmax - box.xmin) * (box.ymax - box.ymin)
                        }
                    
                    clicked?.let { onObjectSelected(it.id) }
                }
            }
    ) {
        detections.forEach { detection ->
            val box = detection.box_2d
            val isSelected = detection.id == selectedId
            
            val top = box.ymin * size.height
            val left = box.xmin * size.width
            val bottom = box.ymax * size.height
            val right = box.xmax * size.width

            val baseColor = if (detection.is_supported) GoldPrimary else Color.Red
            val rectColor = if (isSelected) Color.White else baseColor

            // Draw Box
            drawRoundRect(
                color = rectColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                style = Stroke(width = if (isSelected) 4.dp.toPx() else 2.dp.toPx())
            )

            // Label
            val labelText = "${detection.label.uppercase()} ${(detection.confidence * 100).toInt()}%"
            val textLayoutResult = textMeasurer.measure(
                text = AnnotatedString(labelText),
                style = TextStyle(
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    background = rectColor
                )
            )

            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(left, (top - textLayoutResult.size.height).coerceAtLeast(0f))
            )
        }
    }
}
