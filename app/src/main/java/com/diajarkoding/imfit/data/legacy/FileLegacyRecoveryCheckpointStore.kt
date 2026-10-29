package com.diajarkoding.imfit.data.legacy

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** DI supplies noBackupFilesDir/legacy_recovery. Never opens or writes the legacy database. */
class FileLegacyRecoveryCheckpointStore(private val directory: File) : LegacyRecoveryCheckpointStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    private fun path(ownerId: String): AtomicFile {
        require(ownerId.isNotBlank())
        return AtomicFile(File(directory, "${fingerprint(ownerId)}.json"))
    }

    private fun loadLocked(ownerId: String): LegacyRecoveryCheckpoint? {
        val file = path(ownerId)
        val backup = File(file.baseFile.path + ".bak")
        if (!file.baseFile.exists() && !backup.exists()) return null
        require(file.baseFile.length() <= MAX_BYTES && backup.length() <= MAX_BYTES)
        val bytes = file.openRead().use { input ->
            val length = input.channel.size()
            require(length in 1..MAX_BYTES.toLong())
            val buffer = ByteArray(length.toInt())
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                require(read > 0)
                count += read
            }
            require(input.read() == -1)
            buffer
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return json.decodeFromString<LegacyRecoveryCheckpoint>(text).also { it.validate(ownerId) }
    }

    override suspend fun load(ownerId: String): LegacyRecoveryCheckpoint? = mutex.withLock {
        withContext(Dispatchers.IO) { guarded { loadLocked(ownerId) } }
    }

    override suspend fun save(checkpoint: LegacyRecoveryCheckpoint): Unit = mutex.withLock {
        withContext(Dispatchers.IO) { guarded {
            checkpoint.validate(checkpoint.ownerId)
            // A corrupt/foreign existing checkpoint is a blocker, never permission to overwrite it.
            loadLocked(checkpoint.ownerId)?.let { old ->
                old.actions.forEach { previous ->
                    val next = checkpoint.actions.single { it.actionId == previous.actionId }
                    require(previous.copy(state = next.state, validatedReceipt = next.validatedReceipt,
                        serverPayloadDigest = next.serverPayloadDigest, failure = next.failure) == next)
                    require(previous.serverPayloadDigest == null || previous.serverPayloadDigest == next.serverPayloadDigest)
                    require(previous.validatedReceipt == null || previous.validatedReceipt == next.validatedReceipt)
                    require(next.state in when (previous.state) {
                        LegacyRecoveryState.AWAITING_REVIEW -> setOf(previous.state, LegacyRecoveryState.PREPARED, LegacyRecoveryState.BLOCKED)
                        LegacyRecoveryState.PREPARED -> setOf(previous.state, LegacyRecoveryState.SUBMITTED, LegacyRecoveryState.BLOCKED)
                        LegacyRecoveryState.SUBMITTED -> setOf(previous.state, LegacyRecoveryState.CONFIRMED, LegacyRecoveryState.CONFLICT, LegacyRecoveryState.BLOCKED)
                        else -> setOf(previous.state)
                    })
                }
                require(checkpoint.localResolutions.containsAll(old.localResolutions))
            }
            val bytes = json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            check(directory.isDirectory || directory.mkdirs())
            val file = path(checkpoint.ownerId)
            val output = file.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
        } }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw LegacyRecoveryCheckpointException() }

    private companion object { const val MAX_BYTES = 4_194_304 }
}
