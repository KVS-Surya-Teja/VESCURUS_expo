package com.example.vescurus.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.vescurus.VescurusApplication
import com.example.vescurus.detector.GeminiIngredientDetector
import com.example.vescurus.domain.model.BoundingBox
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.ui.theme.GoldPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Composable
fun GuideScreen(
    status: ConnectionStatus,
    diagnostics: String,
    detections: List<IngredientDetection>,
    onDetectionsUpdated: (List<IngredientDetection>) -> Unit,
    onFrameAvailable: (ByteArray) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission state is checked on every ON_RESUME so a user who revokes
    // CAMERA in Settings and comes back sees the recovery CTA — not a
    // black screen from a rebind that silently threw.
    var hasCameraPermission by remember { mutableStateOf(context.hasCameraPermission()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = context.hasCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    val detector = remember(context) {
        val container = (context.applicationContext as VescurusApplication).container
        GeminiIngredientDetector(container.analyzeImageUseCase)
    }

    // Scripted "found the egg" behavior for the demo: after 5 s of the
    // camera streaming, emit a green center-square detection and keep
    // refreshing it every 1.5 s so both phones show a persistent box.
    // Real Gemini detection may run in parallel; whichever emits most
    // recently wins the shared detection state.
    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        delay(5_000L)
        while (true) {
            onDetectionsUpdated(listOf(MOCK_CENTER_EGG))
            delay(1_500L)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            CameraPreview(detector, onDetectionsUpdated, onFrameAvailable)
            DetectionOverlay(detections = detections)

            if (detections.isEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(250.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = "CENTER INGREDIENT HERE",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    )
                }
            }
        } else {
            CameraPermissionCta(
                onGrant = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) }
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VESCURUS", color = GoldPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("GUIDE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)

            Spacer(modifier = Modifier.weight(1f))

            GuideStatusCard(status = status, diagnostics = diagnostics)
        }
    }
}

@Composable
private fun CameraPermissionCta(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Camera permission required",
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
            ) {
                Text("Grant camera access", color = Color.Black)
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onOpenSettings) {
                Text("Open app settings", color = Color.White)
            }
        }
    }
}

@Composable
private fun GuideStatusCard(status: ConnectionStatus, diagnostics: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val guideText = when (status) {
                ConnectionStatus.CONNECTED -> "Guiding: COOK-01"
                ConnectionStatus.SEARCHING -> "Waiting for Cook..."
                ConnectionStatus.DISCONNECTED, ConnectionStatus.LOST -> "Cook disconnected"
                else -> "Initializing..."
            }
            Text(text = guideText, color = Color.White, fontSize = 16.sp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                val dotColor = when (status) {
                    ConnectionStatus.CONNECTED -> Color.Green
                    ConnectionStatus.LOST, ConnectionStatus.DISCONNECTED -> Color.Red
                    else -> Color.Gray
                }
                Box(modifier = Modifier.size(8.dp).background(dotColor, shape = CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                val statusLabel = when (status) {
                    ConnectionStatus.CONNECTED -> "Connected"
                    ConnectionStatus.SEARCHING -> "Listening"
                    ConnectionStatus.LOST -> "Connection lost"
                    ConnectionStatus.DISCONNECTED -> "Disconnected"
                    else -> "Idle"
                }
                Text(text = statusLabel, color = Color.White, fontSize = 14.sp)
            }

            if (diagnostics.isNotEmpty()) {
                Text(
                    text = diagnostics,
                    color = GoldPrimary.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CameraPreview(
    detector: GeminiIngredientDetector,
    onDetectionsUpdated: (List<IngredientDetection>) -> Unit,
    onFrameAvailable: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()

    // Gemini inference lives in the cloud. This interval balances "looks live"
    // with "doesn't hammer the API". A successful detection stays visible
    // between calls; two consecutive misses clear it.
    val detectionIntervalMs = 1200L
    val frameIntervalMs = 200L
    val lastAnalysisTime = remember { AtomicLong(0L) }
    val isDetecting = remember { AtomicBoolean(false) }
    val consecutiveEmptyResults = remember { AtomicInteger(0) }
    val lastFrameTime = remember { AtomicLong(0L) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Explicit 480x640 analyzer target — otherwise CameraX picks
                // "largest analysis size" per device, which on some phones is
                // 1080p YUV and burns ~4 MB per frame.
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(480, 640))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                            val currentTime = System.currentTimeMillis()
                            var uprightBitmap: Bitmap? = null
                            var scaledBitmap: Bitmap? = null
                            try {
                                val originalBitmap = imageProxy.toBitmap()
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                uprightBitmap = if (rotation == 0) {
                                    originalBitmap
                                } else {
                                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                    Bitmap.createBitmap(
                                        originalBitmap,
                                        0,
                                        0,
                                        originalBitmap.width,
                                        originalBitmap.height,
                                        matrix,
                                        true
                                    ).also { rotated ->
                                        if (rotated !== originalBitmap) originalBitmap.recycle()
                                    }
                                }

                                if (currentTime - lastFrameTime.get() >= frameIntervalMs) {
                                    lastFrameTime.set(currentTime)
                                    ByteArrayOutputStream(24 * 1024).use { out ->
                                        uprightBitmap.compress(Bitmap.CompressFormat.JPEG, 35, out)
                                        onFrameAvailable(out.toByteArray())
                                    }
                                }

                                val last = lastAnalysisTime.get()
                                val due = last == 0L || currentTime - last >= detectionIntervalMs
                                if (due && isDetecting.compareAndSet(false, true)) {
                                    lastAnalysisTime.set(currentTime)
                                    // The scaled buffer is owned by the coroutine — it must
                                    // outlive `imageProxy.close()` below, so we DO NOT recycle
                                    // it here. The coroutine recycles both after inference.
                                    scaledBitmap = uprightBitmap.scaleDown(SCALED_MAX_DIM)
                                    val rawForInference = uprightBitmap
                                    val scaledForInference = scaledBitmap
                                    // Clear locals so the finally block does not recycle them.
                                    uprightBitmap = null
                                    scaledBitmap = null

                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val results = detector.detect(rawForInference, scaledForInference!!)
                                            launch(Dispatchers.Main) {
                                                if (results.isNotEmpty()) {
                                                    consecutiveEmptyResults.set(0)
                                                    onDetectionsUpdated(results)
                                                } else {
                                                    val misses = consecutiveEmptyResults.incrementAndGet()
                                                    if (misses >= 2) onDetectionsUpdated(emptyList())
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Inference failed", e)
                                        } finally {
                                            isDetecting.set(false)
                                            rawForInference.recycle()
                                            scaledForInference?.recycle()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Frame processing failed", e)
                            } finally {
                                uprightBitmap?.recycle()
                                scaledBitmap?.recycle()
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Use case binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }
}

private fun Bitmap.scaleDown(maxDimension: Int): Bitmap {
    val newWidth: Int
    val newHeight: Int
    if (width > height) {
        newWidth = maxDimension
        newHeight = (height * maxDimension) / width
    } else {
        newHeight = maxDimension
        newWidth = (width * maxDimension) / height
    }
    return Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private const val TAG = "GuideScreen"
private const val SCALED_MAX_DIM = 640

/** Scripted center-square "egg" detection used for the demo fallback. */
private val MOCK_CENTER_EGG = IngredientDetection(
    id = "egg-demo-1",
    label = "egg",
    confidence = 0.99f,
    box_2d = BoundingBox(ymin = 0.35f, xmin = 0.35f, ymax = 0.65f, xmax = 0.65f),
    alternatives = emptyList(),
    is_supported = true
)
