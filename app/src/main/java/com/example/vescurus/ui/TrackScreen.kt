package com.example.vescurus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.example.vescurus.GoldPrimary
import com.example.vescurus.session.EntryStatus
import java.text.DateFormat
import java.util.Date

@Composable fun TrackScreen(viewModel: CookViewModel) {
    LazyColumn(Modifier.fillMaxSize().testTag("history-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Text(if(viewModel.isDemo) "DEMONSTRATION HISTORY" else "LOCAL HISTORY",color=GoldPrimary,fontSize=11.sp,fontWeight=FontWeight.Bold)
            Text("Your cooking record",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.ExtraBold)
            Text("Saved on this phone. Export records from System.",color=Color(0xFF94A3B8),fontSize=13.sp)
        }
        if(viewModel.sessions.isEmpty())item { SessionCard("No completed sessions yet") { Text("Finish the recipe steps and explicitly confirm completion to save a summary. The current session is already retained locally.",color=Color(0xFF94A3B8)) } }
        items(viewModel.sessions.reversed(),key={it.id}) { session ->
            SessionCard(session.recipe?.name ?: "Ingredient preparation",DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(session.completedAt ?: session.updatedAt))) {
                if(session.completedAt==null) {
                    Text("Saved draft · open Cook after resuming",color=GoldPrimary,fontSize=12.sp)
                    OutlinedButton(onClick={viewModel.resumeDraft(session.id)}){Text("Resume this draft")}
                }
                Text("Session ${session.id.take(8)} · revision ${session.version}${if(session.demonstration) " · demonstration" else ""}",color=GoldPrimary,fontSize=11.sp)
                session.entries.filter { it.status!=EntryStatus.UNDONE }.forEach { entry ->
                    Text("${entry.label}: ${entry.quantity?.let { "${formatQuantity(it.value)} ${it.unit.label}" } ?: "quantity unknown"}",color=Color.White,fontSize=14.sp)
                    Text("${entry.status.name.lowercase().replace('_',' ')} · ${entry.quantitySource?.name?.lowercase() ?: "no quantity evidence"}${if(entry.audits.isNotEmpty()) " · ${entry.audits.size} audited changes" else ""}",color=Color(0xFF94A3B8),fontSize=11.sp)
                }
                if(session.entries.any { it.status==EntryStatus.UNDONE })Text("Undone entries retained in the export audit.",color=Color(0xFF94A3B8),fontSize=11.sp)
                session.recipe?.let { Text(nutritionText(it),color=Color(0xFF94A3B8),fontSize=12.sp) }
                Text("Recipe nutrition is per source serving, not a calculated intake from logged ingredients.",color=Color(0xFF94A3B8),fontSize=11.sp)
            }
        }
    }
}
