package com.example.vescurus.ui.snapshot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vescurus.domain.usecase.AnalyzeImageUseCase
import com.example.vescurus.model.canonicalizeIngredientLabel
import com.example.vescurus.ui.theme.AccentGreen
import com.example.vescurus.ui.theme.GoldPrimary
import com.example.vescurus.ui.theme.SurfaceCard
import com.example.vescurus.ui.theme.SurfaceDeep

/**
 * Single-device capture → analyze → review flow. Fulfills spec items 1–9.
 */
@Composable
fun SnapshotScreen(
    vm: SnapshotViewModel = viewModel(factory = SnapshotViewModel.Factory),
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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

    Box(modifier = Modifier.fillMaxSize().background(SurfaceDeep)) {
        when (val s = state) {
            SnapshotViewModel.State.Idle -> {
                if (hasCameraPermission) {
                    CameraCaptureView(onCaptured = vm::onCapture)
                } else {
                    CameraPermissionCta(
                        onGrant = { launcher.launch(Manifest.permission.CAMERA) },
                        onOpenSettings = { openAppSettings(context) }
                    )
                }
            }
            is SnapshotViewModel.State.Analyzing -> AnalyzingView(s)
            is SnapshotViewModel.State.Reviewing -> ReviewView(s, vm)
            is SnapshotViewModel.State.ErrorState -> ErrorView(s, onRetry = vm::reset)
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Capture (spec items 1, 2 partially — quality is checked in the use case).
// -----------------------------------------------------------------------------

@Composable
private fun CameraCaptureView(onCaptured: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Use the main executor for takePicture callbacks so we can safely
    // mutate Compose state (isCapturing) from onCaptureSuccess. Using a
    // background executor was crashing on newer Compose versions because
    // state writes off the main thread throw.
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                        imageCapture = capture
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, start = 72.dp, end = 24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text("SNAPSHOT", color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text(
                "Frame ingredients and tap the capture button.",
                color = Color.White,
                fontSize = 14.sp
            )
        }

        FloatingActionButton(
            onClick = {
                val capture = imageCapture ?: return@FloatingActionButton
                if (isCapturing) return@FloatingActionButton
                isCapturing = true
                capture.takePicture(
                    mainExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            try {
                                val raw = imageProxy.toBitmap()
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val bitmap = if (rotation == 0) raw else rotateBitmap(raw, rotation)
                                isCapturing = false
                                onCaptured(bitmap)
                            } catch (e: Exception) {
                                Log.e(TAG, "onCaptureSuccess processing failed", e)
                                isCapturing = false
                            } finally {
                                imageProxy.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Image capture failed", exception)
                            isCapturing = false
                        }
                    }
                )
            },
            containerColor = GoldPrimary,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .size(72.dp)
        ) {
            if (isCapturing) {
                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(28.dp))
            } else {
                Icon(Icons.Default.Camera, contentDescription = "Capture", modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun AnalyzingView(state: SnapshotViewModel.State.Analyzing) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = "Captured image",
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .aspectRatio(state.sourceAspect)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = GoldPrimary)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Analyzing…",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ErrorView(state: SnapshotViewModel.State.ErrorState, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            val heading = when (state.reason) {
                AnalyzeImageUseCase.Reason.QUALITY -> "Image quality too low"
                AnalyzeImageUseCase.Reason.TIMEOUT -> "Detector timed out"
                AnalyzeImageUseCase.Reason.RATE_LIMIT -> "Rate limited"
                AnalyzeImageUseCase.Reason.NETWORK -> "Network unreachable"
                AnalyzeImageUseCase.Reason.PARSE -> "Detector returned bad JSON"
                AnalyzeImageUseCase.Reason.UNKNOWN -> "Something went wrong"
            }
            Text(heading, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(state.message, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Try another snapshot", color = Color.Black)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Review (spec items 3–9).
// -----------------------------------------------------------------------------

@Composable
private fun ReviewView(state: SnapshotViewModel.State.Reviewing, vm: SnapshotViewModel) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> state.detections.find { it.id == id } }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceDeep)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.sourceAspect)
                    .align(Alignment.Center)
            ) {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = "Captured image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                InteractiveDetectionOverlay(
                    detections = state.detections,
                    selectedId = selectedId,
                    onSelect = { id -> selectedId = if (selectedId == id) null else id }
                )
            }
        }

        if (selected != null) {
            DetectionReviewCard(
                detection = selected,
                onAccept = { vm.onAccept(selected.id); selectedId = null },
                onReject = { vm.onReject(selected.id); selectedId = null },
                onEditLabel = { newLabel ->
                    vm.onEditLabel(selected.id, newLabel)
                    selectedId = null
                },
                onDismiss = { selectedId = null }
            )
        } else {
            DetectionListPanel(state, vm, onSelect = { selectedId = it })
        }
    }
}

@Composable
private fun DetectionListPanel(
    state: SnapshotViewModel.State.Reviewing,
    vm: SnapshotViewModel,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "DETECTIONS",
                    color = GoldPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                val accepted = state.acceptedDetections.size
                Text(
                    "${state.detections.size} found · $accepted confirmed",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(onClick = vm::reset) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = GoldPrimary)
                Spacer(modifier = Modifier.width(4.dp))
                Text("New snapshot", color = GoldPrimary)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (state.detections.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No ingredients found. Try another shot.",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.detections, key = { it.id }) { det ->
                    DetectionRow(det, onClick = { onSelect(det.id) })
                }
            }
        }
    }
}

