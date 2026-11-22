package com.example.vescurus.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.model.Role
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.network.VescurusConnectionManager

sealed class NavItem(val route: String, val icon: ImageVector, val label: String) {
    object Cook : NavItem("cook", Icons.Default.Restaurant, "Cook")
    object Track : NavItem("track", Icons.Default.History, "Track")
    object Settings : NavItem("settings", Icons.Default.Settings, "Settings")
}

@Composable
fun MainAppShell(
    selectedRole: Role,
    onResetRole: () -> Unit,
    cookViewModel: CookViewModel = viewModel()
) {
    val connectionManager: VescurusConnectionManager = viewModel()
    val haptic = LocalHapticFeedback.current
    val appView=androidx.compose.ui.platform.LocalView.current
    DisposableEffect(appView,selectedRole) {
        val previous=appView.keepScreenOn
        appView.keepScreenOn=selectedRole!=Role.NONE
        onDispose { appView.keepScreenOn=previous }
    }

    val context = LocalContext.current
    val connectionStatus by connectionManager.status.collectAsStateWithLifecycle()
    val diagnostics by connectionManager.diagnostics.collectAsStateWithLifecycle()
    val detections by connectionManager.latestDetections.collectAsStateWithLifecycle()

    val observation by connectionManager.observation.collectAsStateWithLifecycle()
    val peerSession by connectionManager.sessionContext.collectAsStateWithLifecycle()
    val ownPairCode by connectionManager.pairCode.collectAsStateWithLifecycle()
    val analysisRequest by connectionManager.analysisRequest.collectAsStateWithLifecycle()
    val finishedPhoto by connectionManager.finishedPhoto.collectAsStateWithLifecycle()
    val analysisRequestId by connectionManager.analysisRequestId.collectAsStateWithLifecycle()
    val mirroredHistory by connectionManager.mirroredHistory.collectAsStateWithLifecycle()
    val historySyncError by connectionManager.historySyncError.collectAsStateWithLifecycle()
    val historySyncPending by connectionManager.historySyncPending.collectAsStateWithLifecycle()
    val historyLoaded by connectionManager.historyLoaded.collectAsStateWithLifecycle()
    val localHistoryRecords = localJournalRecords(cookViewModel)
    LaunchedEffect(selectedRole, historyLoaded, cookViewModel.isLoading, localHistoryRecords) {
        if (historyLoaded && !cookViewModel.isLoading && selectedRole != Role.DEMONSTRATE)
            connectionManager.updateLocalHistory(localHistoryRecords)
    }
    LaunchedEffect(selectedRole,connectionStatus,cookViewModel.session.completedAt,cookViewModel.session.finalPhotoPath) {
        if(selectedRole==Role.COOK && cookViewModel.session.completedAt!=null && cookViewModel.session.finalPhotoPath==null && connectionStatus==ConnectionStatus.CONNECTED)
            connectionManager.requestFinishedPhoto(cookViewModel.sessionId)
    }
    LaunchedEffect(finishedPhoto) {
        finishedPhoto?.let { photo ->
            if(selectedRole==Role.COOK && photo.sessionId==cookViewModel.sessionId && cookViewModel.session.completedAt!=null)
                cookViewModel.attachFinalPhoto(photo.sessionId,android.util.Base64.decode(photo.jpegBase64,android.util.Base64.NO_WRAP))
        }
    }

    LaunchedEffect(selectedRole) {
        cookViewModel.setDemonstration(selectedRole == Role.DEMONSTRATE)
        if (selectedRole != Role.NONE) {
            connectionManager.start(context, selectedRole)
        }
    }

    LaunchedEffect(selectedRole, cookViewModel.isLoading, cookViewModel.sessionVersion, cookViewModel.sessionId) {
        if (selectedRole == Role.COOK && !cookViewModel.isLoading) connectionManager.updateSession(cookViewModel.sessionContext)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_STOP) cookViewModel.pauseForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(selectedRole) { onDispose { connectionManager.stop() } }

    var selectedItem by rememberSaveable(selectedRole) { mutableStateOf(0) }
    BackHandler(enabled = selectedItem != 0) { selectedItem = 0 }
    val previousItem = remember { mutableStateOf(0) }
    val items = listOf(NavItem.Cook, NavItem.Track, NavItem.Settings)

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF020617),
                contentColor = GoldPrimary,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(64.dp)
            ) {
                items.forEachIndexed { index, item ->
                    val selected = selectedItem == index
                    val isGuideCamera = selectedRole == Role.GUIDE && item == NavItem.Cook
                    val itemLabel = if (isGuideCamera) "Camera" else item.label
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (isGuideCamera) Icons.Default.Videocam else item.icon,
                                contentDescription = itemLabel,
                                modifier = Modifier.size(if (selected) 24.dp else 22.dp)
                            )
                        },
                        modifier = Modifier.testTag("nav-${item.route}"),
                        label = {
                            Text(
                                itemLabel,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                                color = if (selected) GoldPrimary else Color.White.copy(alpha = 0.6f)
                            )
                        },
                        selected = selected,
                        onClick = {
                            if (index != selectedItem) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                previousItem.value = selectedItem
                                selectedItem = index
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GoldPrimary,
                            unselectedIconColor = Color.White.copy(alpha = 0.6f),
                            indicatorColor = GoldPrimary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        },
        containerColor = Color(0xFF020617)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color(0xFF020617))
        ) {
            // Animated tab content — slide direction based on tab index
            val goingRight = selectedItem > previousItem.value
            AnimatedContent(
                targetState = selectedItem,
                transitionSpec = {
                    if (goingRight) {
                        (slideInHorizontally(tween(280, easing = EaseOutCubic)) { it / 3 } + fadeIn(tween(280)))
                            .togetherWith(slideOutHorizontally(tween(280, easing = EaseInCubic)) { -it / 3 } + fadeOut(tween(200)))
                    } else {
                        (slideInHorizontally(tween(280, easing = EaseOutCubic)) { -it / 3 } + fadeIn(tween(280)))
                            .togetherWith(slideOutHorizontally(tween(280, easing = EaseInCubic)) { it / 3 } + fadeOut(tween(200)))
                    }
                },
                label = "TabTransition"
            ) { tabIndex ->
                when (items[tabIndex]) {
                    NavItem.Cook -> {
                        RoleSpecificCookScreen(
                            selectedRole = selectedRole,
                            connectionStatus = connectionStatus,
                            diagnostics = if(selectedRole==Role.GUIDE) diagnostics+"\nPairing code: "+ownPairCode+(peerSession?.let { "\nCook: ${it.recipe ?: "Preparing ingredients"} | step ${it.step+1}${if(it.paused) " | paused" else ""}" } ?: "") else diagnostics,
                            detections = detections,
                            latestFrame = null,
                            latestFrameFlow = connectionManager.latestFrame,
                            onBroadcastDetection = { connectionManager.broadcastDetection(it) },
                            onBroadcastFrame = { connectionManager.broadcastFrame(it) },
                            onSnapshot = { if(cookViewModel.beginIngredientRecognition()) connectionManager.sendSnapshotCommand() },
                            onOpenTrack = { previousItem.value = selectedItem; selectedItem = items.indexOf(NavItem.Track).coerceAtLeast(0) },
                            onCaptureDishPhoto = { connectionManager.requestFinishedPhoto(cookViewModel.sessionId) },
                            remoteAnalysisRequest = analysisRequest,
                            remoteAnalysisRequestId = analysisRequestId,
                            onDirectConnect = { ip, port -> connectionManager.connectDirectly(ip, port) },
                            viewModel = cookViewModel,
                            onBack = onResetRole,
                            observation = observation,
                            sessionId = peerSession?.sessionId ?: "unpaired",
                            onObservation = connectionManager::broadcastObservation,
                            onVideoAvailable = connectionManager::broadcastVideo
                        )
                    }
                    NavItem.Track -> TrackScreen(
                        viewModel = cookViewModel,
                        allowResume = selectedRole != Role.GUIDE,
                        allowRename = selectedRole != Role.GUIDE,
                        mirroredSessions = if (selectedRole == Role.DEMONSTRATE) emptyList() else mirroredHistory,
                        mirrorNotice = if (selectedRole == Role.DEMONSTRATE) null else when {
                            historySyncError != null -> "Some records could not be copied to the other phone. Keep both phones connected and try again."
                            historySyncPending > 0 && connectionStatus == ConnectionStatus.CONNECTED -> "Saving a copy on your other phone…"
                            historySyncPending > 0 -> "Saved here. Connect both phones to keep a copy on each."
                            else -> null
                        },
                        onRetryMirror = { connectionManager.updateLocalHistory(localHistoryRecords) },
                        onResumeDraft = {
                            previousItem.value = selectedItem
                            selectedItem = 0
                        }
                    )
                    NavItem.Settings -> AccountScreen(selectedRole, cookViewModel, onResetRole, connectionManager)
                }
            }
        }
    }
}

