package com.example.vescurus.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.DetectionResult
import com.example.vescurus.model.Role
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.network.VescurusConnectionManager

sealed class NavItem(val route: String, val icon: ImageVector, val label: String) {
    object Cook : NavItem("cook", Icons.Default.Tune, "Cook & Vision")
    object Track : NavItem("track", Icons.Default.Info, "Nutrition")
    object Settings : NavItem("settings", Icons.Default.RestartAlt, "System")
}

@Composable
fun MainAppShell(
    selectedRole: Role,
    onResetRole: () -> Unit
) {
    val connectionManager: VescurusConnectionManager = viewModel()
    val cookViewModel: CookViewModel = viewModel()

    val context = LocalContext.current
    val connectionStatus by connectionManager.status.collectAsState(initial = ConnectionStatus.IDLE)
    val diagnostics by connectionManager.diagnostics.collectAsState(initial = "")
    val detections by connectionManager.latestDetections.collectAsState(initial = emptyList())
    val latestFrame by connectionManager.latestFrame.collectAsState(initial = null)

    LaunchedEffect(selectedRole) {
        if (selectedRole != Role.NONE) {
            connectionManager.start(context, selectedRole)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            connectionManager.stop()
        }
    }

    var selectedItem by remember { mutableStateOf(0) }
    val items = listOf(NavItem.Cook, NavItem.Track, NavItem.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF020617),
                contentColor = GoldPrimary,
                tonalElevation = 8.dp
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = {
                            Text(
                                item.label,
                                fontSize = 11.sp,
                                fontWeight = if (selectedItem == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedItem == index) GoldPrimary else Color.White.copy(alpha = 0.5f)
                            )
                        },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GoldPrimary,
                            unselectedIconColor = Color.White.copy(alpha = 0.5f),
                            indicatorColor = GoldPrimary.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        },
        containerColor = Color(0xFF020617)
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding),
            color = Color(0xFF020617)
        ) {
            Column {
                when (items[selectedItem]) {
                    NavItem.Cook -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            RoleSpecificCookScreen(
                                selectedRole = selectedRole,
                                connectionStatus = connectionStatus,
                                diagnostics = diagnostics,
                                detections = detections,
                                latestFrame = latestFrame,
                                onBroadcastDetection = { newDetections ->
                                    connectionManager.broadcastDetection(newDetections)
                                },
                                onBroadcastFrame = { frameBytes ->
                                    connectionManager.broadcastFrame(frameBytes)
                                },
                                onSnapshot = { connectionManager.sendSnapshotCommand() },
                                onDirectConnect = { ip, port ->
                                    connectionManager.connectDirectly(ip, port)
                                },
                                viewModel = cookViewModel,
                                onBack = onResetRole
                            )
                        }
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
    onBack: () -> Unit
) {
    when (selectedRole) {
        Role.GUIDE -> {
            GuideScreen(
                status = connectionStatus,
                diagnostics = diagnostics,
                detections = detections,
                onDetectionsUpdated = {
                    onBroadcastDetection(it)
                },
                onFrameAvailable = onBroadcastFrame,
                onBack = onBack
            )
        }
        Role.COOK -> {
            CookScreen(
                status = connectionStatus,
                diagnostics = diagnostics,
                detections = detections,
                latestFrame = latestFrame,
                viewModel = viewModel,
                onSnapshot = onSnapshot,
                onDirectConnect = onDirectConnect
            )
        }
        Role.DEMONSTRATE -> {
            CookScreen(
                status = ConnectionStatus.CONNECTED,
                diagnostics = "Standalone Demonstration",
                detections = listOf(
                    DetectionResult(
                        label = "Egg",
                        confidence = 0.94f,
                        recipe_class = 0,
                        box_2d = com.example.vescurus.model.BoundingBox(0.3f, 0.3f, 0.7f, 0.7f),
                        supported = true
                    )
                ),
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
fun AccountScreen(
    role: Role,
    viewModel: CookViewModel,
    onResetRole: () -> Unit,
    connectionManager: VescurusConnectionManager
) {
    val localIp = remember { connectionManager.getLocalIpAddress() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SYSTEM & DIAGNOSTICS",
                color = GoldPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Configuration",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Role Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "ACTIVE ROLE",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = role.name,
                        color = GoldPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "LOCAL IP ADDRESS",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = localIp,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // TTS Setting Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0F172A),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Voice Guidance (TTS)",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Spoken step announcements & timing cues",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = viewModel.isTtsEnabled,
                        onCheckedChange = { viewModel.isTtsEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GoldPrimary,
                            checkedTrackColor = GoldPrimary.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }

        // Switch Role Button
        Button(
            onClick = onResetRole,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
            border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.3f))
        ) {
            Text(
                text = "Switch Device Role (Guide / Cook)",
                color = GoldPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}
