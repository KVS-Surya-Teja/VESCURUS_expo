package com.example.vescurus.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.vescurus.model.CookHistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "vescurus_cook_history"
)

/**
 * Persists completed cooking sessions across process death. Storage is
 * JSON-serialized inside a single DataStore key — small footprint, no schema
 * migrations, no KSP. For 100+ entries or filtering by macros, migrate to
 * Room (see the deferred-work follow-up).
 */
class CookHistoryRepository(context: Context) {

    private val dataStore = context.historyDataStore
    private val serializer = ListSerializer(CookHistoryItem.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    val history: Flow<List<CookHistoryItem>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[KEY_HISTORY]?.let { raw ->
                runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    suspend fun append(item: CookHistoryItem) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_HISTORY]?.let {
                runCatching { json.decodeFromString(serializer, it) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[KEY_HISTORY] = json.encodeToString(serializer, current + item)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_HISTORY) }
    }

    private companion object {
        val KEY_HISTORY = stringPreferencesKey("cook_history_json")
    }
}
