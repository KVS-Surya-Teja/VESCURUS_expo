package com.example.vescurus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.model.DetectionResult

@OptIn(ExperimentalTextApi::class)
@Composable
fun DetectionOverlay(detections: List<DetectionResult>) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenW = size.width
        val screenH = size.height

        detections.forEach { detection ->
            val box = detection.box_2d
            
            // Normalize: handle both 0.0..1.0 and 0..1000 coordinates
            var t = box.top
            var l = box.left
            var b = box.bottom
            var r = box.right

            if (t > 1.1f || l > 1.1f || b > 1.1f || r > 1.1f) {
                t /= 1000f
                l /= 1000f
                b /= 1000f
                r /= 1000f
            }

            // Project normalized coordinates to current canvas pixel bounds
            val topPx = t * screenH
            val leftPx = l * screenW
            val bottomPx = b * screenH
            val rightPx = r * screenW
            val boxWidth = rightPx - leftPx
            val boxHeight = bottomPx - topPx

            if (boxWidth > 0 && boxHeight > 0) {
                // High contrast colors: Green for valid ingredients, Red for unsupported
                val rectColor = if (detection.supported) Color(0xFF22C55E) else Color(0xFFFF5252)

                // Draw bounding box
                drawRoundRect(
                    color = rectColor,
                    topLeft = Offset(leftPx, topPx),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx())
                )

                // Draw label background and text
                val labelText = "${detection.label.uppercase()} ${(detection.confidence * 100).toInt()}%"
                val textLayoutResult = textMeasurer.measure(
                    text = AnnotatedString(labelText),
                    style = TextStyle(
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black
                    )
                )

                val tagHeight = textLayoutResult.size.height + 6.dp.toPx()
                val tagWidth = textLayoutResult.size.width + 12.dp.toPx()
                val tagTop = (topPx - tagHeight).coerceAtLeast(0f)

                drawRoundRect(
                    color = rectColor,
                    topLeft = Offset(leftPx, tagTop),
                    size = Size(tagWidth, tagHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(leftPx + 6.dp.toPx(), tagTop + 3.dp.toPx())
                )
            }
        }
    }
}
