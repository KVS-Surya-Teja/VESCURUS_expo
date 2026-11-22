package com.example.vescurus.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.vescurus.GoldPrimary
import com.example.vescurus.detector.GeminiIngredientDetector
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.network.ConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------
// GuideContent — pure UI layer, preview-safe (no camera / no network deps)
// ---------------------------------------------------------------------------

/**
 * Renders the Guide screen UI given plain state values.
 * [cameraSlot] is an optional composable that fills the background —
 * when null a solid-black placeholder is shown (used in Compose Preview).
 */
@Composable
fun GuideContent(status: ConnectionStatus,diagnostics: String,detections: List<DetectionResult>,onBack: ()->Unit={},cameraSlot: (@Composable ()->Unit)?=null) {
    var showPairing by rememberSaveable { mutableStateOf(false) }
    val pairingCode = remember(diagnostics) { Regex("""Pairing code:\s*(\d{6})""").find(diagnostics)?.groupValues?.get(1) }
    val cookContext = remember(diagnostics) {
        diagnostics.lineSequence().firstOrNull { it.startsWith("Cook: ") }?.removePrefix("Cook: ")
    }
    val connected = status == ConnectionStatus.CONNECTED
    val connectionTitle = when (status) {
        ConnectionStatus.CONNECTED -> "Cook connected"
        ConnectionStatus.SEARCHING -> "Ready to connect"
        ConnectionStatus.DISCONNECTED, ConnectionStatus.LOST -> "Cook disconnected"
        ConnectionStatus.FOUND, ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> "Connecting to Cook…"
        else -> "Starting Guide"
    }
    if (showPairing) GuidePairingDialog(diagnostics, pairingCode) { showPairing = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        cameraSlot?.invoke()
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (landscape) 0.dp else 84.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .72f), Color.Transparent)))
        )
        IconButton(onClick = onBack, modifier = Modifier.padding(12.dp).align(Alignment.TopStart).background(Color.Black.copy(alpha = .55f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Switch role", tint = Color.White)
        }
        if (!landscape) Column(Modifier.align(Alignment.TopCenter).padding(top = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("VESCURUS", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("Guide camera", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
        Surface(
            modifier = Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .padding(12.dp).then(if (landscape) Modifier.width(300.dp) else Modifier.fillMaxWidth()),
            color = Color(0xFF0B1422).copy(alpha = .94f), shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .12f))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).background(if (connected) Color(0xFF6EE7B7) else GoldPrimary, CircleShape))
                    Text(connectionTitle, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Text(
                    if (connected) cookContext ?: "Keep your ingredients in view." else "Connect your Cook phone on the same Wi-Fi or hotspot.",
                    color = Color(0xFFB6C3D4), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    if (pairingCode != null) Text("Code $pairingCode", color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    else Spacer(Modifier.width(1.dp))
                    TextButton(onClick = { showPairing = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Pairing details", color = GoldPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidePairingDialog(diagnostics: String, pairingCode: String?, onDismiss: () -> Unit) {
    val address = remember(diagnostics) { Regex("""\b(?:\d{1,3}\.){3}\d{1,3}:\d{1,5}\b""").find(diagnostics)?.value }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect your Cook phone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("On the other phone, open Cook and choose Connect Guide.")
                if (pairingCode != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Pairing code", fontSize = 12.sp)
                        Text(pairingCode, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    }
                }
                if (address != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Guide address", fontSize = 12.sp)
                        Text(address, fontWeight = FontWeight.SemiBold)
                    }
                } else if (diagnostics.isNotBlank()) {
                    Text(diagnostics, fontSize = 12.sp)
                }
                Text("Keep both phones on the same Wi-Fi or hotspot.", fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

// ---------------------------------------------------------------------------
// GuideScreen — camera-wired entry point
// ---------------------------------------------------------------------------

@Composable
fun GuideScreen(
    status: ConnectionStatus,
    diagnostics: String,
    detections: List<DetectionResult>,
    onDetectionsUpdated: (List<DetectionResult>) -> Unit,
    onFrameAvailable: (ByteArray) -> Unit,
    onBack: () -> Unit,
    observation: com.example.vescurus.model.Observation? = null,
    sessionId: String = "unpaired",
    onObservation: (com.example.vescurus.model.Observation) -> Unit = {},
    onVideoAvailable: (com.example.vescurus.model.VideoFrame) -> Unit = {}
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val detector = remember { GeminiIngredientDetector() }
    var analyzing by remember { mutableStateOf(false) }
    var automatic by rememberSaveable { mutableStateOf(false) }
    var analysisRequest by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Camera Permission Required", color = Color.White, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant Permission")
                }
            }
        }
        return
    }

    GuideContent(
        status = status,
        diagnostics = diagnostics,
        detections = emptyList(),
        onBack = onBack,
        cameraSlot = {
            CameraPreview(
                detector = detector,
                onDetectionsUpdated = onDetectionsUpdated,
                onFrameAvailable = onFrameAvailable,
                sessionId = sessionId,
                onObservation = onObservation,
                onVideoAvailable = onVideoAvailable,
                automatic = automatic,
                analysisRequest = analysisRequest,
                streamingEnabled = status == ConnectionStatus.CONNECTED,
                onAnalysisState = { analyzing=it }
            )
            val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            Column(Modifier.fillMaxWidth().padding(top = if (landscape) 8.dp else 82.dp, start = if (landscape) 76.dp else 12.dp, end = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { analysisRequest++ }, enabled = !analyzing, modifier = Modifier.testTag("analyze-scene"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color(0xFF111827),
                            disabledContainerColor = Color(0xFFE0B54F), disabledContentColor = Color(0xFF111827))
                    ) {
                        if (analyzing) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Color(0xFF111827), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (analyzing) "Analyzing…" else "Analyze scene", fontWeight = FontWeight.SemiBold)
                    }
                    Surface(color = Color(0xFF0B1422).copy(alpha = .9f), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Auto", color = Color.White, fontSize = 12.sp)
                            Switch(automatic, { automatic = it }, modifier = Modifier.testTag("auto-analysis"),
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF111827), checkedTrackColor = GoldPrimary))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ObservationPanel(observation, Modifier.width(if (landscape) 180.dp else 150.dp).height(if (landscape) 120.dp else 190.dp), isAnalyzing = analyzing)
            }
        }
    )
}

@Composable
fun CameraPreview(
    detector: GeminiIngredientDetector,
    onDetectionsUpdated: (List<DetectionResult>) -> Unit,
    onFrameAvailable: (ByteArray) -> Unit,
    sessionId: String = "unpaired",
    onObservation: (com.example.vescurus.model.Observation) -> Unit = {},
    onVideoAvailable: (com.example.vescurus.model.VideoFrame) -> Unit = {},
    automatic: Boolean = true,
    analysisRequest: Int = 0,
    streamingEnabled: Boolean = true,
    onAnalysisState: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val displayRotation = androidx.compose.ui.platform.LocalView.current.display?.rotation ?: android.view.Surface.ROTATION_0
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember(lifecycleOwner) { Executors.newSingleThreadExecutor() }
    val disposed = remember(lifecycleOwner) { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    val session by rememberUpdatedState(sessionId)
    val resultCallback by rememberUpdatedState(onObservation)
    val videoCallback by rememberUpdatedState(onVideoAvailable)
    val autoEnabled by rememberUpdatedState(automatic)
    val manualRequest by rememberUpdatedState(analysisRequest)
    val shouldStream by rememberUpdatedState(streamingEnabled)
    val analysisStateCallback by rememberUpdatedState(onAnalysisState)
    val consumedRequest=remember { AtomicInteger(0) }
    val streamId = remember(lifecycleOwner) { java.util.UUID.randomUUID().toString() }
    val sequence = remember { AtomicLong(0) }
    val busy = remember { AtomicBoolean(false) }
    val lastAnalysis = remember { AtomicLong(0) }
    val lastVideo = remember { AtomicLong(0) }
    val nextAnalysis = remember { AtomicLong(0) }
    val ownedCases = remember { mutableListOf<UseCase>() }
    AndroidView(factory = { ctx ->
        val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        providerFuture.addListener({
            if (!disposed.get()) {
                val provider = providerFuture.get()
                val preview = Preview.Builder().setTargetRotation(displayRotation).build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setTargetRotation(displayRotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                ownedCases.addAll(listOf(preview, analysis))
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val due = now >= nextAnalysis.get() && !busy.get() && ((autoEnabled && now-lastAnalysis.get()>=4000) || manualRequest>consumedRequest.get())
                        val videoDue = shouldStream && now - lastVideo.get() >= 200
                        if (!due && !videoDue) return@setAnalyzer
                        val raw = proxy.toBitmap()
                        val rotation = proxy.imageInfo.rotationDegrees
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                        val image = upright.scaleDown(640)
                        val id = sequence.incrementAndGet()
                        if (videoDue) {
                            lastVideo.set(now)
                            val output = ByteArrayOutputStream()
                            image.compress(Bitmap.CompressFormat.JPEG, 55, output)
                            videoCallback(com.example.vescurus.model.VideoFrame(session,streamId,id,now,image.width,image.height,
                                android.util.Base64.encodeToString(output.toByteArray(),android.util.Base64.NO_WRAP)))
                        }
                        if (due && busy.compareAndSet(false, true)) {
                            lastAnalysis.set(now)
                            consumedRequest.set(manualRequest)
                            val capturedSession = session
                            scope.launch(Dispatchers.IO) {
                                scope.launch(Dispatchers.Main) { analysisStateCallback(true) }
                                try {
                                val start = android.os.SystemClock.elapsedRealtime()
                                var status = "SUCCESS"
                                var message: String? = null
                                var results = emptyList<DetectionResult>()
                                Log.d("CV_FLOW", "start session=$capturedSession stream=$streamId frame=$id engine=${com.example.vescurus.di.AppModule.engineDescription}")
                                try {
                                    results = detector.detect(image, image)
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                                } catch (e: Exception) {
                                    status = (e as? com.example.vescurus.detector.InferenceFailure)?.status ?: "API_FAILURE"
                                    message = e.javaClass.simpleName
                                }
                                val completed = android.os.SystemClock.elapsedRealtime()
                                nextAnalysis.set(completed + when (status) {
                                    "QUOTA_OR_RATE_LIMIT", "MODEL_UNAVAILABLE", "UNCONFIGURED", "CREDENTIAL_FAILURE" -> 60000L
                                    "SUCCESS", "POOR_IMAGE" -> 1000L
                                    else -> 10000L
                                })
                                val output = ByteArrayOutputStream()
                                image.compress(Bitmap.CompressFormat.JPEG, 90, output)
                                val observation = com.example.vescurus.model.Observation(
                                    sessionId = capturedSession, streamId = streamId, frameId = id,
                                    captureUptimeMs = now, width = image.width, height = image.height,
                                    engine = com.example.vescurus.di.AppModule.engineDescription,
                                    status = status, inferenceMs = completed - start, sourceAgeMs = completed - now,
                                    jpegBase64 = android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP),
                                    detections = results, rotationApplied = rotation, message = message)
                                Log.d("CV_FLOW", "complete frame=$id status=$status latency=${completed-start}ms")
                                if (!disposed.get() && capturedSession == session) {
                                    com.example.vescurus.debug.VescurusLogger.retainObservation(observation)
                                    resultCallback(observation)
                                }
                                } finally { busy.set(false);image.recycle();scope.launch(Dispatchers.Main) { analysisStateCallback(false) } }
                            }
                        } else { image.recycle() }
                        if (upright !== image) upright.recycle()
                        if (raw !== upright && raw !== image) raw.recycle()

                    } catch (e: Exception) {
                        Log.e("CV_FLOW", "Camera conversion failed (${e.javaClass.simpleName})")
                    } finally { proxy.close() }
                }
                try { provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                catch (e: Exception) { Log.e("CV_FLOW", "Camera binding failed (${e.javaClass.simpleName})") }
            }
        }, ContextCompat.getMainExecutor(ctx))
        view
    }, modifier = Modifier.fillMaxSize())
    DisposableEffect(lifecycleOwner) {
        onDispose {
            disposed.set(true)
            if (providerFuture.isDone) providerFuture.get().unbind(*ownedCases.toTypedArray())
            executor.shutdown()
        }
    }
}

private fun Bitmap.scaleDown(maxDimension: Int): Bitmap {
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(width, height))
    return Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
}
