package com.example.vescurus.ui

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.vescurus.VescurusApplication
import com.example.vescurus.data.preferences.AppPreferences
import com.example.vescurus.data.preferences.CookHistoryRepository
import com.example.vescurus.data.remote.GeminiClient
import com.example.vescurus.model.CookHistoryItem
import com.example.vescurus.model.EGG_RECIPES
import com.example.vescurus.model.Recipe
import com.google.ai.client.generativeai.Chat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class ChatMessage(val sender: String, val message: String)

enum class CookingState { IDLE, RECIPE_SELECTION, COUNTDOWN, COOKING, DONE }

/**
 * ViewModel for the Cook role. Owns the cook state machine, the TTS voice,
 * a persistent Gemini chat session, and cook-history persistence.
 *
 * Cook state that must survive process death (recipe id, progress ms, state,
 * countdown) is mirrored into `SavedStateHandle`. The timer auto-resumes
 * from where it was on restore, including scripted chat beats already crossed.
 */
class CookViewModel(
    application: Application,
    geminiClient: GeminiClient,
    private val historyRepository: CookHistoryRepository,
    private val appPreferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    // Do NOT implement OnInitListener on the VM itself — that leaks `this`
    // to another thread before the ctor finishes. Use a lambda instead.
    private val tts: TextToSpeech
    @Volatile private var isTtsReady = false

    private val _messages: SnapshotStateList<ChatMessage> = mutableStateListOf()
    val messages: List<ChatMessage> = _messages

    private val chatGreeting = ChatMessage(
        sender = "AI",
        message = "I'm VESCURUS. Ready to help you cook. What's on the menu?"
    )

    private val chatSession: Chat = geminiClient.newChatSession()

    val isTtsEnabled: StateFlow<Boolean> = appPreferences.ttsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setTtsEnabled(enabled) }
    }

    var cookingState: CookingState by mutableStateOf(
        savedStateHandle.get<String>(KEY_STATE)?.let {
            runCatching { CookingState.valueOf(it) }.getOrNull()
        } ?: CookingState.IDLE
    )
        private set

    var selectedRecipe: Recipe? by mutableStateOf(
        savedStateHandle.get<String>(KEY_RECIPE_ID)
            ?.let { rid -> EGG_RECIPES.find { it.id == rid } }
    )
        private set

    var countdownValue: Int by mutableIntStateOf(savedStateHandle[KEY_COUNTDOWN] ?: 5)
        private set

    var currentProgressMs: Long by mutableLongStateOf(savedStateHandle[KEY_PROGRESS] ?: 0L)
        private set

    private var timerJob: Job? = null

    /**
     * History exposed to the UI = whatever the repo has, plus a scripted
     * "last night 9 pm" meal so the Track screen is never empty for the demo.
     * Sorted newest-first so the UI can render as-is.
     */
    val history: StateFlow<List<CookHistoryItem>> = historyRepository.history
        .map { real -> (real + fakeYesterdayMeal()).sortedByDescending { it.timestamp } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    init {
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.US
                tts.setSpeechRate(0.95f)
                isTtsReady = true
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }

        if (_messages.isEmpty()) _messages.add(chatGreeting)

        // Resume mid-flight cook if process death interrupted us.
        when (cookingState) {
            CookingState.COOKING -> selectedRecipe?.let { startTimerFrom(currentProgressMs) }
            CookingState.COUNTDOWN -> resumeCountdown()
            else -> Unit
        }
    }

    fun startRecipeSelection() {
        cookingState = CookingState.RECIPE_SELECTION
        savedStateHandle[KEY_STATE] = cookingState.name
    }

    fun selectRecipe(recipe: Recipe) {
        selectedRecipe = recipe
        savedStateHandle[KEY_RECIPE_ID] = recipe.id
        speak("Great choice. ${recipe.name}. Ready to start?")
    }

    fun startCooking() {
        selectedRecipe ?: return
        cookingState = CookingState.COUNTDOWN
        savedStateHandle[KEY_STATE] = cookingState.name
        countdownValue = 5
        savedStateHandle[KEY_COUNTDOWN] = countdownValue
        resumeCountdown()
    }

    private fun resumeCountdown() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (countdownValue > 0) {
                speak(countdownValue.toString())
                delay(1_000L)
                countdownValue -= 1
                savedStateHandle[KEY_COUNTDOWN] = countdownValue
            }
            startTimerFrom(0L)
        }
    }

    private fun startTimerFrom(startMs: Long) {
        val recipe = selectedRecipe ?: return
        cookingState = CookingState.COOKING
        savedStateHandle[KEY_STATE] = cookingState.name
        currentProgressMs = startMs
        savedStateHandle[KEY_PROGRESS] = currentProgressMs

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var lastAnnouncedStep = -1
            // On resume, catch up on chat beats already crossed so we don't
            // silently drop a beat that fired before process death.
            var lastEmittedBeatIndex = recipe.chatBeats
                .indexOfLast { currentProgressMs >= it.atMs }

            while (currentProgressMs < recipe.totalTimeMs) {
                val stepIndex = recipe.steps.indexOfFirst {
                    currentProgressMs >= it.startTimeMs && currentProgressMs < it.endTimeMs
                }
                if (stepIndex != lastAnnouncedStep && stepIndex != -1) {
                    val step = recipe.steps[stepIndex]
                    speak(step.ttsPrompt ?: step.instruction)
                    lastAnnouncedStep = stepIndex
                }

                val beatIndex = recipe.chatBeats.indexOfLast { currentProgressMs >= it.atMs }
                if (beatIndex > lastEmittedBeatIndex) {
                    for (i in (lastEmittedBeatIndex + 1)..beatIndex) {
                        _messages.add(ChatMessage(sender = "AI", message = recipe.chatBeats[i].text))
                    }
                    lastEmittedBeatIndex = beatIndex
                }

                delay(100L)
                currentProgressMs += 100L
                // Sample the progress into saved state every 500 ms — every
                // tick would thrash SavedStateHandle for no benefit.
                if (currentProgressMs % 500L == 0L) {
                    savedStateHandle[KEY_PROGRESS] = currentProgressMs
                }
            }
            cookingState = CookingState.DONE
            savedStateHandle[KEY_STATE] = cookingState.name
            speak("Cooking complete. How does it look?")
        }
    }

    fun markAsDone() {
        val recipe = selectedRecipe ?: return
        val item = CookHistoryItem(
            id = UUID.randomUUID().toString(),
            recipeId = recipe.id,
            recipeName = recipe.name,
            timestamp = System.currentTimeMillis(),
            calories = recipe.calories,
            proteinG = recipe.proteinG,
            carbsG = recipe.carbsG,
            fatsG = recipe.fatsG
        )
        viewModelScope.launch { historyRepository.append(item) }
        resetCookState()
        _messages.clear()
        _messages.add(chatGreeting)
    }

    private fun resetCookState() {
        cookingState = CookingState.IDLE
        selectedRecipe = null
        currentProgressMs = 0L
        countdownValue = 5
        savedStateHandle.remove<String>(KEY_STATE)
        savedStateHandle.remove<String>(KEY_RECIPE_ID)
        savedStateHandle.remove<Long>(KEY_PROGRESS)
        savedStateHandle.remove<Int>(KEY_COUNTDOWN)
    }

    fun sendMessage(userText: String) {
        val trimmed = userText.trim().take(MAX_CHAT_INPUT_CHARS)
        if (trimmed.isEmpty()) return
        _messages.add(ChatMessage(sender = "User", message = trimmed))

        viewModelScope.launch {
            try {
                val response = withTimeout(CHAT_TIMEOUT_MS) { chatSession.sendMessage(trimmed) }
                val reply = response.text?.trim().orEmpty().ifEmpty {
                    "I'm not sure about that. Let's focus on the cooking."
                }
                _messages.add(ChatMessage(sender = "AI", message = reply))
                speak(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat error", e)
                _messages.add(
                    ChatMessage(
                        sender = "System",
                        message = "I hit a snag reaching Gemini — try that again in a moment."
                    )
                )
            }
        }
    }

    private fun speak(text: String) {
        if (isTtsEnabled.value && isTtsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VESCURUS_VOICE")
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        runCatching {
            tts.stop()
            tts.shutdown()
        }
        super.onCleared()
    }

    private fun fakeYesterdayMeal(): CookHistoryItem {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return CookHistoryItem(
            id = "demo-yesterday-scrambled",
            recipeId = "scrambled",
            recipeName = "Soft Buttery Scrambled Eggs",
            timestamp = cal.timeInMillis,
            calories = 260,
            proteinG = 13f,
            carbsG = 1f,
            fatsG = 22f
        )
    }

    companion object {
        private const val TAG = "CookViewModel"
        private const val KEY_STATE = "cook.state"
        private const val KEY_RECIPE_ID = "cook.recipeId"
        private const val KEY_PROGRESS = "cook.progressMs"
        private const val KEY_COUNTDOWN = "cook.countdown"
        private const val CHAT_TIMEOUT_MS = 15_000L
        private const val MAX_CHAT_INPUT_CHARS = 1_000

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as VescurusApplication
                val savedStateHandle = createSavedStateHandle()
                CookViewModel(
                    application = app,
                    geminiClient = app.container.geminiClient,
                    historyRepository = app.container.cookHistoryRepository,
                    appPreferences = app.container.appPreferences,
                    savedStateHandle = savedStateHandle
                )
            }
        }
    }
}
