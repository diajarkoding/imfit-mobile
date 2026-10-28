package com.diajarkoding.imfit.data.legacy

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Inventory evidence only. No import mapping/receipt acknowledgement is implemented by this phase. */
@Serializable
data class LegacyCheckpoint(
    val ownerId: String, val epoch: Long, val evidence: String,
    val schemaVersion: Int?, val outcome: String, val reasons: List<String>,
    val itemCount: Int, val checkedAt: String, val formatVersion: Int = 1,
)

interface LegacyCheckpointStore {
    suspend fun load(ownerId: String): LegacyCheckpoint?
    suspend fun record(checkpoint: LegacyCheckpoint)
}

/** Must be placed under Context.noBackupFilesDir, never in/over the legacy database. */
class FileLegacyCheckpointStore(private val directory: File) : LegacyCheckpointStore {
    private val json = Json
    private val mutex = Mutex()
    private fun file(owner: String): AtomicFile {
        require(owner.isNotBlank())
        return AtomicFile(File(directory, "${fingerprint(owner)}.json"))
    }
    override suspend fun load(ownerId: String): LegacyCheckpoint? = mutex.withLock { withContext(Dispatchers.IO) {
        val path = file(ownerId)
        if (!path.baseFile.exists() && !File(path.baseFile.path + ".bak").exists()) return@withContext null
        require(path.baseFile.length() <= 65_536 && File(path.baseFile.path + ".bak").length() <= 65_536)
        json.decodeFromString<LegacyCheckpoint>(path.readFully().toString(Charsets.UTF_8)).also {
            require(it.ownerId == ownerId && it.formatVersion == 1)
        }
    } }
    override suspend fun record(checkpoint: LegacyCheckpoint) = mutex.withLock { withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val path = file(checkpoint.ownerId)
        val output = path.startWrite()
        try {
            output.write(json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8))
            output.fd.sync() // Surface fsync failure; AtomicFile.finishWrite otherwise only logs it.
            path.finishWrite(output)
        } catch (failure: Throwable) { path.failWrite(output); throw failure }
    } }
}