@Composable
fun RoleSpecificCookScreen(
    selectedRole: Role,
    connectionStatus: ConnectionStatus,
    diagnostics: String,
    detections: List<DetectionResult>,
    latestFrame: ByteArray?,
    onBroadcastDetection: (List<DetectionResult>) -> Unit,
    onBroadcastFrame: (ByteArray) -> Unit,
    onSnapshot: () -> Unit,
    remoteAnalysisRequest: Long = 0,
    remoteAnalysisRequestId: String? = null,
    onDirectConnect: (String, Int) -> Unit,
    viewModel: CookViewModel,
    onBack: () -> Unit,
    observation: com.example.vescurus.model.Observation? = null,
    sessionId: String = "unpaired",
    onObservation: (com.example.vescurus.model.Observation) -> Unit = {},
    onVideoAvailable: (com.example.vescurus.model.VideoFrame) -> Unit = {},
    latestFrameFlow: kotlinx.coroutines.flow.StateFlow<ByteArray?>? = null,
    onOpenTrack: () -> Unit = {},
    onCaptureDishPhoto: () -> Unit = {}
) {
    when (selectedRole) {
        Role.GUIDE -> {
            GuideScreen(
                status = connectionStatus,
                diagnostics = diagnostics,
                detections = detections,
                onDetectionsUpdated = { onBroadcastDetection(it) },
                onFrameAvailable = onBroadcastFrame,
                onBack = onBack,
                observation = observation, sessionId = sessionId, onObservation = onObservation, onVideoAvailable = onVideoAvailable,
                remoteAnalysisRequest = remoteAnalysisRequest, remoteAnalysisRequestId = remoteAnalysisRequestId
            )
        }
        Role.COOK -> {
            CookScreen(
                status = connectionStatus,
                diagnostics = diagnostics,
                detections = detections,
                latestFrame = latestFrame,
                latestFrameFlow = latestFrameFlow,
                viewModel = viewModel,
                onSnapshot = onSnapshot,
                onDirectConnect = onDirectConnect,
                observation = observation,
                onOpenTrack = onOpenTrack,
                onCaptureDishPhoto = onCaptureDishPhoto
            )
        }
        Role.DEMONSTRATE -> {
            CookScreen(
                status = ConnectionStatus.IDLE,
                diagnostics = "Practice session · manual entries · separate history",
                detections = emptyList(),
                latestFrame = null,
                viewModel = viewModel,
                onSnapshot = {},
                onDirectConnect = { _, _ -> },
                onOpenTrack = onOpenTrack
            )
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Choose how to use this phone", color = Color.White)
            }
        }
    }
}

