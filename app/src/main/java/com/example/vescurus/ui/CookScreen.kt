package com.example.vescurus.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.model.Recipe
import com.example.vescurus.model.canonicalizeIngredientLabel
import com.example.vescurus.network.ConnectionStatus

@Composable
fun CookScreen(
    status: ConnectionStatus,
    diagnostics: String,
    detections: List<DetectionResult>,
    latestFrame: ByteArray?,
    viewModel: CookViewModel,
    onSnapshot: () -> Unit,
    onDirectConnect: (String, Int) -> Unit = { _, _ -> }
) {
    var isVisionHudEnabled by remember { mutableStateOf(true) }
    var showDirectConnectDialog by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("8080") }

    val detectedIngredients = remember(detections) {
        detections
            .filter { it.supported && it.confidence >= 0.50f }
            .mapNotNull { canonicalizeIngredientLabel(it.label) }
            .filter { it != "Unsupported object" }
            .distinct()
    }

    LaunchedEffect(detectedIngredients) {
        if (detectedIngredients.isNotEmpty() && viewModel.cookingState == CookingState.IDLE) {
            viewModel.startRecipeSelection(detectedIngredients)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {
        // TOP SECTION — Guide Video Feed Container
        Box(
            modifier = Modifier
                .weight(if (viewModel.cookingState == CookingState.COOKING) 0.36f else 0.44f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Color.Black)
        ) {
            if (latestFrame != null) {
                val bitmap = remember(latestFrame) {
                    android.graphics.BitmapFactory.decodeByteArray(latestFrame, 0, latestFrame.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Live Guide Feed",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF020617)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = if (status == ConnectionStatus.CONNECTED) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = if (status == ConnectionStatus.CONNECTED) GoldPrimary else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (status == ConnectionStatus.CONNECTED) "WAITING FOR CAMERA FRAMES..." else "P2P TELEMETRY STANDBY",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "Point Guide phone camera at the flat-tawa surface",
                            color = Color.White.copy(alpha = 0.25f),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            if (isVisionHudEnabled) {
                DetectionOverlay(detections = detections)
            }

            // Top-Bar HUD Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection Status Chip with pulsing dot
                ConnectionStatusChip(status)

                // AI HUD Toggle
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI HUD",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = isVisionHudEnabled,
                            onCheckedChange = { isVisionHudEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = GoldPrimary,
                                checkedTrackColor = GoldPrimary.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.scale(0.6f)
                        )
                    }
                }
            }

            // Bottom floating bar: Direct IP fallback when not connected
            if (status != ConnectionStatus.CONNECTED) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .align(Alignment.BottomCenter),
                    color = Color(0xFF0F172A).copy(alpha = 0.90f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = GoldPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (diagnostics.isNotEmpty()) diagnostics.take(30) else "Searching via mDNS...",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                        Button(
                            onClick = { showDirectConnectDialog = true },
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Direct IP", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

            // Direct IP Dialog
            if (showDirectConnectDialog) {
                AlertDialog(
                    onDismissRequest = { showDirectConnectDialog = false },
                    title = {
                        Text("Direct Guide Link", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    },
                    text = {
                        Column {
                            Text(
                                "Enter IP address and port displayed on the Guide phone screen:",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedTextField(
                                value = ipInput,
                                onValueChange = { ipInput = it },
                                label = { Text("Guide IP Address", color = GoldPrimary) },
                                placeholder = { Text("e.g. 192.168.43.1", color = Color.Gray) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GoldPrimary,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { portInput = it },
                                label = { Text("Port", color = GoldPrimary) },
                                placeholder = { Text("8080", color = Color.Gray) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GoldPrimary,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val port = portInput.toIntOrNull() ?: 8080
                                onDirectConnect(ipInput.trim(), port)
                                showDirectConnectDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDirectConnectDialog = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    },
                    containerColor = Color(0xFF0F172A),
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // Animated Countdown Overlay
            this@Column.AnimatedVisibility(
                visible = viewModel.cookingState == CookingState.COUNTDOWN,
                enter = fadeIn(tween(250)) + scaleIn(tween(250, easing = EaseOutBack)),
                exit = fadeOut(tween(200)) + scaleOut(tween(200))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.80f)),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedCountdownDisplay(viewModel.countdownValue)
                }
            }
        }

        // BOTTOM SECTION — Interactive Chef Interface
        Box(
            modifier = Modifier
                .weight(if (viewModel.cookingState == CookingState.COOKING) 0.64f else 0.56f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = viewModel.cookingState,
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically(tween(300, easing = EaseOutCubic)) { it / 4 })
                        .togetherWith(fadeOut(tween(200)))
                },
                label = "CookStateTransition"
            ) { state ->
                when (state) {
                    CookingState.IDLE -> ChefStandbyView(onManualTrigger = {
                        viewModel.startRecipeSelection(listOf("Egg"))
                    })
                    CookingState.RECIPE_SELECTION -> RecipeSelectionView(viewModel, detectedIngredients)
                    CookingState.COUNTDOWN, CookingState.COOKING -> CookingActiveWorkflow(viewModel)
                    CookingState.DONE -> CompletionView(onDone = {
                        onSnapshot()
                        viewModel.markAsDone()
                    })
                }
            }
        }
    }
}

// Pulsing connection status chip
@Composable
private fun ConnectionStatusChip(status: ConnectionStatus) {
    val isConnected = status == ConnectionStatus.CONNECTED
    val isSearching = status == ConnectionStatus.SEARCHING || status == ConnectionStatus.CONNECTING

    // Pulsing alpha for the dot when active/searching
    val infiniteTransition = rememberInfiniteTransition(label = "DotPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = if (isSearching) 0.3f else 1f,
        targetValue = if (isSearching) 1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DotAlpha"
    )

    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .background(
                        when (status) {
                            ConnectionStatus.CONNECTED -> Color(0xFF22C55E)
                            ConnectionStatus.SEARCHING, ConnectionStatus.CONNECTING -> GoldPrimary
                            else -> Color(0xFFEF4444)
                        },
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when (status) {
                    ConnectionStatus.CONNECTED -> "GUIDE SYNCED"
                    ConnectionStatus.SEARCHING -> "DISCOVERING..."
                    ConnectionStatus.CONNECTING -> "CONNECTING..."
                    else -> "OFFLINE"
                },
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

// Animated countdown with spring scale pulse per number
@Composable
private fun AnimatedCountdownDisplay(value: Int) {
    var displayedValue by remember { mutableStateOf(value) }
    var trigger by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        trigger = false
        displayedValue = value
        trigger = true
    }

    val scale by animateFloatAsState(
        targetValue = if (trigger) 1f else 1.4f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "CountdownScale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "GET READY",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )
        Text(
            text = displayedValue.toString(),
            color = GoldPrimary,
            fontSize = 110.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        )
    }
}

@Composable
fun ChefStandbyView(onManualTrigger: () -> Unit) {
    val haptic = LocalHapticFeedback.current

    // Rotating progress indicator
    val infiniteTransition = rememberInfiniteTransition(label = "StandbyPulse")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "Rotation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = GoldPrimary.copy(alpha = 0.08f),
                modifier = Modifier.size(80.dp),
                border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.2f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = GoldPrimary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier
                            .size(50.dp)
                            .graphicsLayer { rotationZ = rotationAngle }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Scanning Cooking Surface",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Present eggs, oil, or ingredients in Guide's view to generate tawa recipes.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onManualTrigger()
                },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.4f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldPrimary)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Demo Flat-Tawa Recipes (Egg)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun RecipeSelectionView(viewModel: CookViewModel, detectedIngredients: List<String>) {
    val haptic = LocalHapticFeedback.current
    val availableRecipes by viewModel.availableRecipes.collectAsState()
    val isFetching = viewModel.isFetchingRecipes

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = GoldPrimary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (detectedIngredients.isNotEmpty()) "DETECTED: ${detectedIngredients.joinToString(", ").uppercase()}" else "TAWA RECIPES",
                    color = GoldPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            AnimatedContent(targetState = availableRecipes.size, label = "RecipeCount") { count ->
                Text(
                    text = "$count DISHES",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isFetching) "Generating Custom Recipes..." else "Select a Dish to Cook",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isFetching) {
            // Shimmer skeleton placeholders
            Box(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    repeat(3) {
                        ShimmerCard()
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(availableRecipes) { recipe ->
                    RecipeSelectionCard(
                        recipe = recipe,
                        isSelected = viewModel.selectedRecipe?.id == recipe.id,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.selectRecipe(recipe)
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = viewModel.selectedRecipe != null && !isFetching,
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { 40 },
            exit = fadeOut(tween(200))
        ) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.startCooking()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "START COOKING TIMELINE",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
private fun ShimmerCard() {
    val shimmerTransition = rememberInfiniteTransition(label = "Shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "ShimmerAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp),
        color = Color.White.copy(alpha = shimmerAlpha),
        shape = RoundedCornerShape(16.dp)
    ) {}
}

@Composable
fun RecipeSelectionCard(recipe: Recipe, isSelected: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.02f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "RecipeScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        color = if (isSelected) Color(0xFF14532D) else Color(0xFF0F172A),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) Color(0xFF22C55E) else Color.White.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!recipe.thumbnailUrl.isNullOrEmpty()) {
                SubcomposeAsyncImage(
                    model = recipe.thumbnailUrl,
                    contentDescription = recipe.name,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                    loading = {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = GoldPrimary, modifier = Modifier.size(20.dp))
                        }
                    },
                    error = {
                        Image(
                            painter = painterResource(id = recipe.thumbnail),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                )
            } else {
                Image(
                    painter = painterResource(id = recipe.thumbnail),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = recipe.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(text = recipe.description, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${recipe.totalTimeMs / 60000} MINS",
                        color = GoldPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " • ${recipe.calories} KCAL • ${recipe.proteinG.toInt()}g PRO",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CookingActiveWorkflow(viewModel: CookViewModel) {
    val recipe = viewModel.selectedRecipe ?: return
    var chatInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(viewModel.currentProgressMs) {
        val activeIndex = recipe.steps.indexOfFirst {
            viewModel.currentProgressMs >= it.startTimeMs && viewModel.currentProgressMs < it.endTimeMs
        }
        if (activeIndex != -1) listState.animateScrollToItem(activeIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Progress header
        val progress = viewModel.currentProgressMs.toFloat() / recipe.totalTimeMs.toFloat()
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = tween(500, easing = LinearEasing),
            label = "ProgressAnim"
        )
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = recipe.name.uppercase(),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = GoldPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = GoldPrimary,
                trackColor = Color(0xFF1E293B)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Steps scroll
        Surface(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recipe.steps) { step ->
                    val isPassed = viewModel.currentProgressMs >= step.endTimeMs
                    val isActive = viewModel.currentProgressMs >= step.startTimeMs && viewModel.currentProgressMs < step.endTimeMs

                    val dotColor by animateColorAsState(
                        targetValue = if (isActive) Color(0xFF22C55E) else if (isPassed) Color.DarkGray else Color.Gray,
                        animationSpec = tween(300),
                        label = "DotColor"
                    )
                    val textAlpha by animateFloatAsState(
                        targetValue = when {
                            isActive -> 1f
                            isPassed -> 0.35f
                            else -> 0.7f
                        },
                        animationSpec = tween(300),
                        label = "TextAlpha"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (isActive) {
                            Modifier
                                .background(Color(0xFF22C55E).copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        } else {
                            Modifier.padding(vertical = 2.dp)
                        }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(dotColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = step.instruction,
                            color = Color.White.copy(alpha = textAlpha),
                            fontSize = 13.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Chat history
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 6.dp)
        ) {
            items(viewModel.messages, key = { it.hashCode() }) { msg ->
                CookChatBubble(msg)
            }
        }

        // Chat input
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = {
                    Text(
                        text = "Ask Chef Gemini about this step...",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                },
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1E293B),
                    unfocusedContainerColor = Color(0xFF0F172A),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                trailingIcon = {
                    IconButton(onClick = {
                        if (chatInput.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.sendMessage(chatInput)
                            chatInput = ""
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = GoldPrimary)
                    }
                }
            )
        }
    }
}

@Composable
fun CookChatBubble(msg: ChatMessage) {
    val isUser = msg.sender == "User"
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(250)) + slideInHorizontally(tween(250)) { if (isUser) 60 else -60 }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 14.dp, topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 2.dp,
                    bottomEnd = if (isUser) 2.dp else 14.dp
                ),
                color = if (isUser) Color(0xFF2563EB) else Color(0xFF1E293B),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Text(
                    text = msg.message,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
fun CompletionView(onDone: () -> Unit) {
    val haptic = LocalHapticFeedback.current

    // Entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val checkScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "CheckScale"
    )

    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = CircleShape,
                color = Color(0xFF22C55E).copy(alpha = 0.15f),
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer { scaleX = checkScale; scaleY = checkScale },
                border = BorderStroke(2.dp, Color(0xFF22C55E).copy(alpha = 0.4f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("✓", color = Color(0xFF22C55E), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("Cooking Finished!", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Your flat-tawa meal is ready to plate. Nutrition logged to Track.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 28.dp)
            )

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDone()
                },
                modifier = Modifier.width(180.dp).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("DONE", color = Color.Black, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            }
        }
    }
}
