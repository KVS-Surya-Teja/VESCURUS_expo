package com.example.vescurus.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.domain.model.IngredientDetection
import com.example.vescurus.model.EGG_RECIPES
import com.example.vescurus.model.Recipe
import com.example.vescurus.model.canonicalizeIngredientLabel
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.ui.theme.AccentGreen
import com.example.vescurus.ui.theme.GoldPrimary
import com.example.vescurus.ui.theme.SurfaceCard
import com.example.vescurus.ui.theme.SurfaceDeep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull

@Composable
fun CookScreen(
    status: ConnectionStatus,
    diagnostics: String,
    detections: List<IngredientDetection>,
    frames: SharedFlow<ByteArray>,
    viewModel: CookViewModel,
    onSnapshot: () -> Unit
) {
    var isVisionHudEnabled by rememberSaveable { mutableStateOf(true) }

    // Egg-detected → drive the state machine. `derivedStateOf` keeps this
    // from firing on every list-identity change when the boolean is stable.
    val eggDetected by remember(detections) {
        derivedStateOf {
            detections.any {
                canonicalizeIngredientLabel(it.label) == "egg" &&
                    it.confidence >= 0.70f &&
                    it.is_supported
            }
        }
    }

    LaunchedEffect(eggDetected) {
        if (eggDetected && viewModel.cookingState == CookingState.IDLE) {
            viewModel.startRecipeSelection()
        }
    }

    // Decode JPEG bytes into an ImageBitmap on Dispatchers.Default — never
    // on the composition thread. Old bitmaps become GC-eligible as soon as
    // this state is replaced.
    var videoBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(frames) {
        frames
            .mapNotNull { bytes -> decodeFrame(bytes) }
            .flowOn(Dispatchers.Default)
            .collect { videoBitmap = it }
    }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceDeep)) {
        Box(
            modifier = Modifier
                .weight(if (viewModel.cookingState == CookingState.COOKING) 0.35f else 0.45f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Color.Black)
        ) {
            if (videoBitmap != null) {
                Image(
                    bitmap = videoBitmap!!,
                    contentDescription = "Guide Feed",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "WAITING FOR VIDEO...",
                        color = Color.White.copy(alpha = 0.2f),
                        fontSize = 10.sp,
                        letterSpacing = 2.sp
                    )
                }
            }

            if (isVisionHudEnabled) DetectionOverlay(detections = detections)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.4f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f)
                            )
                        )
                    )
            )

            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(12.dp).align(Alignment.TopEnd)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "AI HUD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
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

            if (status != ConnectionStatus.CONNECTED) {
                Text(
                    text = "STATUS: $diagnostics",
                    color = Color.Yellow,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
            }

            this@Column.AnimatedVisibility(
                visible = viewModel.cookingState == CookingState.COUNTDOWN,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = viewModel.countdownValue.toString(),
                        color = GoldPrimary,
                        fontSize = 100.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(if (viewModel.cookingState == CookingState.COOKING) 0.65f else 0.55f)
                .fillMaxWidth()
        ) {
            Crossfade(targetState = viewModel.cookingState, label = "InterfaceTransition") { state ->
                when (state) {
                    CookingState.IDLE -> IdleScanPrompt()
                    CookingState.RECIPE_SELECTION -> RecipeSelectionView(viewModel, detections)
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

private fun decodeFrame(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    // Downsample: the video feed is displayed at half-screen height. Decoding
    // at full resolution wastes memory and cycles.
    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    return decoded.asImageBitmap()
}

@Composable
private fun IdleScanPrompt() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = GoldPrimary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Scan ingredients to begin...", color = Color.Gray, fontSize = 14.sp)
        }
    }
}

@Composable
fun RecipeSelectionView(viewModel: CookViewModel, detections: List<IngredientDetection>) {
    val eggDetected = detections.any {
        canonicalizeIngredientLabel(it.label) == "egg" && it.confidence >= 0.7f
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = if (eggDetected) "EGG DETECTED" else "NO INGREDIENTS YET",
            color = GoldPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
        Text(
            text = if (eggDetected) "What would you like to cook?" else "Scan ingredients to see recipes...",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(EGG_RECIPES, key = { it.id }) { recipe ->
                RecipeSelectionCard(
                    recipe = recipe,
                    isSelected = viewModel.selectedRecipe?.id == recipe.id,
                    onClick = { viewModel.selectRecipe(recipe) }
                )
            }
        }

        AnimatedVisibility(visible = viewModel.selectedRecipe != null) {
            Button(
                onClick = { viewModel.startCooking() },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "START COOKING", color = Color.Black, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun RecipeSelectionCard(recipe: Recipe, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        color = if (isSelected) Color(0xFF14532D) else SurfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(2.dp, AccentGreen) else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = recipe.thumbnail),
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(recipe.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${recipe.totalTimeMs / 60000} MINS • ${recipe.calories} KCAL",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun CookingActiveWorkflow(viewModel: CookViewModel) {
    val recipe = viewModel.selectedRecipe ?: return
    var chatInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel.currentProgressMs) {
        val activeIndex = recipe.steps.indexOfFirst {
            viewModel.currentProgressMs >= it.startTimeMs && viewModel.currentProgressMs < it.endTimeMs
        }
        if (activeIndex != -1) listState.animateScrollToItem(activeIndex)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val progress = viewModel.currentProgressMs.toFloat() / recipe.totalTimeMs.toFloat()
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(recipe.name.uppercase(), color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("${(progress * 100).toInt()}%", color = GoldPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = GoldPrimary,
                trackColor = Color.DarkGray
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(recipe.steps) { _, step ->
                    val isPassed = viewModel.currentProgressMs >= step.endTimeMs
                    val isActive = viewModel.currentProgressMs >= step.startTimeMs &&
                        viewModel.currentProgressMs < step.endTimeMs

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (isActive) {
                            Modifier
                                .background(AccentGreen.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        } else Modifier
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (isActive) AccentGreen
                                    else if (isPassed) Color.Black
                                    else Color.Gray,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = step.instruction,
                            color = when {
                                isActive -> Color.White
                                isPassed -> Color.Gray.copy(alpha = 0.5f)
                                else -> Color.Gray
                            },
                            fontSize = 14.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            itemsIndexed(viewModel.messages) { _, msg -> CookChatBubble(msg) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = { Text("Ask Chef Gemini...", color = Color.Gray, fontSize = 14.sp) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceCard,
                    unfocusedContainerColor = SurfaceCard,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                trailingIcon = {
                    IconButton(onClick = {
                        if (chatInput.isNotBlank()) {
                            viewModel.sendMessage(chatInput)
                            chatInput = ""
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = GoldPrimary)
                    }
                }
            )
        }
    }
}

@Composable
private fun CookChatBubble(msg: ChatMessage) {
    val isUser = msg.sender == "User"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) Color(0xFF2563EB) else Color(0xFF334155),
            tonalElevation = 2.dp
        ) {
            Text(
                text = msg.message,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun CompletionView(onDone: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Cooking Finished!", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Great job! Ready to serve?",
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            Button(
                onClick = onDone,
                modifier = Modifier.width(200.dp).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("DONE", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}