@Composable
fun AccountScreen(role: Role, viewModel: CookViewModel, onResetRole: () -> Unit, connectionManager: VescurusConnectionManager) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val status by connectionManager.status.collectAsStateWithLifecycle()
    val diagnostics by connectionManager.diagnostics.collectAsStateWithLifecycle()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) {
            val content=viewModel.exportJson()
            scope.launch {
                exportMessage=withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
                    "Copy saved."
                }.getOrDefault("Could not save a copy. Your cooking records are still on this phone.") }
            }
        }
    }
    val muted = Color(0xFF94A3B8)
    val practice = role == Role.DEMONSTRATE
    val deviceLabel = when (role) {
        Role.GUIDE -> "Camera phone"
        Role.COOK -> "Cooking phone"
        Role.DEMONSTRATE -> "Practice session · separate history"
        else -> "Choose a phone role"
    }
    val connectionLabel = when (status) {
        ConnectionStatus.CONNECTED -> "Phones connected"
        ConnectionStatus.CONNECTING -> "Connecting…"
        ConnectionStatus.RECONNECTING -> "Reconnecting…"
        ConnectionStatus.FOUND -> "Camera phone found"
        ConnectionStatus.SEARCHING -> if (role == Role.GUIDE) "Ready to pair" else "Looking for the camera phone"
        ConnectionStatus.DISCONNECTED, ConnectionStatus.LOST -> "Phone disconnected"
        ConnectionStatus.IDLE -> "Not connected"
    }
    // Keep connection failures actionable without exposing protocol details.
    val connectionNotice = diagnostics.takeIf { message ->
        listOf("rejected", "could not", "unavailable", "lost", "retry", "disconnected", "enter ")
            .any { message.contains(it, ignoreCase = true) }
    }?.substringBefore(" |")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SETTINGS", color = GoldPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text(deviceLabel, color = muted, fontSize = 14.sp)
        }

        if (!practice) {
            SessionCard("Phone connection") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (status == ConnectionStatus.CONNECTED) Color(0xFF86EFAC) else GoldPrimary, CircleShape))
                    Text(connectionLabel, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                connectionNotice?.let { Text(it, color = GoldPrimary, fontSize = 13.sp) }
                if (role != Role.GUIDE && status != ConnectionStatus.CONNECTED) {
                    Text("Connect to your camera phone from Cook.", color = muted, fontSize = 13.sp)
                }
                Text("Ingredient recognition needs internet.", color = muted, fontSize = 12.sp)
            }
        }

        if (role != Role.GUIDE) SessionCard("Voice guidance") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Spoken guidance", color = Color.White, fontSize = 15.sp)
                    Text("Steps, reminders and assistant replies.", color = muted, fontSize = 12.sp)
                }
                Switch(viewModel.isTtsEnabled, { viewModel.toggleVoice() })
            }
        }

        if (role != Role.GUIDE) SessionCard("Saved on this phone") {
            Text(
                when {
                    viewModel.isLoading -> "Loading your records…"
                    viewModel.isSaving -> "Saving your changes…"
                    viewModel.sessions.isEmpty() -> "Your cooking is saved automatically."
                    else -> "${viewModel.sessions.size} saved cooking ${if (viewModel.sessions.size == 1) "record" else "records"}"
                },
                color = Color.White, fontSize = 14.sp
            )
            if (practice) Text("Practice records are kept separate.", color = muted, fontSize = 12.sp)
            viewModel.storageMessage?.let {
                Text(it, color = GoldPrimary, fontSize = 13.sp)
                TextButton(onClick = viewModel::retrySave) { Text("Retry save") }
            }
            OutlinedButton(
                onClick = { exporter.launch("vescurus-${viewModel.sessionId.take(8)}.json") },
                enabled = !viewModel.isLoading,
                modifier = Modifier.fillMaxWidth().testTag("export-session")
            ) { Text("Save a copy") }
            exportMessage?.let { Text(it, color = GoldPrimary, fontSize = 12.sp) }
        }

        OutlinedButton(onClick = onResetRole, modifier = Modifier.fillMaxWidth().testTag("switch-role")) {
            Text("Switch phone role")
        }

        Text("VESCURUS", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = muted, fontSize = 11.sp, letterSpacing = 2.sp)
    }
}
