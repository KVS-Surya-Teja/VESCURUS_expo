package com.example.vescurus.ui.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.vescurus.model.BoundingBox
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.ui.GuideContent

// ---------------------------------------------------------------------------
// GuideScreen preview fixtures — no camera, no network, no ViewModel
// ---------------------------------------------------------------------------

@Preview(name = "Guide – Waiting for Cook", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewGuideWaiting() {
    GuideContent(
        status = ConnectionStatus.SEARCHING,
        diagnostics = "192.168.43.1:8765",
        detections = emptyList()
    )
}

@Preview(name = "Guide – Connected Idle", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewGuideConnectedIdle() {
    GuideContent(
        status = ConnectionStatus.CONNECTED,
        diagnostics = "",
        detections = emptyList()
    )
}

@Preview(name = "Guide – Egg Detected", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewGuideEggDetected() {
    GuideContent(
        status = ConnectionStatus.CONNECTED,
        diagnostics = "",
        detections = listOf(
            DetectionResult(
                label = "Egg",
                confidence = 0.91f,
                supported = true,
                box_2d = BoundingBox(top = 0.30f, left = 0.25f, bottom = 0.70f, right = 0.75f),
                recipe_class = 0
            )
        )
    )
}

@Preview(name = "Guide – Connection Lost", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewGuideLost() {
    GuideContent(
        status = ConnectionStatus.LOST,
        diagnostics = "Cook disconnected",
        detections = emptyList()
    )
}

@Preview(name = "Guide – API Failure", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PreviewGuideApiFailure() {
    GuideContent(
        status = ConnectionStatus.CONNECTED,
        diagnostics = "Gemini: quota exceeded",
        detections = emptyList()
    )
}
