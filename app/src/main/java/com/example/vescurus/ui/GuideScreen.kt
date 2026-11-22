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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
fun GuideContent(status: ConnectionStatus, diagnostics: String, detections: List<DetectionResult>,
    onBack: () -> Unit = {}, cameraSlot: (@Composable () -> Unit)? = null,
    onRecognize: () -> Unit = {}, recognizing: Boolean = false,
    observation: com.example.vescurus.model.Observation? = null) {
    var showPairing by rememberSaveable { mutableStateOf(false) }
    val pairingCode = remember(diagnostics) { Regex("""Pairing code:\s*(\d{6})""").find(diagnostics)?.groupValues?.get(1) }
    val connected = status == ConnectionStatus.CONNECTED
    val names = observation?.takeIf { it.status == "SUCCESS" }?.detections.orEmpty()
        .filter { it.supported && it.confidence >= .5f }.map { it.label }.distinct()
    val hint = when {
        recognizing -> "Looking at your food…"
        observation?.status == "POOR_IMAGE" -> "Move closer and use better lighting."
        observation?.status == "QUOTA_OR_RATE_LIMIT" -> "Please wait a minute, then try again."
        observation?.status == "TIMEOUT" -> "That took too long. Try again."
        observation?.status == "NETWORK_UNAVAILABLE" -> "Check internet, then try again."
        observation?.status in listOf("UNCONFIGURED", "CREDENTIAL_FAILURE", "MODEL_UNAVAILABLE", "ON_DEVICE_UNAVAILABLE") -> "Food recognition is unavailable right now."
        observation?.status == "SUCCESS" && names.isEmpty() -> "No food found. Try a clearer view."
        names.isNotEmpty() -> names.joinToString(" · ")
        observation != null -> "Couldn't recognize this view. Try again."
        else -> if(connected) "Aim at one ingredient and keep steady." else "Connect the cooking phone to begin."
    }
    if (showPairing) GuidePairingDialog(diagnostics, pairingCode) { showPairing = false }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        cameraSlot?.invoke()
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(90.dp)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent))))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(alpha = .4f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Choose how to use this phone", tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Guide camera", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Point. Hold steady. Cook does the rest.", color = Color.White.copy(alpha = .7f), fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Surface(modifier = Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter)
                .padding(16.dp).then(if (landscape) Modifier.width(300.dp) else Modifier.fillMaxWidth())
                .heightIn(max = (maxHeight - 32.dp).coerceAtLeast(120.dp)),
            color = if(connected) Color.Black.copy(alpha = .55f) else Color(0xFF0B1422).copy(alpha = .95f), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(7.dp).background(if (connected) Color(0xFF6EE7B7) else GoldPrimary, CircleShape))
                        Text(if (connected) "Cook controls this camera" else "Waiting for your cooking phone", color = Color.White, fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!connected) TextButton(onClick = { showPairing = true }) { Text("Connect", fontSize = 12.sp) }
                }
                Text(hint, color = Color(0xFFCBD5E1), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (recognizing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = GoldPrimary)
            }
        }
    }
}

@Composable
internal fun GuidePairingDialog(diagnostics: String, pairingCode: String?, onDismiss: () -> Unit) {
    val address = remember(diagnostics) { Regex("""\b(?:\d{1,3}\.){3}\d{1,3}:\d{1,5}\b""").find(diagnostics)?.value }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect your cooking phone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("On your cooking phone, tap Connect and enter these details.")
                if (pairingCode != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Pairing code", fontSize = 12.sp)
                        Text(pairingCode, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    }
                }
                if (address != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Camera address", fontSize = 12.sp)
                        Text(address.substringBeforeLast(":"), fontWeight = FontWeight.SemiBold)
                        if(address.substringAfterLast(":")!="8766") Text("Port ${address.substringAfterLast(":")}", fontSize=12.sp)
                    }
                } else {
                    Text("Still finding this phone's address. Check Wi-Fi and reopen these details.", fontSize = 12.sp)
                }
                Text("Keep both phones on the same Wi-Fi or hotspot. Pairing also keeps your cooking journal on both phones.", fontSize = 12.sp)
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
    onVideoAvailable: (com.example.vescurus.model.VideoFrame) -> Unit = {},
    remoteAnalysisRequest: Long = 0,
    remoteAnalysisRequestId: String? = null
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
    val automatic = false
    var analysisRequest by remember { mutableIntStateOf(0) }
    // Requests belong to the currently paired session and are never replayed on reopening.
    var consumedRemoteRequest by remember { mutableLongStateOf(remoteAnalysisRequest) }
    LaunchedEffect(remoteAnalysisRequest) {
        if(remoteAnalysisRequest != consumedRemoteRequest) {
            consumedRemoteRequest=remoteAnalysisRequest
            if(hasCameraPermission) analysisRequest++
        }
    }

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
                Text(text = "Use the camera to recognize your food", color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 24.dp), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Allow camera")
                }
            }
        }
        return
    }

    GuideContent(
        status = status,
        diagnostics = diagnostics,
        onRecognize = { analysisRequest++ }, recognizing = analyzing, observation = observation,
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
                analysisRequestId = remoteAnalysisRequestId,
                streamingEnabled = status == ConnectionStatus.CONNECTED,
                onAnalysisState = { analyzing=it }
            )

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
    analysisRequestId: String? = null,
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
    val requestIdentity by rememberUpdatedState(analysisRequestId)
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
                            val capturedRequestId=requestIdentity
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
                                    detections = results, rotationApplied = rotation, message = message, requestId=capturedRequestId)
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
