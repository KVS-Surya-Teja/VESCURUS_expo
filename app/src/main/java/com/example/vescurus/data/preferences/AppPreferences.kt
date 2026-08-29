package com.example.vescurus.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.vescurus.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vescurus_prefs"
)

/**
 * Small key-value store for user preferences that need to survive process
 * death: TTS toggle, last selected role. Backed by Jetpack DataStore.
 *
 * Cook-in-flight state (recipe, progress) lives in `SavedStateHandle`, not
 * here — DataStore is for cross-session settings, not workflow state.
 */
class AppPreferences(context: Context) {

    private val dataStore = context.appPreferencesDataStore

    val ttsEnabled: Flow<Boolean> = dataStore.data
        .catchIo()
        .map { it[KEY_TTS_ENABLED] ?: true }

    val selectedRole: Flow<Role> = dataStore.data
        .catchIo()
        .map {
            when (it[KEY_SELECTED_ROLE]) {
                Role.GUIDE.name -> Role.GUIDE
                Role.COOK.name -> Role.COOK
                Role.DEMONSTRATE.name -> Role.DEMONSTRATE
                else -> Role.NONE
            }
        }

    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_TTS_ENABLED] = enabled }
    }

    suspend fun setSelectedRole(role: Role) {
        dataStore.edit { it[KEY_SELECTED_ROLE] = role.name }
    }

    suspend fun clearSelectedRole() {
        dataStore.edit { it.remove(KEY_SELECTED_ROLE) }
    }

    private fun Flow<Preferences>.catchIo(): Flow<Preferences> =
        catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    private companion object {
        val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val KEY_SELECTED_ROLE = stringPreferencesKey("selected_role")
    }
}
