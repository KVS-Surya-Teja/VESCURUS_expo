package com.example.vescurus.history

import com.example.vescurus.session.CookingSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Paired-phone archival protocol. Paths are local-only and never cross the socket. */
@Serializable data class HistoryRecord(val session: CookingSession, val photoDigest: String? = null, val photoBytes: Long = 0)
@Serializable data class HistoryChunk(val kind: String, val digest: String, val totalBytes: Long,
    val offset: Long, val dataBase64: String)
data class HistoryTransfer(val digest: String, val file: File, val kind: String)

/** Append-only content-addressed archive; absent records never mean deletion. */
@OptIn(ExperimentalSerializationApi::class, ExperimentalEncodingApi::class)
class HistoryReplicaStore(private val directory: File) {
    companion object { const val CHUNK_BYTES = 64 * 1024 }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val records = File(directory,"records").apply { mkdirs() }
    private val photos = File(directory,"photos").apply { mkdirs() }
    private val pending = File(directory,"pending").apply { mkdirs() }
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val known = linkedMapOf<String,HistoryRecord>()
    private val localInputs = mutableMapOf<String,CookingSession>()
    var recoveryMessage: String? = null
        private set

    init {
        records.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
            // Retain unreadable files for recovery; callers see a clear failure rather than an empty archive.
            runCatching {
                val record = file.inputStream().use { json.decodeFromStream<HistoryRecord>(it) }
                require(file.nameWithoutExtension == sha256(file))
                validate(record); known[file.nameWithoutExtension] = record
            }.onFailure { recoveryMessage = "Some saved history needs recovery. Original files have been kept." }
        }
    }
    private fun validate(record: HistoryRecord) {
        require(record.session.id.length in 1..128 && record.session.version >= 0 && record.session.schemaVersion == 1)
        require(record.session.finalPhotoPath == null && !record.session.demonstration)
        require(if(record.photoDigest == null) record.photoBytes == 0L else
            digestPattern.matches(record.photoDigest) && record.photoBytes > 0)
    }
    @Synchronized fun addLocal(sessions: List<CookingSession>): Boolean {
        var changed=false
        sessions.filterNot { it.demonstration }.forEach { session ->
            val previous=localInputs[session.id]
            if(previous==session)return@forEach
            if(previous!=null && session.copy(stepElapsedMs=previous.stepElapsedMs,activeCookingMs=previous.activeCookingMs,updatedAt=previous.updatedAt)==previous &&
                session.activeCookingMs-previous.activeCookingMs in 0 until 30_000)return@forEach
            val image = session.finalPhotoPath?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
            val photoDigest = image?.let(::sha256)
            if(image != null && photoDigest != null) copyAtomic(image,File(photos,photoDigest))
            val record = HistoryRecord(session.copy(finalPhotoPath=null),photoDigest,image?.length() ?: 0)
            validate(record)
            val temp = File.createTempFile("local-", ".pending", pending)
            try {
                FileOutputStream(temp).use { out -> json.encodeToStream(record,out);out.fd.sync() }
                val digest = sha256(temp)
                changed = changed || digest !in known
                val target = File(records,"$digest.json")
                if(!target.exists()) check(temp.renameTo(target)) { "Couldn't save cooking history" }
                known[digest] = record
                compact(session.id)
                localInputs[session.id]=session
            } finally { temp.delete() }
        }
        return changed
    }
    private fun compact(id: String) {
        val versions=known.filter { it.value.session.id==id }
        val latestVersions=versions.values.map { it.session.version }.distinct().sortedDescending().take(2).toSet()
        val discarded=versions.filter { (key,old) ->
            old.session.version !in latestVersions || versions.any { (otherKey,new) -> otherKey!=key &&
                new.session.version==old.session.version && new.photoDigest==old.photoDigest &&
                (new.session.activeCookingMs>old.session.activeCookingMs || new.session.updatedAt>old.session.updatedAt) &&
                new.session.copy(updatedAt=0,activeCookingMs=0,stepElapsedMs=0)==old.session.copy(updatedAt=0,activeCookingMs=0,stepElapsedMs=0) }
        }.keys
        discarded.forEach { key -> known.remove(key);File(records,"$key.json").delete() }
    }
    /** Deterministic latest revision per identity; older/conflicting revisions remain archived. */
    @Synchronized fun sessions(): List<CookingSession> = known.entries.groupBy { it.value.session.id }.values.map { versions ->
        val winner = versions.maxWith(compareBy<Map.Entry<String,HistoryRecord>> { it.value.session.version }
            .thenBy { it.value.session.updatedAt }.thenBy { it.key }).value
        val image = winner.photoDigest?.let { File(photos,it) }?.takeIf { it.isFile && it.length()==winner.photoBytes }
        winner.session.copy(finalPhotoPath=image?.absolutePath)
    }.sortedBy { it.completedAt ?: it.updatedAt }

    @Synchronized fun transfers(): List<Pair<String,List<HistoryTransfer>>> = known.entries.groupBy { it.value.session.id }.values.map { versions ->
        val winner=versions.maxWith(compareBy<Map.Entry<String,HistoryRecord>> { it.value.session.version }.thenBy { it.value.session.updatedAt }.thenBy { it.key })
        val digest=winner.key;val record=winner.value
        digest to buildList {
            add(HistoryTransfer(digest,File(records,"$digest.json"),"record"))
            record.photoDigest?.let { hash -> File(photos,hash).takeIf { it.isFile && it.length()==record.photoBytes }?.let {
                add(HistoryTransfer(hash,it,"photo"))
            } }
        }
    }
    fun chunks(transfer: HistoryTransfer): Sequence<HistoryChunk> = sequence {
        val total = transfer.file.length()
        transfer.file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES);var offset=0L
            while(true) {
                val count=input.read(buffer);if(count<0)break
                yield(HistoryChunk(transfer.kind,transfer.digest,total,offset,Base64.encode(buffer.copyOf(count))))
                offset+=count
            }
        }
    }
    /** Returns durable record digests ready to ACK, only after any associated photo is durable. */
    @Synchronized fun accept(chunk: HistoryChunk): List<String> {
        require(chunk.kind in setOf("record","photo") && digestPattern.matches(chunk.digest))
        require(chunk.totalBytes > 0 && chunk.offset >= 0 && chunk.offset < chunk.totalBytes)
        require(chunk.dataBase64.length <= ((CHUNK_BYTES+2)/3)*4)
        val bytes=Base64.decode(chunk.dataBase64)
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES && chunk.offset <= chunk.totalBytes-bytes.size)
        if(chunk.kind=="photo") require(known.values.any { it.photoDigest==chunk.digest && it.photoBytes==chunk.totalBytes })
        val target=if(chunk.kind=="record") File(records,"${chunk.digest}.json") else File(photos,chunk.digest)
        if(target.exists())require(target.length()==chunk.totalBytes)
        if(!target.exists()) {
            val temp=File(pending,"${chunk.kind}-${chunk.digest}.part")
            if(chunk.offset > temp.length() || temp.length()>chunk.totalBytes) {
                temp.delete();throw IllegalArgumentException("History transfer arrived out of order; reconnect to retry")
            }
            require(chunk.totalBytes-temp.length() <= directory.usableSpace) { "Not enough phone storage for shared history" }
            try { RandomAccessFile(temp,"rw").use { file ->
                file.seek(chunk.offset)
                if(chunk.offset < file.length()) {
                    require(chunk.offset+bytes.size <= file.length())
                    val previous=ByteArray(bytes.size);file.readFully(previous)
                    require(previous.contentEquals(bytes)) { "History transfer changed during retry" }
                } else file.write(bytes)
                if(file.length()==chunk.totalBytes)file.fd.sync()
            } } catch(e: IllegalArgumentException) { temp.delete();throw e }
            if(temp.length()==chunk.totalBytes) {
                if(sha256(temp)!=chunk.digest) { temp.delete();error("Shared history failed its integrity check") }
                if(chunk.kind=="record") {
                    val record=temp.inputStream().use { json.decodeFromStream<HistoryRecord>(it) }
                    validate(record)
                    check(temp.renameTo(target)) { "Couldn't save shared cooking history" }
                    known[chunk.digest]=record
                    compact(record.session.id)
                } else check(temp.renameTo(target)) { "Couldn't save the finished dish photo" }
            }
        }
        if(!target.exists() || chunk.offset+bytes.size != chunk.totalBytes)return emptyList()
        return known.filter { (digest,r) ->
            (if(chunk.kind=="record") digest==chunk.digest else r.photoDigest==chunk.digest) &&
            (r.photoDigest == null || File(photos,r.photoDigest).let { it.isFile && it.length()==r.photoBytes })
        }.keys.toList()
    }
    private fun copyAtomic(source: File, target: File) {
        if(target.exists())return
        val temp=File.createTempFile("photo-", ".pending", pending)
        try {
            FileOutputStream(temp).use { out -> source.inputStream().use { it.copyTo(out) };out.fd.sync() }
            check(sha256(temp)==target.name)
            check(temp.renameTo(target)) { "Couldn't save the finished dish photo" }
        } finally { temp.delete() }
    }
    private fun sha256(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(CHUNK_BYTES);while(true) {
            val read=input.read(buffer);if(read<0)break;digest.update(buffer,0,read)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
