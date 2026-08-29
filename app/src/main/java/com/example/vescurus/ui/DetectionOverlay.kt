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

        // GuideScreen displays the upright camera image in PreviewView using
        // FILL_CENTER. The analyzer rotates the image before sending it to Gemini,
        // so the expected upright source aspect ratio is approximately 3:4.
        // FILL_CENTER crops the sides on a portrait phone; compensate for that
        // crop when projecting normalized Gemini coordinates onto the screen.
        val sourceAspect = 3f / 4f // upright camera frame: width / height
        val displayedWidth = screenH * sourceAspect
        val horizontalCrop = ((displayedWidth - screenW) / 2f).coerceAtLeast(0f)

        detections.forEach { detection ->
            val box = detection.box_2d

            // Normalize: handle both 0.0..1.0 and 0..1000 coordinates.
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

            // Gemini coordinates are relative to the full upright camera frame.
            // Y maps directly because the preview fills the screen vertically.
            // X needs the same horizontal crop applied by PreviewView/FILL_CENTER.
            val topPx = t * screenH
            val leftPx = l * displayedWidth - horizontalCrop
            val bottomPx = b * screenH
            val rightPx = r * displayedWidth - horizontalCrop

            // Clip to the visible preview. This prevents labels/boxes from
            // extending into the cropped-out region.
            val visibleLeft = leftPx.coerceIn(0f, screenW)
            val visibleRight = rightPx.coerceIn(0f, screenW)
            val visibleTop = topPx.coerceIn(0f, screenH)
            val visibleBottom = bottomPx.coerceIn(0f, screenH)
            val boxWidth = visibleRight - visibleLeft
            val boxHeight = visibleBottom - visibleTop

            if (boxWidth > 0f && boxHeight > 0f) {
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
                    style = Stroke(width = 3.dp.toPx())
                )

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
                val tagTop = (visibleTop - tagHeight).coerceAtLeast(0f)
                val tagLeft = visibleLeft.coerceAtMost((screenW - tagWidth).coerceAtLeast(0f))

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
    }
}
