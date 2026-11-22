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
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.UUID

data class ChatMessage(val sender: String, val message: String)
enum class CookingState { IDLE, RECIPE_SELECTION, COUNTDOWN, COOKING, DONE }

/** A bounded conversation with the current, confirmed cooking context. No camera frame is sent. */
internal fun cookingAssistantPrompt(session: CookingSession, messages: List<ChatMessage>): String = buildString {
    appendLine("You are VESCURUS, a helpful cooking assistant. Give concise, practical replies, usually two or three sentences.")
    appendLine("Use the conversation to understand follow-up questions. Ask one useful question when details are missing.")
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
        appendLine("- ${entry.label}: $quantity ($source; ${entry.status}).${if (entry.identities.size > 1) " This is one combined batch, not a quantity for each ingredient." else ""}")
    }
    appendLine("Recent conversation:")
    messages.filter { it.sender != "System" }.takeLast(12).forEach {
        appendLine("${if (it.sender == "User") "User" else "Assistant"}: ${it.message.take(4000)}")
    }
    append("Reply to the latest user message.")
}

/** The sole session writer. Camera and peer state are evidence inputs, never automatic additions. */
class CookViewModel @JvmOverloads constructor(application: Application, directory: File = File(application.filesDir,"sessions")) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val store=SessionStore(directory)
    private val prefs=application.getSharedPreferences("vescurus",0)
    private val repository=UniversalRecipeRepository()
    private val writes=Channel<Pair<Long,LocalState>>(Channel.CONFLATED)
    private var saveGeneration=0L
    var session by mutableStateOf(CookingSession()); private set
    var sessions by mutableStateOf<List<CookingSession>>(emptyList()); private set
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
    private var fetchJob: Job?=null
    private var loadJob: Job?=null
    private var timerJob: Job?=null
    private var chatJob: Job?=null
    private var chatGeneration=0L
    private var modeInitialized=false
    private var voice: TextToSpeech?=TextToSpeech(application,this)
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
            val savedRecipes=withContext(Dispatchers.IO) { store.loadRecipes().filter(com.example.vescurus.data.network.RecipeMapper::panCompatible) }
            session=loaded.active.copy(paused=loaded.active.state=="COOKING" || loaded.active.paused)
            sessions=loaded.history;onlineRecipes=savedRecipes
            _availableRecipes.value=savedRecipes
            storageMessage=store.recoveryMessage;syncHistory();isLoading=false;persist()
        }
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
    fun addIngredient(input: String, amount: Double?, unit: QuantityUnit, vision: VisionEvidence?=null, eventId: String=UUID.randomUUID().toString()) {
        if(isLoading || session.completedAt!=null)return
        val identities=input.split(',','+').mapNotNull(::canonicalizeIngredientLabel).filter { it!="Unsupported object" && it.length<=80 }.distinct()
        if(identities.isEmpty())return
        val q=amount?.let { Quantity(it,unit) }
        val entry=IngredientEntry(id=eventId,sessionId=session.id,identities=identities,
            identitySource=if(identities.size>1) EvidenceSource.MIXED else if(vision!=null) EvidenceSource.VISION_CONFIRMED else EvidenceSource.MANUAL,
            quantity=q,quantitySource=if(q!=null) EvidenceSource.MANUAL else null,vision=vision)
        val next=SessionReducer.add(session,entry,System.currentTimeMillis())
        if(next!=session) { session=next;persist();recipeMessage="Ready to find recipes for these ingredients." }
    }
    fun correctEntry(id: String,input: String,amount: Double?,unit: QuantityUnit) {
        val names=input.split(',','+').mapNotNull(::canonicalizeIngredientLabel).filter { it!="Unsupported object" && it.length<=80 }.distinct()
        if(names.isEmpty() || session.completedAt!=null)return
        session=SessionReducer.edit(session,id,names,amount?.let { Quantity(it,unit) },if(amount!=null) EvidenceSource.MANUAL else null,System.currentTimeMillis());persist()
    }
    fun undoEntry(id: String) { if(session.completedAt==null) { session=SessionReducer.undo(session,id,System.currentTimeMillis());persist() } }
    fun confirmTransfer(id: String) { if(session.completedAt==null) { session=SessionReducer.transfer(session,id,System.currentTimeMillis());persist() } }
    fun startRecipeSelection(detectedIngredients: List<String> = confirmedIngredients) {
        update(session.copy(state="RECIPE_SELECTION"))
        if(detectedIngredients.isNotEmpty()) fetchRecipesForIngredients(detectedIngredients)
    }
    fun fetchRecipesForIngredients(ingredients: List<String> = confirmedIngredients) {
        if(isFetchingRecipes || ingredients.isEmpty() || isLoading)return
        val names=ingredients.distinct().toList()
        fetchJob=viewModelScope.launch {
            isFetchingRecipes=true;recipeMessage="Finding recipes for your ingredients…"
            try {
                val found=repository.generateRecipesForIngredients(names)
                onlineRecipes=found;_availableRecipes.value=found
                recipeMessage=if(confirmedIngredients.toSet()!=names.toSet()) "Your ingredients changed. Search again for updated suggestions." else if(found.isEmpty()) "No matches yet. Try different ingredients or an offline guide." else "${found.size} recipes from Spoonacular."
                if(found.isNotEmpty())withContext(Dispatchers.IO) { store.saveRecipes(found) }
            } catch(e: CancellationException) { throw e
            } catch(e: Exception) {
                recipeMessage=when((e as? RecipeServiceException)?.reason) {
                    "UNCONFIGURED" -> "Online recipes aren't set up yet. Offline guides are ready."
                    "CREDENTIALS" -> "Online recipes couldn't connect. Please check the service setup."
                    "QUOTA_EXHAUSTED" -> "Online recipe limit reached. Choose a saved or offline guide."
                    "RATE_LIMITED" -> "Recipe search is busy. Try again shortly."
                    else -> "Online recipes are unavailable. Choose a saved or offline guide."
                }
            } finally { isFetchingRecipes=false }
        }
    }
    fun selectRecipe(recipe: Recipe) {
        if(cookingState==CookingState.COOKING || session.completedAt!=null)return
        update(session.copy(recipe=recipe,state="RECIPE_SELECTION",step=0,stepElapsedMs=0,paused=false,stepTimerSeconds=null))
    }
    fun startCooking() {
        if(session.recipe==null || session.completedAt!=null || cookingState==CookingState.COOKING)return
        update(session.copy(state="COOKING",paused=false));startTimer();announceStep()
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
                session=session.copy(stepElapsedMs=session.stepElapsedMs+now-last);last=now
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
        if(session.completedAt==null && (session.entries.isNotEmpty() || session.recipe!=null))return
        timerJob?.cancel();resetChat();session=CookingSession(demonstration=isDemo);persist()
    }
    fun archiveDraftAndStartNew() {
        if(isLoading)return
        timerJob?.cancel()
        if(session.completedAt==null && (session.entries.isNotEmpty() || session.recipe!=null)) {
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
        if(current.completedAt==null && (current.entries.isNotEmpty() || current.recipe!=null)) sessions=sessions.filterNot { it.id==current.id }+current.copy(paused=true)
        session=draft.copy(paused=true,version=draft.version+1,updatedAt=System.currentTimeMillis());syncHistory();persist()
    }
    fun exportJson(): String = store.export(LocalState(active=session,history=sessions))
    fun toggleVoice() { isTtsEnabled=!isTtsEnabled;if(!isTtsEnabled)voice?.stop();prefs.edit().putBoolean("voice",isTtsEnabled).apply() }
    private fun resetChat() {
        chatGeneration++;chatJob?.cancel();chatJob=null;isGenerating=false;messages.clear();voice?.stop()
    }
    fun sendMessage(text: String) {
        val question=text.trim()
        if(isGenerating || isLoading || question.isEmpty())return
        messages.add(ChatMessage("User",question));isGenerating=true
        val requestGeneration=chatGeneration
        val requestSessionId=session.id
        val prompt=cookingAssistantPrompt(session,messages.toList())
        chatJob=viewModelScope.launch {
            try {
                val model=GenerativeModel(BuildConfig.GEMINI_MODEL,BuildConfig.GEMINI_API_KEY)
                val response=model.generateContent(prompt)
                if(requestGeneration==chatGeneration && requestSessionId==session.id) {
                    val reply=response.text?.takeIf { it.isNotBlank() }
                    if(reply==null) messages.add(ChatMessage("System","No reply came back. Please try again."))
                    else { messages.add(ChatMessage("AI suggestion",reply));speak(reply) }
                }
            } catch(e: CancellationException) { throw e
            } catch(_: Exception) {
                if(requestGeneration==chatGeneration && requestSessionId==session.id)
                    messages.add(ChatMessage("System","The assistant couldn't reply. Try again in a moment."))
            } finally { if(requestGeneration==chatGeneration)isGenerating=false }
        }
    }
    override fun onInit(status: Int) { if(status==TextToSpeech.SUCCESS) { voice?.language=Locale.US;voiceReady=true } }
    private fun speak(text: String) { if(isTtsEnabled && voiceReady)voice?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"vescurus-step") }
    override fun onCleared() { timerJob?.cancel();voice?.stop();voice?.shutdown();writes.close();super.onCleared() }
}
