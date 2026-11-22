package com.example.vescurus.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.vescurus.GoldPrimary
import com.example.vescurus.model.*
import com.example.vescurus.network.ConnectionStatus
import com.example.vescurus.session.*
import java.util.UUID

private val SurfaceNavy=Color(0xFF0F172A)
private val Muted=Color(0xFF94A3B8)
private data class EntryEditor(val id: String?=null,val input: String="",val amount: String="",val unit: QuantityUnit=QuantityUnit.GRAMS,
    val vision: VisionEvidence?=null,val requestId: String=UUID.randomUUID().toString())

@Composable
fun SessionCard(title: String, subtitle: String?=null, content: @Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(20.dp),color=SurfaceNavy,border=BorderStroke(1.dp,Color.White.copy(alpha=.08f))) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
            if(subtitle!=null)Text(subtitle,color=Muted,fontSize=12.sp)
            content()
        }
    }
}

@Composable
fun CookScreen(status: ConnectionStatus, diagnostics: String, detections: List<DetectionResult>, latestFrame: ByteArray?,
    viewModel: CookViewModel,onSnapshot: ()->Unit,onDirectConnect: (String,Int)->Unit={_,_->},observation: Observation?=null,latestFrameFlow: kotlinx.coroutines.flow.StateFlow<ByteArray?>?=null) {
    var editor by remember { mutableStateOf<EntryEditor?>(null) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var analyzed by rememberSaveable { mutableStateOf(true) }
    var showGuides by rememberSaveable { mutableStateOf(false) }
    var timerDialog by rememberSaveable { mutableStateOf(false) }
    var chat by rememberSaveable { mutableStateOf("") }
    val haptic=LocalHapticFeedback.current
    val uriHandler=LocalUriHandler.current
    val session=viewModel.session
    val entries=session.entries.filter { it.status!=EntryStatus.UNDONE }
    val observations=detections.filter { it.supported && it.confidence>=.5f }.mapNotNull { canonicalizeIngredientLabel(it.label) }.filter { it!="Unsupported object" }.distinct()
    val recipe=session.recipe
    if(connect)GuideConnectionDialog(onDismiss={connect=false},onConnect={host,port->onDirectConnect(host,port);connect=false})
    editor?.let { e -> IngredientDialog(e,onDismiss={editor=null},onSave={name,amount,unit->
        if(e.id==null)viewModel.addIngredient(name,amount,unit,e.vision?.takeIf { it.label.equals(name.trim(),true) },e.requestId)
        else viewModel.correctEntry(e.id,name,amount,unit)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress);editor=null
    }) }
    if(timerDialog) {
        var minutes by remember { mutableStateOf("5") }
        AlertDialog(onDismissRequest={timerDialog=false},title={Text("Optional step timer")},text={Column {
            Text("A timer prompts you to check. It never completes a step.")
            OutlinedTextField(minutes,{minutes=it},label={Text("Minutes")},singleLine=true,modifier=Modifier.testTag("timer-minutes"))
        }},confirmButton={TextButton(enabled=minutes.toLongOrNull() in 1L..1440L,onClick={viewModel.setStepTimer(minutes.toLong()*60);timerDialog=false}){Text("Set timer")}},
            dismissButton={TextButton(onClick={viewModel.setStepTimer(null);timerDialog=false}){Text("No timer")}})
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("cook-scroll"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(if(viewModel.isDemo) "DEMONSTRATION" else "VESCURUS / COOK",color=GoldPrimary,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
                Text(if(session.completedAt!=null) "Session saved" else if(viewModel.cookingState==CookingState.COOKING) "Cook at your pace" else "What are we cooking?",color=Color.White,fontSize=28.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.testTag("session-title"))
                Text(if(viewModel.isDemo) "Practice mode. Separate local records; no simulated recognition or sensor readings." else "Your session stays on this phone. Guide and internet are optional for manual cooking.",color=Muted,fontSize=13.sp)
                Text(if(viewModel.isLoading) "Loading local session…" else if(viewModel.isSaving) "Saving on phone…" else "Saved locally · ${session.id.take(8)} · revision ${session.version}",color=Muted,fontSize=11.sp,modifier=Modifier.testTag("save-status"))
            }
        }
        if(viewModel.storageMessage!=null)item { SessionCard("Storage notice") { Text(viewModel.storageMessage!!,color=Color.White);TextButton(onClick=viewModel::retrySave){Text("Retry save")} } }
        if(!viewModel.isDemo)item {
            SessionCard("Guide camera",if(status==ConnectionStatus.CONNECTED) "Peer synchronized. Camera and recognition have independent freshness." else "${status.name.lowercase().replaceFirstChar { it.uppercase() }} · manual logging remains available") {
                Text(diagnostics.ifBlank { "Connect the Guide phone on the same Wi-Fi or hotspot." },color=Muted,fontSize=12.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={connect=true},modifier=Modifier.testTag("guide-connect")){Text(if(status==ConnectionStatus.CONNECTED) "Reconnect" else "Connect Guide")}
                    TextButton(onClick={showCamera=!showCamera}){Text(if(showCamera) "Hide camera" else "Show camera")}
                }
                AnimatedVisibility(showCamera) { Column {
                    Row { FilterChip(selected=analyzed,onClick={analyzed=true},label={Text("Analyzed snapshot")});Spacer(Modifier.width(8.dp));FilterChip(selected=!analyzed,onClick={analyzed=false},label={Text("Live preview")}) }
                    if(analyzed)ObservationPanel(observation,Modifier.fillMaxWidth().height(240.dp))
                    else LiveGuideFrame(latestFrameFlow,latestFrame)
                } }
                if(observations.isNotEmpty()) {
                    Text("Camera proposals — tap to review identity and quantity",color=Muted,fontSize=12.sp)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(observations) { name ->
                        SuggestionChip(onClick={editor=EntryEditor(input=name,vision=observation?.let { VisionEvidence(it.sessionId,it.streamId,it.frameId,name) })},label={Text(name)})
                    } }
                } else Text(observation?.let { if(it.status=="SUCCESS") "No identifiable food in the analyzed frame." else "Vision: ${it.status.lowercase().replace('_',' ')}. Manual input works offline." } ?: "No current ingredient observation.",color=Muted,fontSize=12.sp)
            }
        }
        item {
            SessionCard("Preparation log","Scale bypassed for now. Enter a quantity from your own measurement, or leave it unknown. g, ml and counts stay separate.") {
                if(entries.isEmpty())Text("Add your first ingredient. Seeing food in the camera does not add it to your recipe.",color=Muted,fontSize=13.sp)
                if(session.completedAt==null)Button(onClick={editor=EntryEditor()},enabled=!viewModel.isLoading,modifier=Modifier.fillMaxWidth().testTag("add-ingredient"),colors=ButtonDefaults.buttonColors(containerColor=GoldPrimary)) { Text("Add ingredient or mixed batch",color=Color.Black) }
                if(session.entries.any { it.status==EntryStatus.UNDONE })Text("${session.entries.count { it.status==EntryStatus.UNDONE }} undone entries retained in the audit log.",color=Muted,fontSize=11.sp)
            }
        }
        items(entries,key={it.id}) { entry ->
            SessionCard(entry.label,when(entry.status) { EntryStatus.TRANSFER_CONFIRMED -> "Transfer confirmed by you";EntryStatus.PREPARED -> "Prepared · transfer not confirmed";else -> "Identity confirmed · quantity unknown" }) {
                Text(entry.quantity?.let { "${formatQuantity(it.value)} ${it.unit.label}" } ?: "Quantity not recorded",color=GoldPrimary,fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("Identity: ${entry.identitySource.name.lowercase().replace('_',' ')} · Quantity: ${entry.quantitySource?.name?.lowercase() ?: "unknown"}",color=Muted,fontSize=11.sp)
                if(entry.identities.size>1)Text("Combined batch quantity. Individual ingredient quantities are unknown.",color=Muted,fontSize=12.sp)
                if(session.completedAt==null) {
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick={editor=EntryEditor(entry.id,entry.identities.joinToString(", "),entry.quantity?.value?.let(::formatQuantity).orEmpty(),entry.quantity?.unit ?: QuantityUnit.GRAMS)}){Text("Correct")}
                        TextButton(onClick={viewModel.undoEntry(entry.id)},modifier=Modifier.testTag("undo-${entry.id}")){Text("Undo")}
                    }
                    if(entry.status!=EntryStatus.TRANSFER_CONFIRMED)OutlinedButton(onClick={viewModel.confirmTransfer(entry.id)}){Text("I transferred this into the recipe")}
                }
            }
        }
        if(recipe!=null)item {
            SessionCard(recipe.name,"${recipe.source} · ${recipe.servings?.let { "$it servings" } ?: "Servings not supplied"}") {
                if(recipe.targets.isNotEmpty()) {
                    Text("RECIPE TARGETS — not measured additions",color=GoldPrimary,fontSize=11.sp,fontWeight=FontWeight.Bold)
                    recipe.targets.forEach { Text(it.original.ifBlank { "${it.ingredient}: ${it.amount ?: "unspecified"} ${it.unit}" },color=Muted,fontSize=13.sp) }
                }
                Text(nutritionText(recipe),color=Muted,fontSize=12.sp)
                recipe.sourceUrl?.let { url -> TextButton(onClick={runCatching { uriHandler.openUri(url) }}){Text("Recipe source") } }
                when(viewModel.cookingState) {
                    CookingState.IDLE,CookingState.RECIPE_SELECTION -> Button(onClick=viewModel::startCooking,modifier=Modifier.fillMaxWidth().testTag("start-cooking")){Text("Start cooking")}
                    CookingState.COOKING -> {
                        LinearProgressIndicator(progress={session.step.toFloat()/recipe.steps.size},modifier=Modifier.fillMaxWidth(),color=GoldPrimary)
                        Text("STEP ${session.step+1} OF ${recipe.steps.size}",color=GoldPrimary,fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Text(recipe.steps.getOrNull(session.step)?.instruction.orEmpty(),color=Color.White,fontSize=20.sp)
                        Text(if(session.paused) "Paused · progress is retained" else "Elapsed ${formatTime(session.stepElapsedMs)} · completion is your decision",color=Muted,fontSize=12.sp)
                        session.stepTimerSeconds?.let { Text("Reminder: ${formatTime(it*1000)}",color=Muted) }
                        viewModel.timerNotice?.let { Text(it,color=GoldPrimary) }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick=viewModel::togglePause,modifier=Modifier.testTag("pause-cooking")){Text(if(session.paused) "Resume" else "Pause")}
                            TextButton(onClick=viewModel::repeatStep){Text("Repeat")}
                            TextButton(onClick={timerDialog=true}){Text("Timer")}
                        }
                        Button(onClick={haptic.performHapticFeedback(HapticFeedbackType.LongPress);viewModel.completeStep()},enabled=!session.paused,modifier=Modifier.fillMaxWidth().testTag("complete-step")) { Text(if(session.step==recipe.steps.lastIndex) "I completed the final step" else "I completed this step") }
                        if(session.step>0)TextButton(onClick=viewModel::previousStep){Text("Return to previous step")}
                    }
                    CookingState.DONE -> {
                        Text(if(session.completedAt==null) "Review your entries and confirm that you finished. A timer or camera label cannot establish that food is ready." else "This session is retained on your phone. Open History to review or export it.",color=Muted)
                        if(session.completedAt==null)Button(onClick=viewModel::markAsDone,modifier=Modifier.fillMaxWidth().testTag("save-session")){Text("Confirm completion & save")}
                        else Button(onClick=viewModel::startNewSession,modifier=Modifier.fillMaxWidth().testTag("new-session")){Text("Start a new session")}
                        if(session.completedAt==null)TextButton(onClick=viewModel::returnToCooking){Text("Go back to cooking")}
                    }
                    else -> Unit
                }
            }
        }
        if(session.completedAt==null && viewModel.cookingState!=CookingState.COOKING && viewModel.cookingState!=CookingState.DONE)item {
            SessionCard("Find a recipe",viewModel.recipeMessage) {
                Text("Confirmed: ${viewModel.confirmedIngredients.joinToString().ifBlank { "add ingredient identities first" }}",color=Muted,fontSize=12.sp)
                Button(onClick={viewModel.fetchRecipesForIngredients()},enabled=viewModel.confirmedIngredients.isNotEmpty() && !viewModel.isFetchingRecipes,modifier=Modifier.fillMaxWidth().testTag("search-recipes")) { Text(if(viewModel.isFetchingRecipes) "Searching…" else "Search Spoonacular") }
                TextButton(onClick={showGuides=!showGuides},modifier=Modifier.testTag("offline-guides")){Text(if(showGuides) "Hide built-in offline guides" else "Browse built-in offline guides")}
            }
        }
        if(viewModel.cookingState!=CookingState.COOKING && session.completedAt==null) {
            items(viewModel.onlineRecipes,key={"online-${it.id}"}) { r -> RecipeSelectionCard(r,recipe?.id==r.id) { viewModel.selectRecipe(r) } }
            if(showGuides)items(BUILT_IN_RECIPES.sortedByDescending { r -> viewModel.confirmedIngredients.count { name -> r.name.contains(name,true) || r.targets.any { it.ingredient.contains(name,true) } } },key={"builtin-${it.id}"}) { r -> RecipeSelectionCard(r,recipe?.id==r.id) { viewModel.selectRecipe(r) } }
        }
        if(viewModel.cookingState==CookingState.COOKING)item {
            SessionCard("Ask the cooking assistant","Online AI suggestions. Your saved instructions remain available offline.") {
                viewModel.messages.takeLast(6).forEach { CookChatBubble(it) }
                OutlinedTextField(chat,{chat=it},label={Text("Question about this step")},modifier=Modifier.fillMaxWidth())
                TextButton(onClick={viewModel.sendMessage(chat);chat=""},enabled=chat.isNotBlank() && !viewModel.isGenerating){Text(if(viewModel.isGenerating) "Asking…" else "Ask")}
            }
        }
        if(session.completedAt==null && (entries.isNotEmpty() || recipe!=null))item {
            OutlinedButton(onClick=viewModel::archiveDraftAndStartNew,modifier=Modifier.fillMaxWidth().testTag("archive-draft")) { Text("Save draft & start a new session") }
        }
        item { Text("Preparation → identity & quantity review → transfer confirmation → recipe steps → saved summary",color=Muted,fontSize=11.sp,modifier=Modifier.padding(vertical=10.dp)) }
    }
}

