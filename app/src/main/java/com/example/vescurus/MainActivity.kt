package com.example.vescurus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

import com.example.vescurus.model.Role
import com.example.vescurus.ui.MainAppShell
import com.example.vescurus.ui.RoleSelectionScreen

// Brand Theme Colors
val GoldPrimary = Color(0xFFFFB800)
val BlackBackground = Color(0xFF000000)
val TextWhite = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VESCURUSApp()
        }
    }
}

enum class Screen {
    Splash,
    RoleSelection,
    MainApp
}

@Composable
fun VESCURUSApp() {
    var currentScreen by remember { mutableStateOf(Screen.Splash) }
    var selectedRole by remember { mutableStateOf(Role.NONE) }

    Crossfade(
        targetState = currentScreen,
        animationSpec = tween(durationMillis = 600),
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            Screen.Splash -> SplashScreen(onTimeout = { 
                currentScreen = if (selectedRole == Role.NONE) Screen.RoleSelection else Screen.MainApp 
            })
            Screen.RoleSelection -> RoleSelectionScreen(onRoleSelected = { role ->
                selectedRole = role
                currentScreen = Screen.MainApp
            })
            Screen.MainApp -> MainAppShell(
                selectedRole = selectedRole,
                onResetRole = {
                    selectedRole = Role.NONE
                    currentScreen = Screen.RoleSelection
                }
            )
        }
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000),
        label = "AlphaAnimation"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.8f,
        animationSpec = tween(durationMillis = 1000),
        label = "ScaleAnimation"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(2500)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BlackBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer(
                alpha = alphaAnim,
                scaleX = scaleAnim,
                scaleY = scaleAnim
            )
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_vescurus_logo),
                contentDescription = "VESCURUS Logo",
                modifier = Modifier
                    .size(220.dp)
                    .padding(16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "VESCURUS",
                color = GoldPrimary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Smart Cooking System",
                color = TextWhite.copy(alpha = 0.7f),
                fontSize = 14.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

