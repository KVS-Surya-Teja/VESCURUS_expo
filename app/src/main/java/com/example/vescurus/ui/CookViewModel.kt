package com.example.vescurus.ui

import android.app.Application
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vescurus.BuildConfig
import com.example.vescurus.data.local.*
import com.example.vescurus.data.network.RecipeServiceException
import com.example.vescurus.data.repository.UniversalRecipeRepository
import com.example.vescurus.model.*
import com.example.vescurus.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.UUID

data class ChatMessage(val sender: String, val message: String,
    val id: String = UUID.randomUUID().toString(), val createdAt: Long = System.currentTimeMillis())
enum class CookingState { IDLE, RECIPE_SELECTION, COUNTDOWN, COOKING, DONE }

private fun assistantFailure(error: Exception, fallback: String): String = when((error as? com.example.vescurus.data.network.GeminiTextException)?.code) {
    "SERVICE_UNAVAILABLE" -> "The assistant is temporarily busy. Try again shortly; your recipe and ingredients are saved."
    "QUOTA_OR_RATE_LIMIT" -> "The assistant's request limit has been reached. Try later, or use a saved recipe."
    "NETWORK_FAILURE", "TIMEOUT" -> "Couldn't reach the assistant. Check internet and try again."
    "CREDENTIAL_FAILURE", "UNCONFIGURED", "MODEL_UNAVAILABLE" -> "The assistant couldn't connect. Saved and offline recipes are still available."
    else -> fallback
}

/** A bounded conversation with the current, confirmed cooking context. No camera frame is sent. */
internal fun cookingAssistantPrompt(session: CookingSession, messages: List<ChatMessage>): String = buildString {
    appendLine("You are VESCURUS, a helpful cooking assistant. Give concise, practical replies, usually two or three sentences.")
    appendLine("Use plain conversational sentences. Avoid display markup, internal identifiers, model names and technical jargon.")
    appendLine("Use the conversation to understand follow-up questions. Ask one useful question when details are missing.")
    appendLine("Honor the user's dietary restrictions, available time, serving goal, preferences and corrections in the conversation. They are optional; never invent them.")
    appendLine("Respect ingredient preparation (raw, fried, diced, julienne, etc.) when known; ask rather than guessing uncertain condition. Use the confirmed ingredients and amounts when proposing a recipe, and list any additional pantry ingredients explicitly.")
    appendLine("Small pantry seasonings/liquids may be optional unweighed additions; when oil, milk, butter or similar is a primary recipe ingredient, request its amount. Never treat an unknown amount as zero.")
    appendLine("When the user reports a cooking mistake or corrects an instruction, acknowledge it and adapt the remaining method toward the same dish and constraints. Explain if the original goal cannot reasonably be recovered; don't silently change goals or claim unsafe food is fixed.")
    appendLine("No camera image is attached to this chat. Do not claim to see the food, measure quantities, or verify doneness or safety.")
    appendLine("The following session is user-confirmed context. Recipe targets are not measured amounts. Keep g, ml and counts distinct.")
    appendLine("Recipe: ${session.recipe?.name ?: "Not chosen yet"}")
    appendLine("Cooking state: ${session.state}${if (session.paused) " (paused)" else ""}")
    session.recipe?.steps?.getOrNull(session.step)?.let {
        appendLine("Current step ${session.step + 1}: ${it.instruction}")
    }
    val activeEntries = session.entries.filter { it.status != EntryStatus.UNDONE }
    appendLine("Ingredient records:")
    if (activeEntries.isEmpty()) appendLine("None confirmed yet.")
    activeEntries.forEach { entry ->
        val quantity = entry.quantity?.let { "${it.value} ${it.unit.label}" } ?: "quantity unknown"
        val source = when (entry.quantitySource) {
            EvidenceSource.SCALE -> "scale record"
            EvidenceSource.MANUAL -> "entered by the user"
            else -> "no measurement recorded"
        }
        appendLine("- ${entry.label}${entry.ingredientPreparation?.let { " ($it)" } ?: ""}: $quantity ($source; ${entry.status}).${if (entry.identities.size > 1) " This is one combined batch, not a quantity for each ingredient." else ""}")
    }
    appendLine("Saved planning conversation (user questions/reports, not measurements; honor the latest correction):")
    session.planningNotes.forEach { appendLine("User: ${it.take(1500)}") }
    appendLine("Recent conversation:")
    messages.filter { it.sender != "System" }.takeLast(12).forEach {
        appendLine("${if (it.sender == "User") "User" else "Assistant"}: ${it.message.take(4000)}")
    }
    append("Reply to the latest user message.")
}

