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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

import com.example.vescurus.debug.VescurusLogger
import com.example.vescurus.di.AppModule
import com.example.vescurus.model.Role
import com.example.vescurus.ui.MainAppShell
import com.example.vescurus.ui.RoleSelectionScreen
import com.example.vescurus.ui.theme.VESCURUSTheme

// Brand Theme Colors
val GoldPrimary = Color(0xFFFFB800)
val BlackBackground = Color(0xFF020617)
val TextWhite = Color(0xFFFFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppModule.init(this)
        VescurusLogger.initSession(this)
        setContent {
            VESCURUSTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    VESCURUSApp()
                }
            }
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
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("vescurus",0) }
    var selectedRole by rememberSaveable { mutableStateOf(runCatching { Role.valueOf(prefs.getString("role","NONE")!!) }.getOrDefault(Role.NONE).takeIf { it == Role.GUIDE || it == Role.COOK } ?: Role.NONE) }
    // Keep the branded entrance on a fresh launch, then resume the saved phone role.
    // Saved screen state still prevents replaying it on an activity recreation.
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Splash) }

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
                prefs.edit().putString("role",role.name).apply()
                selectedRole = role
                currentScreen = Screen.MainApp
            })
            Screen.MainApp -> MainAppShell(
                selectedRole = selectedRole,
                onResetRole = {
                    prefs.edit().putString("role","NONE").apply()
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
        delay(2200)
        onTimeout()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.vescurus_startup_brand),
            contentDescription = "VESCURUS",
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 28.dp)
                .graphicsLayer(alpha = alphaAnim, scaleX = scaleAnim, scaleY = scaleAnim)
        )
    }
}
