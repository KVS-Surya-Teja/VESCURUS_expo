package com.example.vescurus.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.Role
import kotlinx.coroutines.delay

@Composable
fun RoleSelectionScreen(onRoleSelected: (Role) -> Unit) {
    var cardsVisible by remember { mutableStateOf(false) }
    var headerVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(80)
        headerVisible = true
        delay(180)
        cardsVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF020617))
                )
            )
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Animated Header
            AnimatedVisibility(
                visible = headerVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { -30 }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Surface(
                        color = GoldPrimary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.25f)),
                        modifier = Modifier.padding(bottom = 14.dp)
                    ) {
                        Text(
                            text = "V0 TWO-DEVICE ECOSYSTEM",
                            color = GoldPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = "Select Device Mode",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Configure this phone as the overhead vision Guide or interactive Cook companion",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Role Cards Stack with staggered entrance
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                data class CardDef(
                    val title: String,
                    val tag: String,
                    val description: String,
                    val icon: ImageVector,
                    val badgeColor: Color,
                    val role: Role
                )
                val cards = listOf(
                    CardDef(
                        "GUIDE (VISION)", "OVERHEAD",
                        "Mount above stove. Observes flat tawa, detects food & streams real-time P2P frames.",
                        Icons.Default.Videocam, Color(0xFF38BDF8), Role.GUIDE
                    ),
                    CardDef(
                        "COOK (COMPANION)", "CHEF ASSIST",
                        "Interactive touch display. Suggests tawa recipes, guides timelines & speaks steps.",
                        Icons.Default.Restaurant, GoldPrimary, Role.COOK
                    ),
                    CardDef(
                        "DEMONSTRATION", "STANDALONE",
                        "Explore interface, tawa recipe library and nutritional telemetry independently.",
                        Icons.Default.AutoAwesome, Color(0xFFA855F7), Role.DEMONSTRATE
                    )
                )

                cards.forEachIndexed { index, card ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(cardsVisible) {
                        if (cardsVisible) {
                            delay(index * 110L)
                            visible = true
                        }
                    }
                    AnimatedVisibility(
                        visible = visible,
                        enter = fadeIn(tween(360)) + slideInVertically(
                            animationSpec = tween(360, easing = EaseOutCubic)
                        ) { 50 }
                    ) {
                        RoleCard(
                            title = card.title,
                            tag = card.tag,
                            description = card.description,
                            icon = card.icon,
                            badgeColor = card.badgeColor,
                            onClick = { onRoleSelected(card.role) }
                        )
                    }
                }
            }

            // Footer
            Text(
                text = "VESCURUS IoT Lab Examination • Flat-Tawa System",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
fun RoleCard(
    title: String,
    tag: String,
    description: String,
    icon: ImageVector,
    badgeColor: Color,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "CardScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            },
        color = Color(0xFF1E293B).copy(alpha = 0.85f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.20f)),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(badgeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Surface(
                    color = badgeColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text(
                        text = tag,
                        color = badgeColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        letterSpacing = 0.8.sp,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = badgeColor.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
