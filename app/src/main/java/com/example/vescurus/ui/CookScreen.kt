package com.example.vescurus.ui

import android.graphics.BitmapFactory
import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.vescurus.GoldPrimary
import com.example.vescurus.R
import com.example.vescurus.model.*
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.session.*
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

private val KitchenBackground = Color(0xFF020617)
private val SurfaceNavy = Color(0xFF0F172A)
private val Muted = Color(0xFF94A3B8)
private val SoftWhite = Color(0xFFE2E8F0)
private val SavedGreen = Color(0xFF86EFAC)

private data class EntryEditor(
    val id: String? = null,
    val input: String = "",
    val amount: String = "",
    val unit: QuantityUnit = QuantityUnit.GRAMS,
    val vision: VisionEvidence? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val condition: String = ""
)

@Composable
fun SessionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = SurfaceNavy,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .07f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            content()
        }
    }
}

/**
 * Presentation only: CookViewModel still owns every ingredient, recipe and session transition.
 * Video is collected inside LiveGuideFrame; analyzed boxes only accompany their matched snapshot.
 */
@Composable
fun CookScreen(
    status: ConnectionStatus,
    diagnostics: String,
    detections: List<DetectionResult>,
    latestFrame: ByteArray?,
    viewModel: CookViewModel,
    onSnapshot: () -> Unit,
    onDirectConnect: (String, Int) -> Unit = { _, _ -> },
    observation: Observation? = null,
    latestFrameFlow: StateFlow<ByteArray?>? = null
) {
    var editor by remember { mutableStateOf<EntryEditor?>(null) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var cameraCollapsed by rememberSaveable { mutableStateOf(false) }
    var analyzed by rememberSaveable { mutableStateOf(false) }
    var workspace by rememberSaveable { mutableIntStateOf(0) }
    var showGuides by rememberSaveable { mutableStateOf(false) }
    var timerDialog by rememberSaveable { mutableStateOf(false) }
    var chat by rememberSaveable { mutableStateOf("") }
    var ingredientReviewed by rememberSaveable { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var dictationSession by rememberSaveable { mutableStateOf<String?>(null) }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && dictationSession == viewModel.session.id) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { spoken ->
                chat = if (chat.isBlank()) spoken else "$chat $spoken"
            }
        }
    }
    fun dictate() {
        dictationSession = viewModel.session.id
        try {
            dictation.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask VESCURUS")
            })
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Voice input is unavailable on this phone. You can still type your question.", Toast.LENGTH_LONG).show()
        }
    }
    val assistantScroll = rememberLazyListState()
    val ingredientScroll = rememberLazyListState()
    val recipeScroll = rememberLazyListState()
    val session = viewModel.session
    val recipe = session.recipe
    val entries = session.entries.filter { it.status != EntryStatus.UNDONE }
    val observations = remember(detections) {
        detections.filter { it.supported && it.confidence >= .5f }
            .mapNotNull { canonicalizeIngredientLabel(it.label) }
            .filter { it != "Unsupported object" }
            .distinct()
    }

    fun reviewObservation(name: String) {
        val proposedPreparation = observation?.detections?.firstOrNull {
            canonicalizeIngredientLabel(it.label)?.equals(name, true) == true
        }?.preparation.orEmpty()
        editor = EntryEditor(
            input = name,
            vision = observation?.let { VisionEvidence(it.sessionId, it.streamId, it.frameId, name) },
            condition = proposedPreparation
        )
    }
    fun identifyIngredient() {
        ingredientReviewed = false
        workspace = 0
        onSnapshot()
    }
    fun sendQuestion() {
        val question = chat.trim()
        if (question.isNotEmpty() && !viewModel.isGenerating && !viewModel.isLoading) {
            viewModel.sendMessage(question)
            chat = ""
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    fun chooseRecipe(selected: Recipe) {
        viewModel.selectRecipe(selected)
        workspace = 0
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LaunchedEffect(session.id) { chat = ""; dictationSession = null; ingredientReviewed = false }
    LaunchedEffect(observation?.sessionId, observation?.streamId, observation?.frameId) {
        observation?.let { viewModel.receiveIngredientObservation(it) }
    }
    LaunchedEffect(session.id, recipe?.id, viewModel.cookingState, session.step) {
        assistantScroll.scrollToItem(0)
    }
    LaunchedEffect(viewModel.messages.size, viewModel.isGenerating) {
        if (viewModel.messages.isNotEmpty() && workspace == 0) {
            withFrameNanos { }
            assistantScroll.animateScrollToItem(
                (assistantScroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            )
        }
    }

    if (connect) {
        GuideConnectionDialog(
            onDismiss = { connect = false },
            onConnect = { host, port -> onDirectConnect(host, port); connect = false }
        )
    }
    editor?.let { entry ->
        IngredientDialog(
            editor = entry,
            onDismiss = { editor = null },
            onSave = { name, amount, unit, condition ->
                if (entry.id == null) {
                    viewModel.addIngredient(
                        name, amount, unit,
                        entry.vision?.takeIf { it.label.equals(name.trim(), true) },
                        entry.requestId, condition
                    )
                } else {
                    viewModel.correctEntry(entry.id, name, amount, unit, condition)
                }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                ingredientReviewed = true
                editor = null
            }
        )
    }
    if (timerDialog) {
        StepTimerDialog(
            onDismiss = { timerDialog = false },
            onSave = { seconds -> viewModel.setStepTimer(seconds); timerDialog = false }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(KitchenBackground).imePadding()) {
        val availableHeight = maxHeight
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val tightWorkspace = availableHeight < 360.dp
        // Keep the composer and current task usable in landscape and when the keyboard opens.
        val compactCamera = cameraCollapsed || keyboardVisible || availableHeight < 500.dp
        val cameraHeight by animateDpAsState(
            targetValue = if (compactCamera) 52.dp else (availableHeight * if (viewModel.cookingState == CookingState.COOKING) .28f else .34f).coerceIn(180.dp, 280.dp),
            animationSpec = tween(180),
            label = "camera-height"
        )

        Column(Modifier.fillMaxSize()) {
            if (!viewModel.isDemo && !(keyboardVisible && tightWorkspace)) {
                GuideCameraHero(
                    status = status,
                    diagnostics = diagnostics,
                    observation = observation,
                    analyzed = analyzed,
                    collapsed = compactCamera,
                    latestFrameFlow = latestFrameFlow,
                    latestFrame = latestFrame,
                    onModeChange = { analyzed = it },
                    onConnect = { connect = true },
                    onRecognize = ::identifyIngredient,
                    recognizing = viewModel.recognitionInProgress,
                    primaryRecognition = recipe == null && !ingredientReviewed && observations.isEmpty(),
                    onToggleSize = { cameraCollapsed = !cameraCollapsed },
                    canExpand = !keyboardVisible && availableHeight >= 500.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(cameraHeight)
                )
            }

            if (!tightWorkspace && (recipe == null || workspace != 0)) Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (viewModel.isDemo) {
                        Text("PRACTICE · DEMO", color = GoldPrimary, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                    Text(
                        when {
                            session.completedAt != null -> "Your cooking record"
                            workspace == 1 -> "Your ingredients"
                            workspace == 2 -> "Choose a recipe"
                            else -> "What are we cooking?"
                        },
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("session-title")
                    )
                }
                val saveText = when {
                    viewModel.isLoading -> "Loading…"
                    viewModel.storageMessage != null -> "Not saved"
                    viewModel.isSaving -> "Saving…"
                    else -> "Saved"
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!viewModel.isLoading && !viewModel.isSaving && viewModel.storageMessage == null) {
                        Icon(Icons.Default.CheckCircle, null, tint = SavedGreen, modifier = Modifier.size(13.dp))
                    }
                    Text(saveText, color = if (viewModel.storageMessage != null) GoldPrimary else Muted,
                        fontSize = 10.sp, modifier = Modifier.testTag("save-status"))
                }
            }

            if (tightWorkspace && viewModel.isDemo) {
                Text("PRACTICE · DEMO", color = GoldPrimary, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp))
            }
            if (!(keyboardVisible && availableHeight < 220.dp)) {
                WorkspaceTabs(selected = workspace, onSelect = { workspace = it })
            }

            when (workspace) {
                0 -> Column(Modifier.weight(1f)) {
                    LazyColumn(
                        state = assistantScroll,
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("cook-scroll"),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        viewModel.storageMessage?.let { notice ->
                            item("storage") { StorageNotice(notice, viewModel::retrySave) }
                        }
                        if (session.completedAt == null &&
                            viewModel.cookingState != CookingState.COOKING &&
                            (recipe == null || viewModel.recognitionInProgress || observations.isNotEmpty() || ingredientReviewed)) {
                            item("welcome") {
                                IngredientRecognitionCard(
                                    connected = status == ConnectionStatus.CONNECTED,
                                    busy = viewModel.recognitionInProgress,
                                    planning = viewModel.isFetchingRecipes,
                                    notice = viewModel.recognitionMessage,
                                    detections = detections,
                                    reviewed = ingredientReviewed,
                                    hasIngredients = entries.isNotEmpty(),
                                    onIdentify = ::identifyIngredient,
                                    onReview = ::reviewObservation,
                                    onManual = { editor = EntryEditor() },
                                    onConnect = { connect = true },
                                    onRecipes = { viewModel.planRecipeForIngredients(); workspace = 0 }
                                )
                            }
                        }
                        if (recipe != null) {
                            item("current-task") {
                                CurrentRecipeCard(
                                    viewModel = viewModel,
                                    onTimer = { timerDialog = true },
                                    onIngredients = { workspace = 1 },
                                    onRecipeDetails = { workspace = 2 }
                                )
                            }
                        }
                        if (viewModel.messages.isEmpty() && recipe != null) {
                            item("assistant-intro") {
                                Text("Ask about your recipe or the next step.",
                                    color = Muted, fontSize = 12.sp, lineHeight = 17.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                            }
                        }
                        items(viewModel.messages.size, key = { "message-$it" }) { index ->
                            CookChatBubble(viewModel.messages[index])
                        }
                        if (viewModel.isGenerating || viewModel.isFetchingRecipes) {
                            item("thinking") {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    CircularProgressIndicator(Modifier.size(14.dp), color = GoldPrimary, strokeWidth = 2.dp)
                                    Text(if (viewModel.isFetchingRecipes) "Planning your recipe…" else "Thinking…", color = Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    ChatComposer(chat, { chat = it }, viewModel.isGenerating, !viewModel.isLoading, ::sendQuestion, viewModel.isTtsEnabled, viewModel::toggleVoice, ::dictate)
                }

                1 -> LazyColumn(
                    state = ingredientScroll,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("cook-scroll"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    viewModel.storageMessage?.let { notice ->
                        item("storage") { StorageNotice(notice, viewModel::retrySave) }
                    }
                    item("ingredients-header") {
                        SessionCard("Your ingredients", "Add what you have. Mark it added only after it goes into the recipe.") {
                            if (entries.isEmpty()) {
                                Text("Start with an ingredient or a mixed batch.", color = Muted, fontSize = 13.sp)
                            }
                            if (session.completedAt == null) {
                                Button(
                                    onClick = { editor = EntryEditor() },
                                    enabled = !viewModel.isLoading,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-ingredient"),
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text("Add ingredient", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    if (observations.isNotEmpty() && session.completedAt == null) {
                        item("camera-proposals") { IngredientProposals(observations, ::reviewObservation) }
                    }
                    items(entries, key = { it.id }) { entry ->
                        IngredientEntryCard(
                            entry = entry,
                            editable = session.completedAt == null,
                            onCorrect = {
                                editor = EntryEditor(
                                    entry.id, entry.identities.joinToString(", "),
                                    entry.quantity?.value?.let(::formatQuantity).orEmpty(),
                                    entry.quantity?.unit ?: QuantityUnit.GRAMS,
                                    condition = entry.ingredientPreparation.orEmpty()
                                )
                            },
                            onUndo = { viewModel.undoEntry(entry.id) },
                            onTransfer = { viewModel.confirmTransfer(entry.id) }
                        )
                    }
                    if (session.completedAt == null && (entries.isNotEmpty() || recipe != null)) {
                        item("archive") {
                            OutlinedButton(
                                onClick = { viewModel.archiveDraftAndStartNew(); workspace = 0 },
                                modifier = Modifier.fillMaxWidth().testTag("archive-draft"),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("Save draft & start a new session") }
                        }
                    }
                }

                2 -> LazyColumn(
                    state = recipeScroll,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("cook-scroll"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    viewModel.storageMessage?.let { notice ->
                        item("storage") { StorageNotice(notice, viewModel::retrySave) }
                    }
                    if (recipe != null) {
                        item("recipe-details") {
                            RecipeDetailsCard(recipe, onReturn = { workspace = 0 },
                                cooking = viewModel.cookingState == CookingState.COOKING || viewModel.cookingState == CookingState.DONE)
                        }
                    }
                    if (session.completedAt == null && viewModel.cookingState != CookingState.COOKING && viewModel.cookingState != CookingState.DONE) {
                        item("recipe-search") {
                            SessionCard("Find something to cook") {
                                Text(
                                    viewModel.confirmedIngredients.joinToString().ifBlank { "Add ingredients to search for matching recipes." },
                                    color = SoftWhite, fontSize = 13.sp
                                )
                                Button(
                                    onClick = { viewModel.planRecipeForIngredients(); workspace = 0 },
                                    enabled = viewModel.confirmedIngredients.isNotEmpty() && !viewModel.isFetchingRecipes && !viewModel.isLoading,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("search-recipes"),
                                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    if (viewModel.isFetchingRecipes) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                    } else {
                                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (viewModel.isFetchingRecipes) "Finding recipes…" else if(recipe!=null) "Update recipe with my preferences" else "Find recipes", fontWeight = FontWeight.SemiBold)
                                }
                                Text(viewModel.recipeMessage, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                                TextButton(onClick = { viewModel.fetchRecipesForIngredients() },
                                    enabled = !viewModel.isFetchingRecipes && viewModel.confirmedIngredients.isNotEmpty()) {
                                    Text("Browse sourced recipes instead")
                                }
                                TextButton(
                                    onClick = { showGuides = !showGuides },
                                    modifier = Modifier.fillMaxWidth().testTag("offline-guides")
                                ) {
                                    Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (showGuides) "Hide offline recipes" else "Browse offline recipes")
                                }
                            }
                        }
                        if (viewModel.onlineRecipes.isNotEmpty()) {
                            item("online-heading") {
                                SectionLabel("SAVED & ONLINE RECIPES")
                            }
                            items(viewModel.onlineRecipes, key = { "online-${it.id}" }) { result ->
                                RecipeSelectionCard(result, recipe?.id == result.id) { chooseRecipe(result) }
                            }
                        }
                        if (showGuides) {
                            item("offline-heading") { SectionLabel("OFFLINE GUIDES") }
                            items(
                                BUILT_IN_RECIPES.sortedByDescending { result ->
                                    viewModel.confirmedIngredients.count { name ->
                                        result.name.contains(name, true) || result.targets.any { it.ingredient.contains(name, true) }
                                    }
                                },
                                key = { "builtin-${it.id}" }
                            ) { result ->
                                RecipeSelectionCard(result, recipe?.id == result.id) { chooseRecipe(result) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Assistant", "Ingredients", "Recipes")
    val icons = listOf(Icons.Default.AutoAwesome, Icons.Default.ShoppingBasket, Icons.Default.MenuBook)
    val showIcons = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 420 &&
        LocalDensity.current.fontScale <= 1.15f
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Surface(
                onClick = { onSelect(index) },
                color = Color.Transparent,
                shape = RoundedCornerShape(11.dp),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                    .semantics { this.selected = active }
                    .testTag("cook-tab-${label.lowercase()}")
            ) {
                Row(
                    Modifier.padding(horizontal = 5.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showIcons) {
                        Icon(icons[index], null, tint = if (active) GoldPrimary else Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(label, color = if (active) GoldPrimary else Muted, fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 2,
                        textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun GuideCameraHero(
    status: ConnectionStatus,
    diagnostics: String,
    observation: Observation?,
    analyzed: Boolean,
    collapsed: Boolean,
    latestFrameFlow: StateFlow<ByteArray?>?,
    latestFrame: ByteArray?,
    onModeChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onRecognize: () -> Unit,
    recognizing: Boolean,
    primaryRecognition: Boolean,
    onToggleSize: () -> Unit,
    canExpand: Boolean,
    modifier: Modifier = Modifier
) {
    val connected = status == ConnectionStatus.CONNECTED
    val visionNotice = observation?.takeIf { it.status != "SUCCESS" }?.let {
        when (it.status) {
            "POOR_IMAGE" -> "Try better lighting"
            "QUOTA_OR_RATE_LIMIT" -> "Analysis paused · try shortly"
            "TIMEOUT" -> "Analysis timed out"
            else -> "Analysis unavailable"
        }
    }
    val statusText = when {
        diagnostics.contains("code rejected", ignoreCase = true) -> "Check pairing code"
        connected -> "Camera connected"
        status == ConnectionStatus.CONNECTING -> "Connecting…"
        status == ConnectionStatus.RECONNECTING -> "Reconnecting…"
        status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.LOST -> "Camera disconnected"
        status == ConnectionStatus.FOUND -> "Camera found"
        else -> "Ready to connect"
    }
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(SurfaceNavy)
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (connected) SavedGreen else Muted))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(visionNotice ?: statusText, color = if (visionNotice != null) GoldPrimary else Color.White,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)

            }
            TextButton(onClick = onConnect, modifier = Modifier.testTag("guide-connect")) {
                Icon(if (connected) Icons.Default.Link else Icons.Default.LinkOff, if (connected) "Pairing details" else "Connect camera phone", tint = if (connected) Muted else GoldPrimary, modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onToggleSize,
                enabled = canExpand || !collapsed,
                modifier = Modifier.testTag("camera-expand")
            ) {
                Icon(
                    if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (collapsed) "Expand camera" else "Minimize camera",
                    tint = if (canExpand || !collapsed) Muted else Muted.copy(alpha = .35f)
                )
            }
        }
        if (!collapsed) {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                if (analyzed) {
                    ObservationPanel(observation, Modifier.fillMaxSize())
                } else {
                    LiveGuideFrame(latestFrameFlow, latestFrame, connected, Modifier.fillMaxSize())
                }
                if (connected) {
                    FilledTonalButton(
                        onClick = onRecognize,
                        enabled = !recognizing,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                            .testTag("recognize-ingredients"),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (primaryRecognition) GoldPrimary else SurfaceNavy.copy(alpha = .94f),
                            contentColor = if (primaryRecognition) Color.Black else SoftWhite),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(when {
                            recognizing -> "Identifying…"
                            primaryRecognition -> "Identify ingredient"
                            else -> "Scan ingredient"
                        }, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(false to "Live camera", true to "Last scan").forEach { (mode, label) ->
                    Surface(
                        onClick = { onModeChange(mode) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .semantics { selected = analyzed == mode }
                            .testTag(if (mode) "camera-analyzed" else "camera-live"),
                        color = if (analyzed == mode) GoldPrimary.copy(alpha = .10f) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label, color = if (analyzed == mode) GoldPrimary else Muted,
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** Keep frame collection and bitmap decoding out of the session/assistant composition. */
@Composable
private fun LiveGuideFrame(
    flow: StateFlow<ByteArray?>?,
    fallback: ByteArray?,
    connected: Boolean,
    modifier: Modifier = Modifier
) {
    val bytes = if (flow == null) fallback else {
        val current by flow.collectAsStateWithLifecycle()
        current
    }
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    Box(
        modifier.clipToBounds().background(Brush.verticalGradient(listOf(SurfaceNavy, KitchenBackground))),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), "Live Guide preview; no tracking boxes",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Column(
                Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(if (connected) Icons.Default.Videocam else Icons.Default.Wifi,
                    null, tint = GoldPrimary.copy(alpha = .7f), modifier = Modifier.size(28.dp))
                Text(if (connected) "Waiting for camera" else "Your kitchen, in view",
                    color = SoftWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (connected) "Point your camera phone at the ingredients." else "Connect your camera phone to see your ingredients.",
                    color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun IngredientRecognitionCard(
    connected: Boolean,
    busy: Boolean,
    planning: Boolean,
    notice: String?,
    detections: List<DetectionResult>,
    reviewed: Boolean,
    hasIngredients: Boolean,
    onIdentify: () -> Unit,
    onReview: (String) -> Unit,
    onManual: () -> Unit,
    onConnect: () -> Unit,
    onRecipes: () -> Unit
) {
    val proposals = detections.filter { it.supported && it.confidence >= .5f }
        .distinctBy { canonicalizeIngredientLabel(it.label) }.take(2)
    SessionCard(
        when {
            planning -> "Planning your recipe"
            busy -> "Looking at your ingredient"
            reviewed -> "Ready for your recipe"
            proposals.isNotEmpty() -> "Review your ingredient"
            else -> "Let’s see what you have"
        }
    ) {
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), color = GoldPrimary, strokeWidth = 2.dp)
                Text("Keep the ingredient in view.", color = Muted, fontSize = 13.sp)
            }
            reviewed -> Text("Place the next ingredient in view, or find a recipe with what you’ve saved.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            proposals.isNotEmpty() -> {
                proposals.forEach { detection ->
                    val name = canonicalizeIngredientLabel(detection.label) ?: detection.label
                    OutlinedButton(onClick = { onReview(name) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$name · ${(detection.confidence * 100).toInt()}% · Review", maxLines = 2)
                    }
                }
                Text("Confirm the name, then enter its weight. Without a scale, enter a manual amount or leave it unknown.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                if (proposals.size > 1) Text("Weigh separately, or record the whole batch together. Its weight cannot be split automatically.",
                    color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            else -> Text(notice ?: if (connected) "Place one ingredient in view and tap Identify ingredient on the camera."
                else "Connect a camera phone, or enter your ingredients below.",
                color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
        if (!busy) {
            if (!connected) OutlinedButton(onClick = onConnect,
                modifier = Modifier.fillMaxWidth()) { Text("Connect camera phone") }
            if (hasIngredients) Button(onClick = onRecipes, enabled = !planning,
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().testTag("find-recipes")) {
                Text(if (planning) "Planning recipe…" else "Find recipes")
            }
            if (reviewed) OutlinedButton(
                onClick = onIdentify, enabled = connected && !planning,
                modifier = Modifier.fillMaxWidth().testTag("next-ingredient")
            ) { Text("Next ingredient") }
            TextButton(onClick = onManual, enabled = !planning, modifier = Modifier.testTag("add-ingredient")) {
                Text("Enter an ingredient manually")
            }
        }
    }
}

@Composable
private fun AssistantWelcome(
    ingredientCount: Int,
    isLoading: Boolean,
    onAdd: () -> Unit,
    onRecipes: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (ingredientCount == 0) "Add ingredients to find a recipe." else "Your ingredients are ready. Choose a recipe.",
            color = SoftWhite, fontSize = 14.sp, lineHeight = 20.sp)
        if (ingredientCount == 0) {
            Button(onClick = onAdd, enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add-ingredient"),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Add ingredients", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(onClick = onRecipes, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black)) {
                Text("Choose a recipe", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun IngredientProposals(names: List<String>, onReview: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.Videocam, null, tint = Muted, modifier = Modifier.size(15.dp))
            Text("Found by camera · tap to review", color = Muted, fontSize = 11.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(names) { name ->
                SuggestionChip(onClick = { onReview(name) }, label = { Text(name, color = SoftWhite) },
                    icon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = GoldPrimary) })
            }
        }
    }
}

@Composable
private fun IngredientEntryCard(
    entry: IngredientEntry,
    editable: Boolean,
    onCorrect: () -> Unit,
    onUndo: () -> Unit,
    onTransfer: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    SessionCard(
        entry.label,
        when (entry.status) {
            EntryStatus.TRANSFER_CONFIRMED -> "Added to recipe"
            EntryStatus.PREPARED -> "Ready to add"
            else -> "Not added yet"
        }
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.quantity?.let { "${formatQuantity(it.value)} ${it.unit.label}" } ?: "Amount not entered",
                    color = GoldPrimary, fontSize = if (entry.quantity == null) 14.sp else 20.sp, fontWeight = FontWeight.Bold)
                if (entry.quantity != null) Text(
                    when (entry.quantitySource) {
                        EvidenceSource.SCALE -> "Measured by scale"
                        EvidenceSource.MANUAL -> "Entered by you"
                        else -> "Recorded quantity"
                    }, color = Muted, fontSize = 11.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.status == EntryStatus.TRANSFER_CONFIRMED) {
                    Icon(Icons.Default.CheckCircle, null, tint = SavedGreen, modifier = Modifier.size(22.dp))
                }
                if (editable) Box {
                    IconButton(onClick = { showActions = true }, modifier = Modifier.testTag("ingredient-actions-${entry.id}")) {
                        Icon(Icons.Default.MoreVert, "Edit ingredient", tint = Muted)
                    }
                    DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false },
                        modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                        DropdownMenuItem(text = { Text("Correct ingredient") },
                            onClick = { showActions = false; onCorrect() }, modifier = Modifier.testTag("correct-${entry.id}"))
                        DropdownMenuItem(text = { Text("Remove ingredient") },
                            onClick = { showActions = false; onUndo() }, modifier = Modifier.testTag("undo-${entry.id}"))
                    }
                }
            }
        }
        if (entry.identities.size > 1) {
            Text("Total for this batch; individual amounts are unknown.", color = Muted, fontSize = 12.sp)
        }
        if (editable) {
            if (entry.status != EntryStatus.TRANSFER_CONFIRMED) {
                OutlinedButton(
                    onClick = onTransfer, modifier = Modifier.fillMaxWidth().testTag("transfer-${entry.id}"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("I’ve added this", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CurrentRecipeCard(
    viewModel: CookViewModel,
    onTimer: () -> Unit,
    onIngredients: () -> Unit,
    onRecipeDetails: () -> Unit
) {
    val session = viewModel.session
    val recipe = session.recipe ?: return
    val haptic = LocalHapticFeedback.current
    when (viewModel.cookingState) {
        CookingState.COOKING -> SessionCard(
            title = if (session.paused) "Paused at step ${session.step + 1}" else "Step ${session.step + 1} of ${recipe.steps.size}"
        ) {
            LinearProgressIndicator(
                progress = { session.step.toFloat() / recipe.steps.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = GoldPrimary, trackColor = Color.White.copy(alpha = .07f)
            )
            Text(recipe.steps.getOrNull(session.step)?.instruction.orEmpty(),
                color = Color.White, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
            session.stepTimerSeconds?.let {
                Text("Reminder ${formatTime(it * 1000)}", color = GoldPrimary, fontSize = 11.sp)
            }
            viewModel.timerNotice?.let {
                Text(it, color = GoldPrimary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = viewModel::togglePause,
                    modifier = Modifier.weight(1f).testTag("pause-cooking"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) {
                    Icon(if (session.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(if (session.paused) "Resume" else "Pause", fontSize = 11.sp)
                }
                TextButton(onClick = viewModel::repeatStep, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 3.dp)) {
                    Icon(Icons.Default.VolumeUp, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Repeat", fontSize = 11.sp)
                }
                TextButton(onClick = onTimer, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 3.dp)) {
                    Icon(Icons.Default.Schedule, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Timer", fontSize = 11.sp)
                }
            }
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.completeStep()
                },
                enabled = !session.paused && !viewModel.isLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("complete-step"),
                colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (session.step == recipe.steps.lastIndex) "Final step done" else "Done, next step",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            if (session.step > 0) {
                TextButton(onClick = viewModel::previousStep, modifier = Modifier.fillMaxWidth()) {
                    Text("Previous step", color = Muted, fontSize = 12.sp)
                }
            }
        }

        CookingState.DONE -> SessionCard(
            if (session.completedAt == null) "Finished cooking?" else "Cooking record saved",
            if (session.completedAt == null) "Point the camera phone at your finished dish, then save."
            else "Your dish and ingredient record are saved in Track."
        ) {
            if (session.completedAt != null) {
                Icon(Icons.Default.CheckCircle, null, tint = SavedGreen, modifier = Modifier.size(34.dp))
                Button(
                    onClick = viewModel::startNewSession,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new-session"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black)
                ) { Text("Cook something new", fontWeight = FontWeight.SemiBold) }
            } else {
                TextButton(onClick = onIngredients) { Text("Review ingredients") }
                Button(
                    onClick = viewModel::markAsDone,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-session"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black)
                ) { Text("Finish & save", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = viewModel::returnToCooking, modifier = Modifier.fillMaxWidth()) {
                    Text("Go back to cooking", color = Muted)
                }
            }
        }

        else -> Surface(color = SurfaceNavy, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecipeArtwork(recipe, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(recipe.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(recipeSummary(recipe), color = Muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = onRecipeDetails) { Icon(Icons.Default.Info, "Recipe details", tint = Muted, modifier = Modifier.size(20.dp)) }
                }
                Button(onClick = viewModel::startCooking,
                    enabled = !viewModel.isLoading && recipe.steps.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start-cooking"),
                    colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(13.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Start cooking", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RecipeDetailsCard(recipe: Recipe, cooking: Boolean, onReturn: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    SessionCard(recipe.name, "${recipe.source} · ${recipe.servings?.let { "$it servings" } ?: "Servings unspecified"}") {
        if (recipe.targets.isNotEmpty()) {
            SectionLabel("RECIPE INGREDIENTS")
            recipe.targets.forEach {
                Text(it.original.ifBlank { "${it.ingredient}: ${it.amount ?: "unspecified"} ${it.unit}" },
                    color = SoftWhite, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Text("Recipe amounts are a guide. Your added quantities are recorded in Ingredients.",
                color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Text(nutritionText(recipe), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        recipe.sourceUrl?.let { url ->
            TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                Text("Open recipe source")
            }
        }
        OutlinedButton(onClick = onReturn, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
            Text(if (cooking) "Return to cooking" else "Use this recipe")
        }
    }
}

@Composable
private fun ChatComposer(value: String, onValueChange: (String) -> Unit, busy: Boolean, ready: Boolean, onSend: () -> Unit, voiceEnabled: Boolean, onToggleVoice: () -> Unit, onDictate: () -> Unit) {
    Surface(color = KitchenBackground, shadowElevation = 10.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = ready,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                modifier = Modifier.fillMaxWidth().testTag("chat-input"),
                placeholder = { Text("Ask VESCURUS…", color = Muted, fontSize = 13.sp) },
                shape = RoundedCornerShape(28.dp),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceNavy, unfocusedContainerColor = SurfaceNavy,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = GoldPrimary
                ),
                leadingIcon = {
                    IconButton(onClick = onToggleVoice, modifier = Modifier.testTag("voice-guidance")) {
                        Icon(if (voiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            if (voiceEnabled) "Mute spoken guidance" else "Enable spoken guidance",
                            tint = if (voiceEnabled) SoftWhite else Muted, modifier = Modifier.size(21.dp))
                    }
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value.isBlank() && !busy) IconButton(onClick = onDictate, enabled = ready,
                        modifier = Modifier.padding(end = 5.dp).size(40.dp).clip(CircleShape)
                            .background(if (ready) GoldPrimary else Muted.copy(alpha = .2f)).testTag("chat-dictate")) {
                        Icon(Icons.Default.Mic, "Dictate question", tint = if (ready) Color.Black else Muted, modifier = Modifier.size(21.dp))
                    } else IconButton(
                        onClick = onSend,
                        enabled = value.isNotBlank() && !busy && ready,
                        modifier = Modifier.testTag("chat-send")
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = GoldPrimary, strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, "Send question",
                            tint = if (value.isNotBlank()) GoldPrimary else Muted, modifier = Modifier.size(21.dp))
                    }
                    }
                }
            )
        }
    }
}

@Composable
private fun StorageNotice(message: String, onRetry: () -> Unit) {
    Surface(color = GoldPrimary.copy(alpha = .10f), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, GoldPrimary.copy(alpha = .22f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(message, color = SoftWhite, fontSize = 12.sp, lineHeight = 17.sp)
            TextButton(onClick = onRetry) { Text("Retry save", color = GoldPrimary) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = GoldPrimary, fontSize = 10.sp, letterSpacing = 1.1.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
}

@Composable
private fun StepTimerDialog(onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    var minutes by rememberSaveable { mutableStateOf("5") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Step reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("We'll remind you to check. You decide when the step is finished.")
                OutlinedTextField(
                    minutes, { minutes = it }, label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag("timer-minutes")
                )
            }
        },
        confirmButton = {
            TextButton(enabled = minutes.toLongOrNull() in 1L..1440L, onClick = { onSave(minutes.toLong() * 60) }) {
                Text("Set reminder")
            }
        },
        dismissButton = { TextButton(onClick = { onSave(null) }) { Text("No timer") } }
    )
}

@Composable
private fun IngredientDialog(editor: EntryEditor, onDismiss: () -> Unit, onSave: (String, Double?, QuantityUnit, String?) -> Unit) {
    var input by rememberSaveable(editor.requestId) { mutableStateOf(editor.input) }
    var amount by rememberSaveable(editor.requestId) { mutableStateOf(editor.amount) }
    var unit by rememberSaveable(editor.requestId) { mutableStateOf(editor.unit) }
    var condition by rememberSaveable(editor.requestId) { mutableStateOf(editor.condition) }
    var nameConfirmed by rememberSaveable(editor.requestId) { mutableStateOf(editor.vision == null || editor.id != null) }
    val value = amount.trim().toDoubleOrNull()
    val valid = input.isNotBlank() && input.length <= 240 &&
        (amount.isBlank() || (value != null && value.isFinite() && value > 0 && value <= 10_000_000 &&
            (unit != QuantityUnit.COUNT || value % 1.0 == 0.0)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when {
            editor.id != null -> "Edit ingredient"
            !nameConfirmed -> "Is this the right ingredient?"
            editor.vision != null -> "Record the amount"
            else -> "Review ingredient"
        }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add one ingredient, or separate a mixed batch with commas.", fontSize = 13.sp)
                OutlinedTextField(
                    input, { input = it }, label = { Text("Ingredient or ingredients") },
                    modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("ingredient-name")
                )
                OutlinedTextField(
                    condition, { condition = it.take(120) },
                    label = { Text("Preparation (optional)") },
                    supportingText = { Text(if (editor.vision != null && editor.condition.isNotBlank())
                        "Camera suggestion — check or correct it before confirming."
                        else "For example: raw, fried, diced or julienne.") },
                    modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }
                        .testTag("ingredient-preparation")
                )
                if (nameConfirmed) {
                if (editor.vision != null) Text("Place $input on your scale. Enter its reading below. No scale is connected to this session; this amount will be recorded as manual.", fontSize = 13.sp)
                OutlinedTextField(
                    amount, { amount = it },
                    keyboardOptions = KeyboardOptions(keyboardType = if (unit == QuantityUnit.COUNT) KeyboardType.Number else KeyboardType.Decimal),
                    label = { Text("Amount (optional)") },
                    supportingText = { Text("Leave blank if you haven't measured it.") },
                    isError = amount.isNotBlank() && !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }.testTag("ingredient-amount")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuantityUnit.entries.forEach { choice ->
                        FilterChip(selected = unit == choice, onClick = { unit = choice }, label = { Text(choice.label) })
                    }
                }
                Text("Mark it added only after you put it into your dish.",
                    fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid, onClick = {
                    if (!nameConfirmed) nameConfirmed = true else onSave(input.trim(), value, unit,
                        condition.trim().takeIf { it.isNotEmpty() })
                },
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("save-ingredient")
            ) { Text(when {
                !nameConfirmed -> "Confirm name & continue"
                editor.id != null -> "Save changes"
                amount.isBlank() -> "Save without amount"
                else -> "Save ingredient"
            }) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun GuideConnectionDialog(onDismiss: () -> Unit, onConnect: (String, Int) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("vescurus", 0) }
    var host by rememberSaveable { mutableStateOf(prefs.getString("guideHost", "").orEmpty()) }
    var port by rememberSaveable { mutableStateOf(prefs.getInt("guidePort", 8766).toString()) }
    var code by rememberSaveable { mutableStateOf(prefs.getString("guidePairCode", "").orEmpty()) }
    var connectionOptions by rememberSaveable { mutableStateOf(port != "8766") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect camera phone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the address and code shown on your camera phone. Keep both phones on the same Wi-Fi.")
                OutlinedTextField(
                    host, { host = it },
                    isError = host.isNotBlank() && !com.example.vescurus.network.isValidPeerHost(host),
                    label = { Text("Camera address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("guide-host")
                )
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("guide-code")
                )
                TextButton(onClick = { connectionOptions = !connectionOptions }) {
                    Text(if(connectionOptions) "Hide connection options" else "Connection options")
                }
                if(connectionOptions) {
                OutlinedTextField(
                    port, { port = it }, label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("guide-port")
                )
                }

            }
        },
        confirmButton = {
            TextButton(
                enabled = com.example.vescurus.network.isValidPeerHost(host) && port.toIntOrNull() in 1..65535 && code.length == 6,
                onClick = {
                    prefs.edit().putString("guideHost", host.trim()).putInt("guidePort", port.toInt()).putString("guidePairCode", code).apply()
                    onConnect(host.trim(), port.toInt())
                },
                modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("guide-connect-confirm")
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun RecipeSelectionCard(recipe: Recipe, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("recipe-${recipe.id}"),
        color = if (isSelected) Color(0xFF202A23) else SurfaceNavy,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (isSelected) GoldPrimary.copy(alpha = .7f) else Color.White.copy(alpha = .07f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            RecipeArtwork(recipe, Modifier.size(76.dp).clip(RoundedCornerShape(13.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(recipe.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                Text(
                    if (recipe.savedAt != null) "Saved recipe · ${recipe.source}"
                    else if (recipe.source.contains("built-in", ignoreCase = true)) "Offline guide"
                    else recipe.source,
                    color = GoldPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(recipeSummary(recipe), color = Muted, fontSize = 11.sp, maxLines = 2, lineHeight = 15.sp)
            }
            if (isSelected) Icon(Icons.Default.CheckCircle, "Selected recipe", tint = GoldPrimary, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun RecipeArtwork(recipe: Recipe, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Brush.linearGradient(listOf(Color(0xFF253137), Color(0xFF19231D)))),
        contentAlignment = Alignment.Center
    ) {
        if (recipe.thumbnailUrl != null) {
            // Recipe artwork has a neutral fallback while loading or offline.
            Icon(Icons.Default.Restaurant, null, tint = GoldPrimary.copy(alpha = .6f), modifier = Modifier.size(29.dp))
            AsyncImage(recipe.thumbnailUrl, recipe.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (recipe.thumbnail != R.drawable.ic_vescurus_logo) {
            Image(painterResource(recipe.thumbnail), recipe.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.Restaurant, null, tint = GoldPrimary.copy(alpha = .8f), modifier = Modifier.size(29.dp))
        }
    }
}

private fun recipeSummary(recipe: Recipe): String = buildList {
    if (recipe.totalTimeMs > 0) add("About ${(recipe.totalTimeMs + 59_999) / 60_000} min")
    add("${recipe.steps.size} steps")
    recipe.servings?.let { add("$it servings") }
}.joinToString(" · ")

@Composable
fun CookChatBubble(msg: ChatMessage) {
    val isUser = msg.sender == "User"
    val isNotice = msg.sender == "System"
    var visible by remember(msg) { mutableStateOf(false) }
    LaunchedEffect(msg) { visible = true }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 5 }
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
            Surface(
                modifier = Modifier.fillMaxWidth(if (isUser) .88f else 1f),
                color = when {
                    isUser -> Color(0xFF223023)
                    isNotice -> GoldPrimary.copy(alpha = .08f)
                    else -> SurfaceNavy
                },
                shape = RoundedCornerShape(topStart = 17.dp, topEnd = 17.dp,
                    bottomStart = if (isUser) 17.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 17.dp),
                border = BorderStroke(1.dp, if (isNotice) GoldPrimary.copy(alpha = .18f) else Color.White.copy(alpha = .06f))
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (isUser) "YOU" else "VESCURUS",
                        color = if (isUser) Muted else GoldPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = .6.sp)
                    Text(msg.message, color = SoftWhite, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

fun formatQuantity(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

fun formatTime(ms: Long): String = "${ms / 60_000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"

fun nutritionText(r: Recipe): String =
    if (r.calories == null && r.proteinG == null && r.carbsG == null && r.fatsG == null) "Nutrition unavailable"
    else "Per recipe serving · ${r.calories?.let { "$it kcal" } ?: "calories unavailable"} · protein ${r.proteinG?.let { "${formatQuantity(it.toDouble())} g" } ?: "unavailable"}"
