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
    var recipeMessage by mutableStateOf("Choose ingredients, then search online or open an offline guide."); private set
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
        if(next!=session) { session=next;persist();recipeMessage="Ingredients changed. Refresh suggestions when ready." }
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
            isFetchingRecipes=true;recipeMessage="Searching Spoonacular for confirmed ingredients…"
            try {
                val found=repository.generateRecipesForIngredients(names)
                onlineRecipes=found;_availableRecipes.value=found
                recipeMessage=if(confirmedIngredients.toSet()!=names.toSet()) "Results are for the earlier ingredient list. Search again to include your new ingredients." else if(found.isEmpty()) "No matching pan recipes returned. Saved and built-in guides remain available." else "${found.size} recipes retrieved from Spoonacular. Targets are separate from your measured quantities."
                if(found.isNotEmpty())withContext(Dispatchers.IO) { store.saveRecipes(found) }
            } catch(e: CancellationException) { throw e
            } catch(e: Exception) {
                recipeMessage=when((e as? RecipeServiceException)?.reason) {
                    "UNCONFIGURED" -> "Spoonacular key is not configured. Use a built-in or saved guide."
                    "CREDENTIALS" -> "Spoonacular rejected the credentials. Your ingredients are saved locally."
                    "QUOTA_EXHAUSTED" -> "Spoonacular quota is exhausted. Use saved or built-in guides."
                    "RATE_LIMITED" -> "Spoonacular is rate limiting requests. Try later; ingredients are retained."
                    else -> "Online recipe search is unavailable. Saved and built-in guides remain usable."
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
        timerJob?.cancel();messages.clear();session=CookingSession(demonstration=isDemo);persist()
    }
    fun archiveDraftAndStartNew() {
        if(isLoading)return
        timerJob?.cancel()
        if(session.completedAt==null && (session.entries.isNotEmpty() || session.recipe!=null)) {
            val draft=session.copy(paused=true,updatedAt=System.currentTimeMillis(),version=session.version+1)
            sessions=sessions.filterNot { it.id==draft.id }+draft
        }
        session=CookingSession(demonstration=isDemo);messages.clear();syncHistory();persist()
    }
    fun resumeDraft(id: String) {
        val draft=sessions.find { it.id==id && it.completedAt==null } ?: return
        timerJob?.cancel()
        val current=session
        sessions=sessions.filterNot { it.id==id }
        if(current.completedAt==null && (current.entries.isNotEmpty() || current.recipe!=null)) sessions=sessions.filterNot { it.id==current.id }+current.copy(paused=true)
        session=draft.copy(paused=true,version=draft.version+1,updatedAt=System.currentTimeMillis());syncHistory();persist()
    }
    fun exportJson(): String = store.export(LocalState(active=session,history=sessions))
    fun toggleVoice() { isTtsEnabled=!isTtsEnabled;prefs.edit().putBoolean("voice",isTtsEnabled).apply() }
    fun sendMessage(text: String) {
        if(isGenerating || text.isBlank())return
        messages.add(ChatMessage("User",text));isGenerating=true
        viewModelScope.launch {
            try {
                val model=GenerativeModel(BuildConfig.GEMINI_MODEL,BuildConfig.GEMINI_API_KEY)
                val response=model.generateContent("You are VESCURUS, a cooking assistant. Keep replies brief. Do not claim to observe an action, measure mass, or certify safety/doneness. Recipe: ${session.recipe?.name}. Current instruction: ${session.recipe?.steps?.getOrNull(session.step)?.instruction}. User asks: $text")
                messages.add(ChatMessage("AI suggestion",response.text ?: "No answer was returned."))
            } catch(e: CancellationException) { throw e
            } catch(_: Exception) { messages.add(ChatMessage("System","Online assistant unavailable. Your recipe and ingredients are saved on this phone.")) }
            finally { isGenerating=false }
        }
    }
    override fun onInit(status: Int) { if(status==TextToSpeech.SUCCESS) { voice?.language=Locale.US;voiceReady=true } }
    private fun speak(text: String) { if(isTtsEnabled && voiceReady)voice?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"vescurus-step") }
    override fun onCleared() { timerJob?.cancel();voice?.stop();voice?.shutdown();writes.close();super.onCleared() }
}