@Composable
private fun LiveGuideFrame(flow: kotlinx.coroutines.flow.StateFlow<ByteArray?>?, fallback: ByteArray?) {
    val bytes=if(flow==null)fallback else { val current by flow.collectAsStateWithLifecycle();current }
    val bitmap=remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it,0,it.size) } }
    if(bitmap!=null)Image(bitmap.asImageBitmap(),"Live Guide preview; no tracking boxes",Modifier.fillMaxWidth().height(240.dp).clipToBounds(),contentScale=ContentScale.Fit)
    else Text("No fresh camera frames. Check the Guide camera.",color=Muted)
}

@Composable
private fun IngredientDialog(editor: EntryEditor,onDismiss: ()->Unit,onSave: (String,Double?,QuantityUnit)->Unit) {
    var input by rememberSaveable(editor.requestId){mutableStateOf(editor.input)}
    var amount by rememberSaveable(editor.requestId){mutableStateOf(editor.amount)}
    var unit by rememberSaveable(editor.requestId){mutableStateOf(editor.unit)}
    val value=amount.trim().toDoubleOrNull()
    val valid=input.isNotBlank() && input.length<=240 && (amount.isBlank() || (value!=null && value.isFinite() && value>0 && value<=10_000_000 && (unit!=QuantityUnit.COUNT || value%1.0==0.0)))
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(editor.id==null) "Review ingredient" else "Correct entry")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Enter one ingredient, or comma-separated identities for a mixed batch. A batch has one combined quantity.",fontSize=13.sp)
        OutlinedTextField(input,{input=it},label={Text("Ingredient / batch contents")},modifier=Modifier.fillMaxWidth().semantics {testTagsAsResourceId=true}.testTag("ingredient-name"))
        OutlinedTextField(amount,{amount=it},keyboardOptions=KeyboardOptions(keyboardType=if(unit==QuantityUnit.COUNT)KeyboardType.Number else KeyboardType.Decimal),label={Text("Measured quantity (optional)")},isError=amount.isNotBlank() && !valid,singleLine=true,modifier=Modifier.fillMaxWidth().semantics {testTagsAsResourceId=true}.testTag("ingredient-amount"))
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { QuantityUnit.entries.forEach { choice -> FilterChip(selected=unit==choice,onClick={unit=choice},label={Text(choice.label)}) } }
        Text("Quantity source: your explicit manual entry. This confirms preparation, not transfer into the cooking vessel.",fontSize=12.sp)
    }},confirmButton={TextButton(enabled=valid,onClick={onSave(input.trim(),value,unit)},modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("save-ingredient")){Text(if(editor.id==null) "Confirm entry" else "Save correction")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
fun GuideConnectionDialog(onDismiss: ()->Unit,onConnect: (String,Int)->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val prefs=remember { context.getSharedPreferences("vescurus",0) }
    var host by rememberSaveable { mutableStateOf(prefs.getString("guideHost","").orEmpty()) }
    var port by rememberSaveable { mutableStateOf(prefs.getInt("guidePort",8766).toString()) }
    var code by rememberSaveable { mutableStateOf(prefs.getString("guidePairCode","").orEmpty()) }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Connect Guide")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Use the address shown on the Guide phone. Both phones need the same router or hotspot. Use an internet-capable network for cloud vision.")
        OutlinedTextField(host,{host=it},isError=host.isNotBlank() && !com.example.vescurus.network.isValidPeerHost(host),label={Text("Guide IP / hostname")},singleLine=true,modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("guide-host"))
        OutlinedTextField(port,{port=it},label={Text("Port")},singleLine=true,modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("guide-port"))
        OutlinedTextField(code,{code=it.filter(Char::isDigit).take(6)},label={Text("Six-digit Guide pairing code")},singleLine=true,modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("guide-code"))
    }},confirmButton={TextButton(enabled=com.example.vescurus.network.isValidPeerHost(host) && port.toIntOrNull() in 1..65535 && code.length==6,onClick={prefs.edit().putString("guideHost",host.trim()).putInt("guidePort",port.toInt()).putString("guidePairCode",code).apply();onConnect(host.trim(),port.toInt())},modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("guide-connect-confirm")){Text("Connect")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
fun RecipeSelectionCard(recipe: Recipe,isSelected: Boolean,onClick: ()->Unit) {
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth().testTag("recipe-${recipe.id}"),color=if(isSelected) Color(0xFF28311E) else SurfaceNavy,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,if(isSelected)GoldPrimary else Color.White.copy(alpha=.08f))) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            if(recipe.thumbnailUrl!=null)AsyncImage(recipe.thumbnailUrl,recipe.name,Modifier.size(64.dp),contentScale=ContentScale.Crop)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(recipe.name,color=Color.White,fontWeight=FontWeight.Bold,fontSize=16.sp)
                Text("${recipe.source}${if(recipe.savedAt!=null) " · saved on phone" else " · offline guide"}",color=GoldPrimary,fontSize=11.sp)
                Text("About ${recipe.totalTimeMs/60_000} min · ${recipe.steps.size} steps · ${recipe.servings?.let { "$it servings" } ?: "servings unspecified"}",color=Muted,fontSize=12.sp)
                Text(nutritionText(recipe),color=Muted,fontSize=11.sp)
            }
        }
    }
}

@Composable
fun CookChatBubble(msg: ChatMessage) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha=.04f),RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(msg.sender,color=GoldPrimary,fontSize=10.sp);Text(msg.message,color=Color.White,fontSize=13.sp)
    }
}
fun formatQuantity(value: Double): String = if(value%1.0==0.0)value.toLong().toString() else String.format(java.util.Locale.US,"%.2f",value).trimEnd('0').trimEnd('.')
fun formatTime(ms: Long): String = "${ms/60_000}:${(ms/1000%60).toString().padStart(2,'0')}"
fun nutritionText(r: Recipe): String = if(r.calories==null && r.proteinG==null && r.carbsG==null && r.fatsG==null) "Nutrition unavailable" else
    "Per recipe serving · ${r.calories?.let { "$it kcal" } ?: "calories unavailable"} · protein ${r.proteinG?.let { "${formatQuantity(it.toDouble())} g" } ?: "unavailable"}"
