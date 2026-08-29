package com.example.vescurus.ui

import androidx.compose.foundation.layout.*
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    object Home : NavItem("home", Icons.Default.Home, "Home")
    object Cook : NavItem("cook", Icons.Default.Restaurant, "Cook")
    object Track : NavItem("track", Icons.Default.Timeline, "Track")
    object Account : NavItem("account", Icons.Default.AccountCircle, "Account")
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
    val items = listOf(NavItem.Home, NavItem.Cook, NavItem.Track, NavItem.Account)

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.Black,
                contentColor = GoldPrimary
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = {
                            Text(
                                item.label,
                                color = if (selectedItem == index) GoldPrimary else Color.Gray
                            )
                        },
                        selected = selectedItem == index,
                        onClick = { selectedItem = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GoldPrimary,
                            unselectedIconColor = Color.Gray,
                            indicatorColor = Color.DarkGray
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
                val showGlobalStatus = items[selectedItem] != NavItem.Cook
                if (showGlobalStatus) {
                    ConnectionStatusBar(selectedRole, connectionStatus)
                }

                when (items[selectedItem]) {
                    NavItem.Home -> PlaceholderScreen("Welcome to VESCURUS Home")
                    NavItem.Cook -> {
                        // Diagnostic: Force detection if stuck for demo
                        var showForceButton by remember { mutableStateOf(false) }
                        
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
                                viewModel = cookViewModel,
                                onBack = onResetRole
                            )

                            // EMERGENCY MOCK BUTTON (Secretly hidden in bottom corner for Expo)
                            if (selectedRole == Role.GUIDE && detections.isEmpty()) {
                                Button(
                                    onClick = {
                                        val mock = listOf(com.example.vescurus.model.DetectionResult("egg", 0.99f, 2, com.example.vescurus.model.BoundingBox(0.3f, 0.3f, 0.6f, 0.6f), true))
                                        connectionManager.broadcastDetection(mock)
                                    },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp).alpha(0.05f), // Almost invisible
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                                ) { }
                            }
                        }
                    }
                    NavItem.Track -> TrackScreen(viewModel = cookViewModel)
                    NavItem.Account -> AccountScreen(selectedRole, cookViewModel, onResetRole)
                }
            }
        }
    }
}

@Composable
fun PlaceholderScreen(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun ConnectionStatusBar(role: Role, status: ConnectionStatus) {
    if (role == Role.DEMONSTRATE || role == Role.NONE) return

    val backgroundColor = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF2E7D32) // Green
        ConnectionStatus.LOST, ConnectionStatus.DISCONNECTED -> Color(0xFFC62828) // Red
        else -> Color(0xFFF57F17) // Orange
    }

    val statusText = when (status) {
        ConnectionStatus.IDLE -> "Network Idle"
        ConnectionStatus.SEARCHING -> "Searching..."
        ConnectionStatus.FOUND -> "Guide Found"
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.CONNECTED -> if (role == Role.GUIDE) "Cook connected" else "Guide connected"
        ConnectionStatus.RECONNECTING -> "Reconnecting..."
        ConnectionStatus.DISCONNECTED -> "Disconnected"
        ConnectionStatus.LOST -> "Connection lost"
        else -> "Unknown"
    }

    Surface(
        color = backgroundColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = statusText,
            color = Color.White,
            modifier = Modifier.padding(8.dp),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
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
                    // This updates both local Guide state and triggers broadcast
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
                onSnapshot = onSnapshot
            )
        }
        Role.DEMONSTRATE -> PlaceholderScreen("DEMO UI - Explore VESCURUS")
        else -> PlaceholderScreen("No Role Selected")
    }
}

@Composable
fun AccountScreen(role: Role, viewModel: CookViewModel, onResetRole: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Current Role: ${role.name}", color = Color.White, fontSize = 18.sp)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Voice Instructions (TTS)", color = Color.White, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = viewModel.isTtsEnabled,
                onCheckedChange = { viewModel.isTtsEnabled = it },
                colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onResetRole,
            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
        ) {
            Text("Reset Role (Dev Only)", color = Color.Black)
        }
    }
}
