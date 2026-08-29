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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.vescurus.GoldPrimary
import com.example.vescurus.debug.VescurusLogger
import com.example.vescurus.detector.GeminiIngredientDetector
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.network.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@Composable
fun GuideScreen(
    status: ConnectionStatus,
    diagnostics: String,
    detections: List<DetectionResult>,
    onDetectionsUpdated: (List<DetectionResult>) -> Unit,
    onFrameAvailable: (ByteArray) -> Unit,
    onBack: () -> Unit
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            CameraPreview(detector, onDetectionsUpdated, onFrameAvailable)
            DetectionOverlay(detections = detections)
            
            // SCANNING INDICATOR
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Camera Permission Required", color = Color.White)
            }
        }

        // Top-left Back Button
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

        // Centered Overlays
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "VESCURUS",
                color = GoldPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "GUIDE",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f))

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
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(dotColor, shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val statusLabel = when (status) {
                            ConnectionStatus.CONNECTED -> "Connected"
                            ConnectionStatus.SEARCHING -> "Listening"
                            ConnectionStatus.LOST -> "Connection lost"
                            ConnectionStatus.DISCONNECTED -> "Disconnected"
                            else -> "Idle"
                        }
                        Text(
                            text = statusLabel,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }

                    if (diagnostics.isNotEmpty()) {
                        Text(
                            text = diagnostics,
                            color = GoldPrimary.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    detector: GeminiIngredientDetector,
    onDetectionsUpdated: (List<DetectionResult>) -> Unit,
    onFrameAvailable: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()
    
    var lastAnalysisTime by remember { mutableLongStateOf(0L) }
    var lastFrameTime by remember { mutableLongStateOf(0L) }
    var isDetecting by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analyzerExecutor) { imageProxy ->
                            val currentTime = System.currentTimeMillis()
                            
                            try {
                                // USE BUILT-IN toBitmap() - MUST BE 1.3.4+
                                val originalBitmap = imageProxy.toBitmap()
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                
                                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                val uprightBitmap = Bitmap.createBitmap(
                                    originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
                                )

                                // 1. VIDEO TRANSMISSION (Low-latency stream)
                                if (currentTime - lastFrameTime >= 200) { 
                                    lastFrameTime = currentTime
                                    val out = ByteArrayOutputStream()
                                    uprightBitmap.compress(Bitmap.CompressFormat.JPEG, 35, out)
                                    onFrameAvailable(out.toByteArray())
                                }

                                // 2. AI DETECTION (Throttled inference)
                                if (currentTime - lastAnalysisTime >= 3000 && !isDetecting) {
                                    lastAnalysisTime = currentTime
                                    isDetecting = true
                                    val scaledBitmap = uprightBitmap.scaleDown(640)
                                    
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            Log.d("CV_FLOW", "Starting Gemini inference...")
                                            val results = detector.detect(uprightBitmap, scaledBitmap)
                                            launch(Dispatchers.Main) {
                                                onDetectionsUpdated(results)
                                            }
                                        } catch (e: Exception) {
                                            Log.e("CV_FLOW", "Analysis failed: ${e.message}")
                                        } finally {
                                            isDetecting = false
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CameraPreview", "Frame processing failed: ${e.message}")
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (exc: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )

    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
        }
    }
}

private fun Bitmap.scaleDown(maxDimension: Int): Bitmap {
    val width = width
    val height = height
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
