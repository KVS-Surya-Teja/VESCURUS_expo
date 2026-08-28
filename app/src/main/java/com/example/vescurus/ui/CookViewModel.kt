package com.example.vescurus.ui

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vescurus.BuildConfig
import com.example.vescurus.model.CookHistoryItem
import com.example.vescurus.model.Recipe
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*

data class ChatMessage(val sender: String, val message: String)

enum class CookingState {
    IDLE,
    RECIPE_SELECTION,
    COUNTDOWN,
    COOKING,
    DONE
}

class CookViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {
    private val TAG = "CookViewModel"
    private var tts: TextToSpeech? = TextToSpeech(application, this)
    private var isTtsReady = false
    
    var isTtsEnabled by mutableStateOf(true)

    private val chatModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    // Chat history state
    val messages = mutableStateListOf<ChatMessage>()
    var isGenerating by mutableStateOf(false)

    // Persistent multi-turn chat session
    private val chatSession = chatModel.startChat(
        history = listOf(
            content(role = "user") { text("You are VESCURUS, an edge AI culinary assistant. Keep all responses very brief, encouraging, and under 2 sentences. You are helping the user cook egg-based recipes.") },
            content(role = "model") { text("I'm VESCURUS! I'm here to help you cook the perfect eggs. What's on the menu?") }
        )
    )

    // Cooking Flow State
    var cookingState by mutableStateOf(CookingState.IDLE)
    var selectedRecipe by mutableStateOf<Recipe?>(null)
    var countdownValue by mutableIntStateOf(5)
    var currentProgressMs by mutableLongStateOf(0L)
    
    private var timerJob: Job? = null
    
    private val _history = MutableStateFlow<List<CookHistoryItem>>(emptyList())
    val history: StateFlow<List<CookHistoryItem>> = _history

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.95f)
            isTtsReady = true
        }
    }

    fun startRecipeSelection() {
        cookingState = CookingState.RECIPE_SELECTION
    }

    fun selectRecipe(recipe: Recipe) {
        selectedRecipe = recipe
        speak("Great choice! You selected ${recipe.name}. Ready to start?")
    }

    fun startCooking() {
        if (selectedRecipe == null) return
        cookingState = CookingState.COUNTDOWN
        countdownValue = 5
        
        viewModelScope.launch {
            while (countdownValue > 0) {
                speak(countdownValue.toString())
                delay(1000)
                countdownValue--
            }
            startTimer()
        }
    }

    private fun startTimer() {
        cookingState = CookingState.COOKING
        currentProgressMs = 0
        timerJob?.cancel()
        
        val recipe = selectedRecipe ?: return
        
        timerJob = viewModelScope.launch {
            var lastAnnouncedStepIndex = -1
            while (currentProgressMs < recipe.totalTimeMs) {
                // Find current step
                val currentStepIndex = recipe.steps.indexOfFirst { 
                    currentProgressMs >= it.startTimeMs && currentProgressMs < it.endTimeMs 
                }
                
                if (currentStepIndex != lastAnnouncedStepIndex && currentStepIndex != -1) {
                    val step = recipe.steps[currentStepIndex]
                    speak(step.ttsPrompt ?: step.instruction)
                    lastAnnouncedStepIndex = currentStepIndex
                }
                
                delay(100)
                currentProgressMs += 100
            }
            cookingState = CookingState.DONE
            speak("Cooking complete! How does it look?")
        }
    }

    fun markAsDone() {
        val recipe = selectedRecipe ?: return
        val newItem = CookHistoryItem(
            id = UUID.randomUUID().toString(),
            recipeId = recipe.id,
            recipeName = recipe.name,
            timestamp = System.currentTimeMillis(),
            calories = recipe.calories,
            proteinG = recipe.proteinG,
            carbsG = recipe.carbsG,
            fatsG = recipe.fatsG
        )
        _history.value = _history.value + newItem
        cookingState = CookingState.IDLE
        selectedRecipe = null
        // Reset chat for next session
        messages.clear()
    }

    fun sendMessage(userText: String) {
        messages.add(ChatMessage(sender = "User", message = userText))
        isGenerating = true

        viewModelScope.launch {
            try {
                val response = chatSession.sendMessage(userText)
                val reply = response.text ?: "I'm not sure about that. Let's focus on the cooking!"
                messages.add(ChatMessage(sender = "AI", message = reply))
                speak(reply)
            } catch (e: Exception) {
                Log.e(TAG, "Chat error: ${e.message}")
                messages.add(ChatMessage(sender = "System", message = "Connection issue. I'm still here!"))
            } finally {
                isGenerating = false
            }
        }
    }

    private fun speak(text: String) {
        if (isTtsEnabled && isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VESCURUS_VOICE")
        }
    }

    override fun onCleared() {
        timerJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
