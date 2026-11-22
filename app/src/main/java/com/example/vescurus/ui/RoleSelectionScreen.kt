package com.example.vescurus.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.Role

@Composable
fun RoleSelectionScreen(onRoleSelected: (Role) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF020617)
                    )
                )
            )
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 24.dp)
            ) {
                Surface(
                    color = GoldPrimary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GoldPrimary.copy(alpha = 0.25f)),
                    modifier = Modifier.padding(bottom = 12.dp)
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

            // Role Cards Stack
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RoleCard(
                    title = "GUIDE (VISION)",
                    tag = "OVERHEAD CAMERA",
                    description = "Mount above stove. Observes flat tawa, detects food & streams real-time P2P frames.",
                    icon = Icons.Default.Videocam,
                    badgeColor = Color(0xFF38BDF8),
                    onClick = { onRoleSelected(Role.GUIDE) }
                )

                RoleCard(
                    title = "COOK (COMPANION)",
                    tag = "CHEF ASSISTANT",
                    description = "Interactive touch display. Suggests tawa recipes, guides timelines & speaks steps.",
                    icon = Icons.Default.Restaurant,
                    badgeColor = GoldPrimary,
                    onClick = { onRoleSelected(Role.COOK) }
                )

                RoleCard(
                    title = "DEMONSTRATION",
                    tag = "STANDALONE",
                    description = "Explore interface, tawa recipe library and nutritional telemetry independently.",
                    icon = Icons.Default.AutoAwesome,
                    badgeColor = Color(0xFFA855F7),
                    onClick = { onRoleSelected(Role.DEMONSTRATE) }
                )
            }

            // Footer note
            Text(
                text = "VESCURUS IoT Lab Examination • Flat-Tawa System",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        color = Color(0xFF1E293B).copy(alpha = 0.85f),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon container
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

            // Body
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = badgeColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = tag,
                            color = badgeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            letterSpacing = 1.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Trailing Chevron
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
