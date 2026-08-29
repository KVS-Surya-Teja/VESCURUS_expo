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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.domain.model.IngredientDetection

/**
 * Draws detection boxes on top of a camera preview or video feed.
 *
 * Gemini returns `[ymin, xmin, ymax, xmax]` normalized to the image it received
 * (portrait upright, aspect [sourceAspect] = width/height). PreviewView applies
 * FILL_CENTER — the on-screen aspect may differ from the source, cropping the
 * long axis. This projection compensates for that horizontal crop.
 *
 * [sourceAspect] must match the aspect (width/height) of the image the model
 * saw. For our 640x480 portrait analyzer, that's 480/640 = 0.75 (3/4).
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun DetectionOverlay(
    detections: List<IngredientDetection>,
    sourceAspect: Float = 3f / 4f
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenW = size.width
        val screenH = size.height
        val displayedWidth = screenH * sourceAspect
        val horizontalCrop = ((displayedWidth - screenW) / 2f).coerceAtLeast(0f)

        detections.forEach { detection ->
            val box = detection.box_2d

            val ymin = box.ymin.coerceIn(0f, 1f)
            val xmin = box.xmin.coerceIn(0f, 1f)
            val ymax = box.ymax.coerceIn(0f, 1f)
            val xmax = box.xmax.coerceIn(0f, 1f)
            if (xmax <= xmin || ymax <= ymin) return@forEach

            val topPx = ymin * screenH
            val leftPx = xmin * displayedWidth - horizontalCrop
            val bottomPx = ymax * screenH
            val rightPx = xmax * displayedWidth - horizontalCrop

            val visibleLeft = leftPx.coerceIn(0f, screenW)
            val visibleRight = rightPx.coerceIn(0f, screenW)
            val visibleTop = topPx.coerceIn(0f, screenH)
            val visibleBottom = bottomPx.coerceIn(0f, screenH)

            val boxWidth = visibleRight - visibleLeft
            val boxHeight = visibleBottom - visibleTop
            if (boxWidth <= 0f || boxHeight <= 0f) return@forEach

            val rectColor = if (detection.is_supported) SupportedColor else UnsupportedColor

            drawRoundRect(
                color = rectColor,
                topLeft = Offset(visibleLeft, visibleTop),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )

            val confidencePercent = (detection.confidence * 100f).coerceIn(0f, 100f).toInt()
            val labelText = "${detection.label.uppercase()} $confidencePercent%"
            val layout = textMeasurer.measure(
                text = AnnotatedString(labelText),
                style = TextStyle(
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            )

            val tagHeight = layout.size.height + 6.dp.toPx()
            val tagWidth = layout.size.width + 12.dp.toPx()
            val tagTop = (visibleTop - tagHeight).coerceAtLeast(0f)
            val tagLeft = visibleLeft.coerceAtMost((screenW - tagWidth).coerceAtLeast(0f))

            drawRoundRect(
                color = rectColor,
                topLeft = Offset(tagLeft, tagTop),
                size = Size(tagWidth, tagHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(tagLeft + 6.dp.toPx(), tagTop + 3.dp.toPx())
            )
        }
    }
}

private val SupportedColor = Color(0xFF22C55E)
private val UnsupportedColor = Color(0xFFFF5252)
