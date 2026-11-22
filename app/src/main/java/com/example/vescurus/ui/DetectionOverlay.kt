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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.model.DetectionResult

@OptIn(ExperimentalTextApi::class)
@Composable
fun DetectionOverlay(detections: List<DetectionResult>, imageWidth: Int = 3, imageHeight: Int = 4, fitImage: Boolean = false) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenW = size.width
        val screenH = size.height

        val projection=com.example.vescurus.model.imageProjection(imageWidth,imageHeight,screenW,screenH,fitImage)
        val displayedWidth=projection.width
        val displayedHeight=projection.height
        val horizontalCrop=-projection.offsetX
        val verticalCrop=-projection.offsetY

        val labelDraws = mutableListOf<() -> Unit>()
        val labelled = mutableSetOf<String>()
        val labelAreas = mutableListOf<androidx.compose.ui.geometry.Rect>()
        detections.forEach { detection ->
            val box = detection.box_2d

            // Accept both our internal 0..1 contract and Gemini's 0..1000
            // coordinate convention.
            val divisor = if (
                box.top > 1.1f || box.left > 1.1f ||
                box.bottom > 1.1f || box.right > 1.1f
            ) 1000f else 1f

            val top = (box.top / divisor).coerceIn(0f, 1f)
            val left = (box.left / divisor).coerceIn(0f, 1f)
            val bottom = (box.bottom / divisor).coerceIn(0f, 1f)
            val right = (box.right / divisor).coerceIn(0f, 1f)

            if (right <= left || bottom <= top) return@forEach

            val topPx = top * displayedHeight - verticalCrop
            val leftPx = left * displayedWidth - horizontalCrop
            val bottomPx = bottom * displayedHeight - verticalCrop
            val rightPx = right * displayedWidth - horizontalCrop

            val visibleLeft = leftPx.coerceIn(0f, screenW)
            val visibleRight = rightPx.coerceIn(0f, screenW)
            val visibleTop = topPx.coerceIn(0f, screenH)
            val visibleBottom = bottomPx.coerceIn(0f, screenH)

            val boxWidth = visibleRight - visibleLeft
            val boxHeight = visibleBottom - visibleTop

            if (boxWidth <= 0f || boxHeight <= 0f) return@forEach

            val rectColor = if (detection.supported) {
                Color(0xFF22C55E)
            } else {
                Color(0xFFFF5252)
            }

            drawRoundRect(
                color = rectColor,
                topLeft = Offset(visibleLeft, visibleTop),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Repeated slices still have individual boxes; name each identity once.
            if (!labelled.add(detection.label.lowercase(java.util.Locale.ROOT))) return@forEach
            val labelText = detection.label
            val textLayoutResult = textMeasurer.measure(
                text = AnnotatedString(labelText),
                style = TextStyle(
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (screenW - 12.dp.toPx()).toInt().coerceAtLeast(1))
            )

            val tagHeight = textLayoutResult.size.height + 6.dp.toPx()
            val tagWidth = textLayoutResult.size.width + 12.dp.toPx()
            var tagTop = (visibleTop - tagHeight).coerceAtLeast(0f)
            val tagLeft = visibleLeft.coerceAtMost(
                (screenW - tagWidth).coerceAtLeast(0f)
            )

            repeat(labelAreas.size) {
                val candidate = androidx.compose.ui.geometry.Rect(tagLeft, tagTop, tagLeft + tagWidth, tagTop + tagHeight)
                labelAreas.firstOrNull { candidate.overlaps(it) }?.let { tagTop = it.bottom + 2.dp.toPx() }
            }
            // Preserve boxes if a very small preview has no room for another name.
            if (tagTop + tagHeight > screenH) return@forEach
            labelAreas.add(androidx.compose.ui.geometry.Rect(tagLeft, tagTop, tagLeft + tagWidth, tagTop + tagHeight))
            labelDraws.add {
                drawRoundRect(
                    color = rectColor,
                    topLeft = Offset(tagLeft, tagTop),
                    size = Size(tagWidth, tagHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(tagLeft + 6.dp.toPx(), tagTop + 3.dp.toPx())
                )
            }
        }
        // Draw names after all outlines so a later slice cannot paint over a name.
        labelDraws.forEach { it() }
    }
}