/** The sole session writer. Camera and peer state are evidence inputs, never automatic additions. */
class CookViewModel @JvmOverloads constructor(
    application: Application,
    directory: File = File(application.filesDir,"sessions"),
    private val recipePlanner: suspend (CookingSession, List<ChatMessage>) -> Recipe = { state, conversation ->
        com.example.vescurus.data.repository.CookingRecipePlanner().plan(state, conversation)
    },
    private val assistantResponder: suspend (String) -> String = {
        com.example.vescurus.data.network.GeminiTextService().generate(it)
    }
) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val store=SessionStore(directory)
    private val prefs=application.getSharedPreferences("vescurus",0)
    val scale = CookScale(prefs)
    private val repository=UniversalRecipeRepository()
    private val writes=Channel<Pair<Long,LocalState>>(Channel.CONFLATED)
    private var saveGeneration=0L
    var session by mutableStateOf(CookingSession()); private set
    var sessions by mutableStateOf<List<CookingSession>>(emptyList()); private set
    var failedChatQuestion by mutableStateOf<String?>(null); private set
    var isLoading by mutableStateOf(true); private set
    var isSaving by mutableStateOf(false); private set
    var storageMessage by mutableStateOf<String?>(null); private set
    var recipeMessage by mutableStateOf("Find recipes for your ingredients, or choose an offline guide."); private set
    var isFetchingRecipes by mutableStateOf(false); private set
    var onlineRecipes by mutableStateOf<List<Recipe>>(emptyList()); private set
    var isDemo by mutableStateOf(false); private set
    var timerNotice by mutableStateOf<String?>(null); private set
    private val _history=MutableStateFlow<List<CookHistoryItem>>(emptyList())
    val history: StateFlow<List<CookHistoryItem>> = _history
    private val _availableRecipes=MutableStateFlow<List<Recipe>>(emptyList())
    val availableRecipes: StateFlow<List<Recipe>> = _availableRecipes
    val messages=mutableStateListOf<ChatMessage>()
    var isGenerating by mutableStateOf(false); private set
    var recognitionInProgress by mutableStateOf(false); private set
    var recognitionMessage by mutableStateOf<String?>(null); private set
    private var recognitionJob: Job?=null
    private var lastRecognitionKey: String?=null
    fun beginIngredientRecognition(): Boolean {
        if(isLoading || recognitionInProgress || session.completedAt!=null)return false
        recognitionInProgress=true;recognitionMessage="Looking at the ingredient…"
        recognitionJob?.cancel()
        recognitionJob=viewModelScope.launch {
            delay(25000)
            if(recognitionInProgress) {
                recognitionInProgress=false
                recognitionMessage="No result received. Check the camera phone and try again."
            }
        }
        return true
    }
    fun receiveIngredientObservation(observation: Observation) {
        if(isLoading || session.completedAt != null || observation.sessionId != session.id) return
        val key="${observation.sessionId}/${observation.streamId}/${observation.frameId}"
        if(lastRecognitionKey==key)return
        lastRecognitionKey=key;recognitionJob?.cancel();recognitionInProgress=false
        val foods=observation.detections.filter { it.supported && it.confidence>=.5f }.distinctBy { it.label }
        val text=when {
            observation.status=="POOR_IMAGE" -> "Move closer and improve the lighting, then try again."
            observation.status=="QUOTA_OR_RATE_LIMIT" -> "Recognition is busy or has reached its request limit. Try later, or enter the ingredient manually."
            observation.status in setOf("UNCONFIGURED","CREDENTIAL_FAILURE","MODEL_UNAVAILABLE") -> "Recognition couldn't connect. You can enter the ingredient manually."
            observation.status!="SUCCESS" -> "Recognition is unavailable right now. Check internet and try again, or enter the ingredient manually."
            foods.isEmpty() -> "No food found. Place one ingredient clearly in view and try again."
            else -> foods.joinToString("; ") { "Identified ${it.label} with ${(it.confidence*100).toInt()}% confidence" }+
                ". Confirm the name, then weigh it."
        }
        recognitionMessage=text;appendMessage(ChatMessage("Assistant",text));speak(text)
    }
    fun renameCompletedSession(id: String,title: String) {
        val name=title.trim().take(120);if(name.isEmpty())return
        sessions=sessions.map { if(it.id==id && it.completedAt!=null)it.copy(historyTitle=name,version=it.version+1) else it }
        if(session.id==id && session.completedAt!=null)session=session.copy(historyTitle=name,version=session.version+1)
        syncHistory();persist()
    }
    fun attachFinalPhoto(id: String,bytes: ByteArray) {
        if(session.id!=id || session.completedAt==null || bytes.isEmpty() || bytes.size>3_000_000)return
        val folder=File(getApplication<Application>().filesDir,"finished-photos").apply { mkdirs() }
        val file=File(folder,"${session.id}.jpg")
        runCatching { file.writeBytes(bytes) }.onSuccess {
            session=session.copy(finalPhotoPath=file.absolutePath,finalPhotoCapturedAt=System.currentTimeMillis(),version=session.version+1)
            sessions=sessions.map { if(it.id==id)session else it };persist()
        }
    }
    private var fetchJob: Job?=null
    private var fetchGeneration=0L
    private var loadJob: Job?=null
    private var timerJob: Job?=null
    private var chatJob: Job?=null
    private var chatGeneration=0L
    private var modeInitialized=false
    private var naturalVoice = runCatching {
        application.packageManager.getPackageInfo("dev.vescurus.voice", 0)
    }.isSuccess
    private var voice: TextToSpeech? = if (naturalVoice) {
        runCatching { TextToSpeech(application, this, "dev.vescurus.voice") }
            .getOrElse {
                naturalVoice = false
                android.util.Log.w("VescurusVoice", "Offline voice could not bind; using phone voice", it)
                createPhoneVoice()
            }
    } else createPhoneVoice()
    private var voiceReady=false
    var isTtsEnabled by mutableStateOf(prefs.getBoolean("voice",false))
        private set
    val sessionId get()=session.id
    val sessionVersion get()=session.version
    val confirmedIngredients get()=session.ingredientNames
    val cookingState get()=runCatching { CookingState.valueOf(session.state) }.getOrDefault(CookingState.IDLE)
    val selectedRecipe get()=session.recipe
    val currentStep get()=session.step
    val countdownValue get()=0
    val currentProgressMs get()=(session.recipe?.steps?.getOrNull(session.step)?.startTimeMs ?: 0)+session.stepElapsedMs
    val timerExpired get()=session.stepTimerSeconds?.let { session.stepElapsedMs>=it*1000 } ?: false
    val sessionContext get()=SessionContext(session.id,session.version,session.recipe?.name,session.step,session.paused,session.ingredientNames)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for((revision,state) in writes) {
                try { store.save(state,state.active.demonstration); withContext(Dispatchers.Main) { if(revision==saveGeneration) { isSaving=false;storageMessage=null } } }
                catch (_: Exception) { withContext(Dispatchers.Main) { if(revision==saveGeneration) { isSaving=false;storageMessage="Could not save on this phone. Free storage space and retry before closing." } } }
            }
        }
        setDemonstration(false)
    }
    fun setDemonstration(demo: Boolean) {
        if(modeInitialized && demo==isDemo) return
        modeInitialized=true;isDemo=demo;isLoading=true
        resetChat()
        loadJob?.cancel();timerJob?.cancel();fetchJob?.cancel()
        loadJob=viewModelScope.launch {
            val loaded=withContext(Dispatchers.IO) { store.load(demo) }
            val savedRecipes=withContext(Dispatchers.IO) { store.loadRecipes().filter { it.id.startsWith("ai-") || com.example.vescurus.data.network.RecipeMapper.panCompatible(it) } }
            session=loaded.active.copy(paused=loaded.active.state=="COOKING" || loaded.active.paused)
            sessions=loaded.history;restoreConversation();onlineRecipes=savedRecipes
            _availableRecipes.value=savedRecipes
            storageMessage=store.recoveryMessage;syncHistory();isLoading=false;persist()
        }
    }
    private fun restoreConversation() {
        messages.clear()
        messages.addAll(session.conversation.map { ChatMessage(it.sender, it.message, it.id, it.createdAt) })
    }
    private fun appendMessage(message: ChatMessage) {
        if(isLoading || session.conversation.any { it.id == message.id }) return
        messages.add(message)
        session=session.copy(conversation=session.conversation + SessionChatMessage(
            message.id, message.sender, message.message, message.createdAt),
            version=session.version+1, updatedAt=System.currentTimeMillis())
        sessions=sessions.map { if(it.id == session.id) session else it }
        persist()
    }
    private fun syncHistory() { _history.value=sessions.filter { it.completedAt!=null }.mapNotNull { s -> s.recipe?.let { r ->
        CookHistoryItem(s.id,r.id,r.name,s.completedAt!!,r.calories,r.proteinG,r.carbsG,r.fatsG) } } }
    private fun persist() { if(isLoading)return;isSaving=true;saveGeneration++;writes.trySend(saveGeneration to LocalState(active=session,history=sessions)) }
    fun retrySave()=persist()
    private fun update(new: CookingSession) {
        if(isLoading || session.completedAt!=null)return
        session=new.copy(version=session.version+1,updatedAt=System.currentTimeMillis());persist()
    }
    fun confirmIngredient(label: String) = addIngredient(label,null,QuantityUnit.GRAMS)
    fun addIngredient(input: String, amount: Double?, unit: QuantityUnit, vision: VisionEvidence?=null, eventId: String=UUID.randomUUID().toString(), condition: String?=null,
        measurement: ScaleMeasurementGate.Measurement? = null) {
        if(isLoading || session.completedAt!=null)return
        val identities=input.split(',','+').mapNotNull(::canonicalizeIngredientLabel).filter { it!="Unsupported object" && it.length<=80 }.distinct()
        if(identities.isEmpty())return
        if (measurement != null && (scale.measurement != measurement || session.entries.any { it.id == measurement.id })) return
        val q = if (measurement != null) Quantity(measurement.grams, QuantityUnit.GRAMS)
            else amount?.let { Quantity(it,unit) }
        val evidence = measurement?.let {
            ScaleEvidence(it.deviceId, it.bootId, it.finalSequence, it.tareEpoch, it.calibrationEpoch,
                it.baselineGrams, it.baselineGrams + it.grams)
        }
        val entry=IngredientEntry(id=measurement?.id ?: eventId,sessionId=session.id,identities=identities,
            identitySource=if(identities.size>1) EvidenceSource.MIXED else if(vision!=null) EvidenceSource.VISION_CONFIRMED else EvidenceSource.MANUAL,
            quantity=q,quantitySource=when { measurement != null -> EvidenceSource.SCALE; q != null -> EvidenceSource.MANUAL; else -> null },scale=evidence,vision=vision,ingredientPreparation=condition?.trim()?.take(120)?.takeIf { it.isNotEmpty() })
        val next=SessionReducer.add(session,entry,System.currentTimeMillis())
        if(next!=session) { session=next;persist();recipeMessage="Ready to find recipes for these ingredients." }
    }
    fun correctEntry(id: String,input: String,amount: Double?,unit: QuantityUnit,condition: String?=null) {
        val names=input.split(',','+').mapNotNull(::canonicalizeIngredientLabel).filter { it!="Unsupported object" && it.length<=80 }.distinct()
        if(names.isEmpty() || session.completedAt!=null)return
        session=SessionReducer.edit(session,id,names,amount?.let { Quantity(it,unit) },if(amount!=null) EvidenceSource.MANUAL else null,System.currentTimeMillis(),condition?.trim()?.take(120)?.takeIf { it.isNotEmpty() });persist()
    }
    fun undoEntry(id: String) { if(session.completedAt==null) { session=SessionReducer.undo(session,id,System.currentTimeMillis());persist() } }
    fun confirmTransfer(id: String) { if(session.completedAt==null) { session=SessionReducer.transfer(session,id,System.currentTimeMillis());persist() } }
    fun startRecipeSelection(detectedIngredients: List<String> = confirmedIngredients) {
        update(session.copy(state="RECIPE_SELECTION"))
        if(detectedIngredients.isNotEmpty()) fetchRecipesForIngredients(detectedIngredients)
    }
    private fun cancelRecipeWork() {
        fetchGeneration++;fetchJob?.cancel();fetchJob=null;isFetchingRecipes=false
    }
    private fun ownsRecipeRequest(owner: String, token: Long): Boolean =
        session.id==owner && fetchGeneration==token && !isLoading && session.completedAt==null &&
            cookingState!=CookingState.COOKING && cookingState!=CookingState.DONE

    fun planRecipeForIngredients() {
        if(isFetchingRecipes || isLoading || session.completedAt!=null || cookingState==CookingState.COOKING || cookingState==CookingState.DONE || confirmedIngredients.isEmpty())return
        val snapshot=session;val conversation=messages.toList();val owner=session.id;val token=++fetchGeneration
        fetchJob=viewModelScope.launch {
            isFetchingRecipes=true;recipeMessage="Planning with your ingredients and preferences…"
            appendMessage(ChatMessage("Assistant",recipeMessage))
            try {
                val planned=recipePlanner(snapshot,conversation)
                if(!ownsRecipeRequest(owner,token))return@launch
                if(session.entries!=snapshot.entries || session.planningNotes!=snapshot.planningNotes) {
                    recipeMessage="Your ingredients or preferences changed. Find recipes again for an updated plan."
                    appendMessage(ChatMessage("Assistant",recipeMessage));return@launch
                }
                update(session.copy(recipe=planned,state="RECIPE_SELECTION",step=0,stepElapsedMs=0,paused=false,stepTimerSeconds=null))
                onlineRecipes=(listOf(planned)+onlineRecipes).distinctBy { it.id };_availableRecipes.value=onlineRecipes
                val recipesToSave=onlineRecipes
                withContext(Dispatchers.IO) { store.saveRecipes(recipesToSave) }
                if(!ownsRecipeRequest(owner,token))return@launch
                recipeMessage="Your recipe is ready. Review it, or tell me what to change."
                val reply="${planned.name}. ${planned.description} Tell me any changes before starting."
                appendMessage(ChatMessage("Assistant",reply));speak(reply)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(ownsRecipeRequest(owner,token)) {
                    recipeMessage=if(e is com.example.vescurus.data.repository.RecipePlanningQuestion) e.question else assistantFailure(e,"I couldn't plan a recipe just now. Try again, or browse saved recipes.")
                    appendMessage(ChatMessage("Assistant",recipeMessage))
                }
            } finally { if(fetchGeneration==token)isFetchingRecipes=false }
        }
    }
    fun fetchRecipesForIngredients(ingredients: List<String> = confirmedIngredients) {
        if(isFetchingRecipes || ingredients.isEmpty() || isLoading || cookingState==CookingState.COOKING || cookingState==CookingState.DONE || session.completedAt!=null)return
        val names=ingredients.distinct().toList();val owner=session.id;val token=++fetchGeneration
        fetchJob=viewModelScope.launch {
            isFetchingRecipes=true;recipeMessage="Finding recipes for your ingredients…"
            try {
                val found=repository.generateRecipesForIngredients(names)
                if(!ownsRecipeRequest(owner,token))return@launch
                if(confirmedIngredients.toSet()!=names.toSet()) {
                    recipeMessage="Your ingredients changed. Search again for updated suggestions.";return@launch
                }
                onlineRecipes=found;_availableRecipes.value=found
                recipeMessage=if(found.isEmpty()) "No matches yet. Try different ingredients or an offline guide." else "${found.size} recipes from Spoonacular."
                if(found.isNotEmpty())withContext(Dispatchers.IO) { store.saveRecipes(found) }
            } catch(e: CancellationException) { throw e
            } catch(e: Exception) {
                if(ownsRecipeRequest(owner,token))recipeMessage=when((e as? RecipeServiceException)?.reason) {
                    "UNCONFIGURED" -> "Online recipes aren't set up yet. Offline guides are ready."
                    "CREDENTIALS" -> "Online recipes couldn't connect. Please check the service setup."
                    "QUOTA_EXHAUSTED" -> "Online recipe limit reached. Choose a saved or offline guide."
                    "RATE_LIMITED" -> "Recipe search is busy. Try again shortly."
                    else -> "Online recipes are unavailable. Choose a saved or offline guide."
                }
            } finally { if(fetchGeneration==token)isFetchingRecipes=false }
        }
    }
    fun selectRecipe(recipe: Recipe) {
        if(cookingState==CookingState.COOKING || session.completedAt!=null)return
        cancelRecipeWork()
        update(session.copy(recipe=recipe,state="RECIPE_SELECTION",step=0,stepElapsedMs=0,paused=false,stepTimerSeconds=null))
    }
    fun startCooking() {
        if(session.recipe==null || session.completedAt!=null || cookingState==CookingState.COOKING)return
        cancelRecipeWork()
        update(session.copy(state="COOKING",paused=false,cookingStartedAt=session.cookingStartedAt ?: System.currentTimeMillis()));startTimer();announceStep()
    }
    fun togglePause() {
        if(cookingState!=CookingState.COOKING)return
        val pause=!session.paused;update(session.copy(paused=pause))
        if(pause)timerJob?.cancel() else startTimer()
    }
    fun pauseForBackground() { if(cookingState==CookingState.COOKING && !session.paused)togglePause() }
    fun setStepTimer(seconds: Long?) { require(seconds==null || seconds in 1..86400);timerNotice=null;update(session.copy(stepTimerSeconds=seconds,stepElapsedMs=0)) }
    fun completeStep() {
        val recipe=session.recipe ?: return
        if(cookingState!=CookingState.COOKING || session.paused)return
        timerNotice=null
        if(session.step>=recipe.steps.lastIndex) { timerJob?.cancel();update(session.copy(state="DONE",paused=false)) }
        else { update(session.copy(step=session.step+1,stepElapsedMs=0,stepTimerSeconds=null));announceStep() }
    }
    fun returnToCooking() { if(session.completedAt==null)update(session.copy(state="COOKING",paused=true)) }
    fun previousStep() {
        if(session.step==0 || session.completedAt!=null)return
        update(session.copy(state="COOKING",step=session.step-1,stepElapsedMs=0,stepTimerSeconds=null,paused=true));timerJob?.cancel()
    }
    private fun announceStep() { session.recipe?.steps?.getOrNull(session.step)?.let { speak(it.instruction) } }
    fun repeatStep()=announceStep()
    private fun startTimer() {
        timerJob?.cancel()
        timerJob=viewModelScope.launch {
            var last=SystemClock.elapsedRealtime();var saveAt=last
            while(isActive && cookingState==CookingState.COOKING && !session.paused) {
                delay(1000)
                val now=SystemClock.elapsedRealtime()
                session=session.copy(stepElapsedMs=session.stepElapsedMs+now-last,activeCookingMs=session.activeCookingMs+now-last);last=now
                if(timerExpired && timerNotice==null) { timerNotice="Timer ended. Check this step and confirm when ready.";speak(timerNotice!!) }
                if(now-saveAt>=5000) { persist();saveAt=now }
            }
        }
    }
    fun markAsDone() {
        if(cookingState!=CookingState.DONE || session.recipe==null || session.completedAt!=null)return
        session=session.copy(completedAt=System.currentTimeMillis(),version=session.version+1,updatedAt=System.currentTimeMillis())
        sessions=(sessions.filterNot { it.id==session.id }+session);syncHistory();persist();timerJob?.cancel()
    }
    fun startNewSession() {
        if(session.completedAt==null && (session.entries.isNotEmpty() || session.recipe!=null || session.conversation.isNotEmpty()))return
        timerJob?.cancel();resetChat();session=CookingSession(demonstration=isDemo);persist()
    }
    fun archiveDraftAndStartNew() {
        if(isLoading)return
        timerJob?.cancel()
        if(session.completedAt==null && (session.entries.isNotEmpty() || session.recipe!=null || session.conversation.isNotEmpty())) {
            val draft=session.copy(paused=true,updatedAt=System.currentTimeMillis(),version=session.version+1)
            sessions=sessions.filterNot { it.id==draft.id }+draft
        }
        resetChat();session=CookingSession(demonstration=isDemo);syncHistory();persist()
    }
    fun resumeDraft(id: String) {
        val draft=sessions.find { it.id==id && it.completedAt==null } ?: return
        timerJob?.cancel();resetChat()
        val current=session
        sessions=sessions.filterNot { it.id==id }
        if(current.completedAt==null && (current.entries.isNotEmpty() || current.recipe!=null || current.conversation.isNotEmpty())) sessions=sessions.filterNot { it.id==current.id }+current.copy(paused=true)
        session=draft.copy(paused=true,version=draft.version+1,updatedAt=System.currentTimeMillis());restoreConversation();syncHistory();persist()
    }
    fun exportJson(): String = store.export(LocalState(active=session,history=sessions))
    fun toggleVoice() { isTtsEnabled=!isTtsEnabled;if(!isTtsEnabled)voice?.stop();prefs.edit().putBoolean("voice",isTtsEnabled).apply() }
    private fun resetChat() {
        cancelRecipeWork();recipeMessage="Find recipes for your ingredients, or choose an offline guide."
        chatGeneration++;chatJob?.cancel();chatJob=null;isGenerating=false;messages.clear();failedChatQuestion=null;voice?.stop();recognitionJob?.cancel();recognitionInProgress=false;recognitionMessage=null;lastRecognitionKey=null
    }
    fun sendMessage(text: String) = requestAssistantReply(text.trim(), appendUser = true)
    fun retryAssistantReply() {
        val question = failedChatQuestion ?: return
        requestAssistantReply(question, appendUser = false)
    }
    private fun requestAssistantReply(question: String, appendUser: Boolean) {
        if(isGenerating || isLoading || question.isEmpty())return
        failedChatQuestion=null
        if(appendUser && session.completedAt==null && cookingState!=CookingState.COOKING && cookingState!=CookingState.DONE)
            update(session.copy(planningNotes=(session.planningNotes+question.take(1500)).takeLast(20)))
        if(appendUser) appendMessage(ChatMessage("User",question))
        isGenerating=true
        val requestGeneration=chatGeneration
        val requestSessionId=session.id
        val prompt=cookingAssistantPrompt(session,messages.toList()) +
            if(appendUser) "" else "\nRetry this unanswered user question in the current session context: ${question.take(4000)}"
        chatJob=viewModelScope.launch {
            try {
                val response=assistantResponder(prompt)
                if(requestGeneration==chatGeneration && requestSessionId==session.id) {
                    val reply=response.takeIf { it.isNotBlank() }
                    if(reply==null) {
                        failedChatQuestion=question
                        appendMessage(ChatMessage("System","No reply came back. Please try again."))
                    }
                    else { appendMessage(ChatMessage("AI suggestion",reply));speak(reply) }
                }
            } catch(e: CancellationException) { throw e
            } catch(e: Exception) {
                if(requestGeneration==chatGeneration && requestSessionId==session.id) {
                    failedChatQuestion=question
                    appendMessage(ChatMessage("System",assistantFailure(e,"The assistant couldn't reply. Try again in a moment.")))
                }
            } finally { if(requestGeneration==chatGeneration)isGenerating=false }
        }
    }
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (naturalVoice) usePhoneVoice()
            return
        }
        if (naturalVoice) {
            // A public framework voice name selects the model and speaker together.
            // Do not silently pick a different installed model or speaker.
            val selected = voice?.voices?.firstOrNull {
                it.name == "kitten-nano-en-v0_8-fp32#5#expr-voice-4-f"
            }
            if (selected == null || voice?.setVoice(selected) != TextToSpeech.SUCCESS) {
                usePhoneVoice()
                return
            }
            voiceReady = true
        } else {
            voiceReady = (voice?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
        }
        voice?.setSpeechRate(1.1f)
        android.util.Log.i("VescurusVoice", when {
            naturalVoice && voiceReady -> "Offline voice ready: speaker 5, speed 1.1"
            voiceReady -> "Phone voice ready: speed 1.1"
            else -> "Voice unavailable; written guidance remains available"
        })
    }
    private fun createPhoneVoice(): TextToSpeech? {
        val application = getApplication<Application>()
        val engines = application.packageManager.queryIntentServices(
            android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0
        ).map { it.serviceInfo.packageName }.distinct()
        val provider = engines.firstOrNull { it == "com.google.android.tts" }
            ?: engines.firstOrNull {
                it !in setOf("dev.vescurus.voice", "dev.ahmedmohamed.hayaitts", "com.k2fsa.sherpa.onnx.tts.engine")
            }
        if (provider == null) return null
        return runCatching { TextToSpeech(application, this, provider) }.getOrElse {
            android.util.Log.w("VescurusVoice", "Phone voice could not bind; written guidance remains available", it)
            null
        }
    }
    private fun usePhoneVoice() {
        voiceReady = false
        naturalVoice = false
        voice?.stop()
        voice?.shutdown()
        android.util.Log.i("VescurusVoice", "Offline voice unavailable; using phone voice")
        voice = createPhoneVoice()
    }
    internal fun announceWeight(grams: Double) = speak("Measured ${scaleGrams(grams)}. Confirm to save the amount.")
    private fun speak(text: String) { if(isTtsEnabled && voiceReady)voice?.speak(spokenGuidance(text),TextToSpeech.QUEUE_FLUSH,null,"vescurus-step") }
    override fun onCleared() { timerJob?.cancel();voice?.stop();voice?.shutdown();writes.close();super.onCleared() }
}
