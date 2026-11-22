package com.example.vescurus.data.local

import com.example.vescurus.session.CookingSession
import com.example.vescurus.model.Recipe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Serializable data class LocalState(val schemaVersion: Int = 1, val active: CookingSession = CookingSession(), val history: List<CookingSession> = emptyList())

/** Atomic replace with a previous valid copy. No account, cloud database or storage permission. */
class SessionStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    var recoveryMessage: String? = null; private set
    init { directory.mkdirs() }
    @Synchronized fun load(demo: Boolean = false): LocalState {
        val name = if (demo) "demonstration" else "cooking"
        for (suffix in listOf(".json", ".pending", ".backup.json")) {
            val file = File(directory,name+suffix)
            if (file.exists()) try {
                val state=json.decodeFromString<LocalState>(file.readText())
                require(state.schemaVersion == 1 && state.active.schemaVersion == 1)
                if (suffix != ".json") recoveryMessage="Recovered the previous saved session."
                return state
            } catch (_: Exception) { recoveryMessage="A saved file could not be read. Original files were retained." }
        }
        return LocalState(active=CookingSession(demonstration=demo))
    }
    @Synchronized fun save(state: LocalState, demo: Boolean = false) {
        val name=if(demo) "demonstration" else "cooking"
        atomicWrite(name,json.encodeToString(state))
    }
    @Synchronized fun saveRecipes(recipes: List<Recipe>) = atomicWrite("recipes",json.encodeToString(recipes))
    @Synchronized fun loadRecipes(): List<Recipe> = runCatching {
        json.decodeFromString<List<Recipe>>(File(directory,"recipes.json").readText())
    }.getOrElse { emptyList() }
    fun export(state: LocalState): String = json.encodeToString(state)
    private fun atomicWrite(name: String, text: String) {
        val current=File(directory,"$name.json"); val backup=File(directory,"$name.backup.json")
        val temporary=File(directory,"$name.pending")
        FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        if(current.exists()) {
            // Never replace a good recovery copy with malformed primary data.
            val valid=runCatching {
                if(name=="recipes") json.decodeFromString<List<Recipe>>(current.readText())
                else require(json.decodeFromString<LocalState>(current.readText()).schemaVersion==1)
            }.isSuccess
            if(valid) current.copyTo(backup,overwrite=true)
            else current.copyTo(File(directory,"$name.corrupt-${System.currentTimeMillis()}.json"),overwrite=false)
        }
        if (!temporary.renameTo(current)) {
            // Windows JVM regression tests require removing the destination before rename.
            // The fsynced pending file and previous valid backup remain recoverable.
            if (!current.exists() || !current.delete() || !temporary.renameTo(current)) throw java.io.IOException("Could not commit local session")
        }
    }
}