@Composable
private fun DetectionRow(
    det: SnapshotViewModel.ReviewableDetection,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(det.id) { detectTapGestures(onTap = { onClick() }) },
        color = SurfaceCard,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(det.status)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(det.currentLabel.uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${(det.confidence * 100).toInt()}% · box [%.2f, %.2f, %.2f, %.2f]"
                        .format(det.bounds.ymin, det.bounds.xmin, det.bounds.ymax, det.bounds.xmax),
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
            Text("Review", color = GoldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusBadge(status: SnapshotViewModel.ReviewStatus) {
    val (label, color) = when (status) {
        SnapshotViewModel.ReviewStatus.PENDING -> "Pending" to Color(0xFFEAB308)
        SnapshotViewModel.ReviewStatus.ACCEPTED -> "Accepted" to AccentGreen
        SnapshotViewModel.ReviewStatus.REJECTED -> "Rejected" to Color(0xFFEF4444)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun DetectionReviewCard(
    detection: SnapshotViewModel.ReviewableDetection,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEditLabel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showLabelPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceCard,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        detection.currentLabel.uppercase(),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${(detection.confidence * 100).toInt()}% confidence",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.Gray)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Accept", color = Color.Black)
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reject")
                }
                OutlinedButton(
                    onClick = { showLabelPicker = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldPrimary)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
            }

            if (detection.currentLabel != detection.originalLabel) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Edited from “${detection.originalLabel}”",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            if (showLabelPicker) {
                LabelPickerDialog(
                    currentLabel = detection.currentLabel,
                    onPick = {
                        showLabelPicker = false
                        onEditLabel(it)
                    },
                    onCancel = { showLabelPicker = false }
                )
            }
        }
    }
}

@Composable
private fun LabelPickerDialog(
    currentLabel: String,
    onPick: (String) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Choose an ingredient") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(CANONICAL_LABELS, key = { it }) { label ->
                    Surface(
                        color = if (label == currentLabel) GoldPrimary.copy(alpha = 0.2f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(label) { detectTapGestures(onTap = { onPick(label) }) }
                    ) {
                        Text(
                            label,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        containerColor = SurfaceCard
    )
}

// -----------------------------------------------------------------------------
// Interactive overlay — draws boxes, taps forward to selection.
// -----------------------------------------------------------------------------

@OptIn(ExperimentalTextApi::class)
@Composable
private fun InteractiveDetectionOverlay(
    detections: List<SnapshotViewModel.ReviewableDetection>,
    selectedId: String?,
    onSelect: (String) -> Unit
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(detections.map { it.id }) {
                detectTapGestures { offset ->
                    val hit = detections
                        .filter { det ->
                            val b = det.bounds
                            val left = b.xmin * size.width
                            val right = b.xmax * size.width
                            val top = b.ymin * size.height
                            val bottom = b.ymax * size.height
                            offset.x in left..right && offset.y in top..bottom
                        }
                        .minByOrNull { (it.bounds.xmax - it.bounds.xmin) * (it.bounds.ymax - it.bounds.ymin) }
                    hit?.let { onSelect(it.id) }
                }
            }
    ) {
        detections.forEach { det ->
            val b = det.bounds
            val left = b.xmin * size.width
            val right = b.xmax * size.width
            val top = b.ymin * size.height
            val bottom = b.ymax * size.height
            val w = right - left
            val h = bottom - top
            if (w <= 0f || h <= 0f) return@forEach

            val color = when (det.status) {
                SnapshotViewModel.ReviewStatus.ACCEPTED -> AcceptedColor
                SnapshotViewModel.ReviewStatus.REJECTED -> RejectedColor
                SnapshotViewModel.ReviewStatus.PENDING -> PendingColor
            }
            val strokeWidth = if (det.id == selectedId) 5.dp.toPx() else 3.dp.toPx()

            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(w, h),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = strokeWidth)
            )

            val labelText = "${det.currentLabel.uppercase()} ${(det.confidence * 100).toInt()}%"
            val layout = textMeasurer.measure(
                text = AnnotatedString(labelText),
                style = TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black)
            )
            val tagH = layout.size.height + 6.dp.toPx()
            val tagW = layout.size.width + 12.dp.toPx()
            val tagTop = (top - tagH).coerceAtLeast(0f)
            val tagLeft = left.coerceAtMost((size.width - tagW).coerceAtLeast(0f))
            drawRoundRect(
                color = color,
                topLeft = Offset(tagLeft, tagTop),
                size = Size(tagW, tagH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(tagLeft + 6.dp.toPx(), tagTop + 3.dp.toPx())
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Helpers.
// -----------------------------------------------------------------------------

@Composable
private fun CameraPermissionCta(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Camera permission required",
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
            ) { Text("Grant camera access", color = Color.Black) }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onOpenSettings) { Text("Open app settings", color = Color.White) }
        }
    }
}

private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    if (rotated !== src) src.recycle()
    return rotated
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

private const val TAG = "SnapshotScreen"

private val CANONICAL_LABELS = listOf(
    "egg", "onion", "green chili", "tomato", "banana", "flour",
    "salt", "black pepper", "oil", "butter", "milk",
    "turmeric powder", "red chilli powder"
)

private val PendingColor = Color(0xFFEAB308)
private val AcceptedColor = Color(0xFF22C55E)
private val RejectedColor = Color(0xFFEF4444)

@Suppress("unused")
private fun unusedCanonicalize(label: String): String? = canonicalizeIngredientLabel(label)
