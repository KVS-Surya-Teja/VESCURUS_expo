package com.example.vescurus.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
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
    object Cook : NavItem("cook", Icons.Default.Tune, "Cook & Vision")
    object Track : NavItem("track", Icons.Default.Info, "History")
    object Settings : NavItem("settings", Icons.Default.RestartAlt, "System")
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

    var selectedItem by rememberSaveable { mutableStateOf(0) }
    val previousItem = remember { mutableStateOf(0) }
    val items = listOf(NavItem.Cook, NavItem.Track, NavItem.Settings)

    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF020617),
                contentColor = GoldPrimary,
                tonalElevation = 0.dp,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                items.forEachIndexed { index, item ->
                    val selected = selectedItem == index
                    NavigationBarItem(
                        icon = {
                            Icon(
                                item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(if (selected) 24.dp else 22.dp)
                            )
                        },
                        modifier = Modifier.testTag("nav-${item.route}"),
                        label = {
                            Text(
                                item.label,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                                color = if (selected) GoldPrimary else Color.White.copy(alpha = 0.4f)
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
                            unselectedIconColor = Color.White.copy(alpha = 0.4f),
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
                            onSnapshot = { connectionManager.sendSnapshotCommand() },
                            onDirectConnect = { ip, port -> connectionManager.connectDirectly(ip, port) },
                            viewModel = cookViewModel,
                            onBack = onResetRole,
                            observation = observation,
                            sessionId = peerSession?.sessionId ?: "unpaired",
                            onObservation = connectionManager::broadcastObservation,
                            onVideoAvailable = connectionManager::broadcastVideo
                        )
                    }
                    NavItem.Track -> TrackScreen(viewModel = cookViewModel)
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
    onDirectConnect: (String, Int) -> Unit,
    viewModel: CookViewModel,
    onBack: () -> Unit,
    observation: com.example.vescurus.model.Observation? = null,
    sessionId: String = "unpaired",
    onObservation: (com.example.vescurus.model.Observation) -> Unit = {},
    onVideoAvailable: (com.example.vescurus.model.VideoFrame) -> Unit = {},
    latestFrameFlow: kotlinx.coroutines.flow.StateFlow<ByteArray?>? = null
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
                observation = observation, sessionId = sessionId, onObservation = onObservation, onVideoAvailable = onVideoAvailable
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
                observation = observation
            )
        }
        Role.DEMONSTRATE -> {
            CookScreen(
                status = ConnectionStatus.IDLE,
                diagnostics = "DEMONSTRATION • simulated observations • no physical evidence",
                detections = emptyList(),
                latestFrame = null,
                viewModel = viewModel,
                onSnapshot = {},
                onDirectConnect = { _, _ -> }
            )
        }
        else -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "No Role Configured", color = Color.White)
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
    val observation by connectionManager.observation.collectAsStateWithLifecycle()
    val pairCode by connectionManager.pairCode.collectAsStateWithLifecycle()
    val cameraFresh by connectionManager.cameraFresh.collectAsStateWithLifecycle()
    val visionStatus by connectionManager.visionStatus.collectAsStateWithLifecycle()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) {
            val content=viewModel.exportJson()
            scope.launch {
                exportMessage=withContext(Dispatchers.IO) { runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
                    "Session export saved."
                }.getOrDefault("Export could not be saved. Your phone records are retained.") }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("SYSTEM",color=GoldPrimary,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
        Text("Ready for your setup",color=Color.White,fontSize=27.sp,fontWeight=FontWeight.ExtraBold)
        SessionCard("This device", "${role.name} · Android ${android.os.Build.VERSION.RELEASE}") {
            Text("Local IP: ${connectionManager.getLocalIpAddress()}",color=Color.White,fontSize=13.sp)
            Text("Guide peer: ${status.name.lowercase()}",color=Color.White,fontSize=13.sp)
            Text(diagnostics.ifBlank { "No live peer session in this role." },color=Color(0xFF94A3B8),fontSize=12.sp)
            if(role==Role.GUIDE)Text("Pairing code: $pairCode",color=GoldPrimary,fontSize=22.sp,fontWeight=FontWeight.Bold)
            Text("Camera: ${if(cameraFresh) "fresh frames" else "no fresh stream"}",color=Color.White,fontSize=13.sp)
            Text("Vision: $visionStatus",color=Color.White,fontSize=13.sp)
            Text("Engine: ${com.example.vescurus.di.AppModule.engineDescription}",color=Color(0xFF94A3B8),fontSize=12.sp)
            Text("Cloud vision and the optional assistant require internet. Local phone communication and saved cooking data do not.",color=Color(0xFF94A3B8),fontSize=12.sp)
        }
        SessionCard("Scale", "Bypassed for this exhibition setup") {
            Text("No live scale is connected. Quantities are entered manually and labelled as manual evidence. Calibration and live weighing will be enabled with the physical assembly.",color=Color(0xFF94A3B8),fontSize=13.sp)
        }
        SessionCard("Voice guidance") {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("Speak step instructions",color=Color.White,modifier=Modifier.weight(1f))
                Switch(viewModel.isTtsEnabled,{viewModel.toggleVoice()})
            }
            Text("Only step actions and timer reminders are announced. Camera frames never trigger repeated speech.",color=Color(0xFF94A3B8),fontSize=12.sp)
        }
        SessionCard("Stored on this phone") {
            Text("Active session, history, correction audit and retrieved recipes are stored in app-private files. No hosted database is required. Demonstration records are separate.",color=Color(0xFF94A3B8),fontSize=13.sp)
            Text("${viewModel.sessions.size} retained sessions · ${if(viewModel.isSaving) "saving…" else "local storage ready"}",color=Color.White,fontSize=13.sp)
            Button(onClick={exporter.launch("vescurus-${viewModel.sessionId.take(8)}.json")},enabled=!viewModel.isLoading,modifier=Modifier.fillMaxWidth().testTag("export-session")){Text("Export sessions as JSON")}
            exportMessage?.let { Text(it,color=GoldPrimary,fontSize=12.sp) }
            viewModel.storageMessage?.let { Text(it,color=GoldPrimary);TextButton(onClick=viewModel::retrySave){Text("Retry save")} }
        }
        OutlinedButton(onClick=onResetRole,modifier=Modifier.fillMaxWidth().testTag("switch-role")){Text("Switch Guide / Cook / Demonstration")}
        Text("VESCURUS · Kotlin / Compose · local session owner: Cook",color=Color(0xFF94A3B8),fontSize=11.sp)
    }
}
