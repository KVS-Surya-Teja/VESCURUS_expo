package com.example.vescurus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.vescurus.model.Role
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.network.VescurusConnectionManager
import com.example.vescurus.ui.snapshot.SnapshotScreen
import com.example.vescurus.ui.theme.GoldPrimary
import com.example.vescurus.ui.theme.SurfaceDeep

private sealed class NavItem(val route: String, val icon: ImageVector, val label: String) {
    object Home : NavItem("home", Icons.Default.Home, "Home")
    object Cook : NavItem("cook", Icons.Default.Restaurant, "Cook")
    object Track : NavItem("track", Icons.Default.Timeline, "Track")
    object Account : NavItem("account", Icons.Default.AccountCircle, "Account")
}

private val NAV_ITEMS = listOf(NavItem.Home, NavItem.Cook, NavItem.Track, NavItem.Account)

@Composable
fun MainAppShell(
    selectedRole: Role,
    onResetRole: () -> Unit
) {
    val connectionManager: VescurusConnectionManager = viewModel()
    val cookViewModel: CookViewModel = viewModel(factory = CookViewModel.Factory)

    val context = LocalContext.current
    val connectionStatus by connectionManager.status.collectAsState(initial = ConnectionStatus.IDLE)
    val diagnostics by connectionManager.diagnostics.collectAsState(initial = "")

    LaunchedEffect(selectedRole) {
        if (selectedRole != Role.NONE) {
            connectionManager.start(context, selectedRole)
        }
    }

    DisposableEffect(Unit) {
        onDispose { connectionManager.stop() }
    }

    // Tab selection survives rotation + process death.
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentItem = NAV_ITEMS[selectedIndex]

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.Black, contentColor = GoldPrimary) {
                NAV_ITEMS.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = {
                            Text(
                                item.label,
                                color = if (selectedIndex == index) GoldPrimary else Color.Gray
                            )
                        },
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GoldPrimary,
                            unselectedIconColor = Color.Gray,
                            indicatorColor = Color.DarkGray
                        )
                    )
                }
            }
        },
        containerColor = SurfaceDeep
    ) { innerPadding ->
        Surface(
            modifier = Modifier.padding(innerPadding),
            color = SurfaceDeep
        ) {
            Column {
                if (currentItem != NavItem.Cook) {
                    ConnectionStatusBar(selectedRole, connectionStatus)
                }

                when (currentItem) {
                    NavItem.Home -> PlaceholderScreen("Welcome to VESCURUS Home")
                    NavItem.Cook -> RoleSpecificCookScreen(
                        selectedRole = selectedRole,
                        connectionManager = connectionManager,
                        connectionStatus = connectionStatus,
                        diagnostics = diagnostics,
                        cookViewModel = cookViewModel,
                        onBack = onResetRole
                    )
                    NavItem.Track -> TrackScreen(viewModel = cookViewModel)
                    NavItem.Account -> AccountScreen(selectedRole, cookViewModel, onResetRole)
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ConnectionStatusBar(role: Role, status: ConnectionStatus) {
    if (role == Role.DEMONSTRATE || role == Role.NONE) return

    val backgroundColor = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF2E7D32)
        ConnectionStatus.LOST, ConnectionStatus.DISCONNECTED -> Color(0xFFC62828)
        else -> Color(0xFFF57F17)
    }

    val statusText = when (status) {
        ConnectionStatus.IDLE -> "Network idle"
        ConnectionStatus.SEARCHING -> "Searching..."
        ConnectionStatus.FOUND -> "Guide found"
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.CONNECTED -> if (role == Role.GUIDE) "Cook connected" else "Guide connected"
        ConnectionStatus.RECONNECTING -> "Reconnecting..."
        ConnectionStatus.DISCONNECTED -> "Disconnected"
        ConnectionStatus.LOST -> "Connection lost"
    }

    Surface(color = backgroundColor, modifier = Modifier.fillMaxWidth()) {
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
private fun RoleSpecificCookScreen(
    selectedRole: Role,
    connectionManager: VescurusConnectionManager,
    connectionStatus: ConnectionStatus,
    diagnostics: String,
    cookViewModel: CookViewModel,
    onBack: () -> Unit
) {
    // Each role subscribes to only the state it actually needs — hoisting
    // frame/detection state out of the shell so pushing a frame no longer
    // recomposes the whole tree.
    when (selectedRole) {
        Role.GUIDE -> {
            val detections by connectionManager.latestDetections.collectAsState(initial = emptyList())
            GuideScreen(
                status = connectionStatus,
                diagnostics = diagnostics,
                detections = detections,
                onDetectionsUpdated = connectionManager::broadcastDetection,
                onFrameAvailable = connectionManager::broadcastFrame,
                onBack = onBack
            )
        }
        Role.COOK -> {
            val detections by connectionManager.latestDetections.collectAsState(initial = emptyList())
            val latestFrame by connectionManager.latestFrame.collectAsState(initial = null)
            CookScreen(
                status = connectionStatus,
                diagnostics = diagnostics,
                detections = detections,
                latestFrame = latestFrame,
                viewModel = cookViewModel,
                onSnapshot = connectionManager::sendSnapshotCommand
            )
        }
        Role.DEMONSTRATE -> SnapshotScreen(onBack = onBack)
        Role.NONE -> PlaceholderScreen("No role selected")
    }
}

@Composable
private fun AccountScreen(role: Role, viewModel: CookViewModel, onResetRole: () -> Unit) {
    val ttsEnabled by viewModel.isTtsEnabled.collectAsState()
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
                checked = ttsEnabled,
                onCheckedChange = viewModel::setTtsEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = GoldPrimary)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onResetRole,
            colors = ButtonDefaults.buttonColors(containerColor = GoldPrimary)
        ) {
            Text("Change role", color = Color.Black)
        }
    }
}
