package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal enum class StorePoint {
    BEFORE_LOCK, WAITING_FILE_LOCK, BEFORE_READ, BEFORE_PREPARE, BEFORE_STAGE_WRITE, AFTER_STAGE_SYNC, AFTER_REPLACE,
    AFTER_DIRECTORY_SYNC, BEFORE_READBACK, BEFORE_COMMIT, AFTER_COMMIT, BEFORE_PUBLICATION,
}

/**
 * Isolated synthetic-credential proof only. The directory must be private and already created.
 * Payload and receipt share one atomic record; no backup file or legacy key is ever recovered.
 * Integrity is not encryption, authenticity, server validity, or complete rollback detection.
 */
class FileAtomicSessionStore internal constructor(
    directory: File,
    private val files: AtomicStoreFiles,
    private val fault: (StorePoint) -> Unit = {},
) : AtomicSessionStore {
    constructor(directory: File) : this(directory, AndroidAtomicStoreFiles)

    private val directory = directory.canonicalFile
    private val record = File(this.directory, "session.v1")
    private val lockFile = File(this.directory, "session.lock")
    private val processLock = locks.getOrPut(lockFile.path) { Mutex() }

    private class Invalid(val reason: StoreFailure) : Exception()
    private class Envelope(
        val incarnation: String, val generation: Long, val operation: String,
        val predecessor: String, val committed: Boolean, val owner: String,
        val sessionJson: String, val receipt: String,
    ) {
        fun body(): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION)
                listOf(incarnation, operation, predecessor).forEach { out.text(it) }
                out.writeLong(generation); out.writeBoolean(committed)
                out.text(owner); out.text(sessionJson)
            }
        }.toByteArray()

        fun bytes(): ByteArray = body() + ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { it.text(receipt) }
        }.toByteArray()

        fun ticket() = StoreTicket(StoreHead(incarnation, generation, operation, digest(bytes())),
            predecessor, digest((owner + "\u0000" + sessionJson).toByteArray(Charsets.UTF_8)))

        fun session(): UserSession? = if (sessionJson.isEmpty()) null else json.decodeFromString(sessionJson)
        fun asCommitted(): Envelope {
            val unsigned = Envelope(incarnation, generation, operation, predecessor, true, owner, sessionJson, "")
            return Envelope(incarnation, generation, operation, predecessor, true, owner, sessionJson,
                digest(RECEIPT_DOMAIN + unsigned.body()))
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // File locks are process-wide on JVM/Android: serialize handles before acquiring the OS lock.
        fault(StorePoint.BEFORE_LOCK)
        processLock.withLock {
            currentCoroutineContext().ensureActive()
            if (!directory.isDirectory) throw Invalid(StoreFailure.UNSUPPORTED)
            RandomAccessFile(lockFile, "rw").use { handle ->
                var fileLock = handle.channel.tryLock()
                while (fileLock == null) {
                    fault(StorePoint.WAITING_FILE_LOCK)
                    delay(25) // cancellable cross-process contention; never wait over network
                    currentCoroutineContext().ensureActive()
                    fileLock = handle.channel.tryLock()
                }
                try { currentCoroutineContext().ensureActive(); block() }
                finally { fileLock.release() }
            }
        }
    }

    private suspend fun outcome(block: suspend () -> StoreOutcome): StoreOutcome = try {
        locked(block)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: LegacyRecord) { StoreOutcome.LegacyUnverified }
      catch (invalid: Invalid) { StoreOutcome.Unavailable(invalid.reason) }
      catch (_: UnsupportedOperationException) { StoreOutcome.Unavailable(StoreFailure.UNSUPPORTED) }
      catch (_: Exception) { StoreOutcome.Unavailable(StoreFailure.IO) }

    private fun read(): Envelope? {
        fault(StorePoint.BEFORE_READ)
        if (!record.exists()) return null
        if (record.length() > MAX_RECORD) throw Invalid(StoreFailure.OVERSIZE)
        val bytes = record.inputStream().use { it.readBytesBounded(MAX_RECORD) }
        if (bytes.size < 4 || DataInputStream(ByteArrayInputStream(bytes)).readInt() != MAGIC) {
            throw LegacyRecord()
        }
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.readInt()
                val version = input.readInt()
                if (version > VERSION) throw Invalid(StoreFailure.FUTURE_VERSION)
                if (version != VERSION) throw Invalid(StoreFailure.INVALID)
                val incarnation = input.text(36); val operation = input.text(36); val predecessor = input.text(64)
                val generation = input.readLong()
                val phase = input.readUnsignedByte()
                if (phase !in 0..1) throw Invalid(StoreFailure.INVALID)
                val owner = input.text(4096); val payload = input.text(MAX_SESSION); val receipt = input.text(64)
                if (input.available() != 0 || !validUuid(incarnation) || !validUuid(operation) || generation <= 0 ||
                    (predecessor.isNotEmpty() && !validDigest(predecessor))) throw Invalid(StoreFailure.INVALID)
                val envelope = Envelope(incarnation, generation, operation, predecessor, phase == 1, owner, payload, receipt)
                if (payload.isEmpty()) {
                    if (owner.isNotEmpty()) throw Invalid(StoreFailure.INVALID)
                } else {
                    val session = envelope.session()!!
                    if (owner.isBlank() || session.user?.id != owner || session.accessToken.isBlank() ||
                        session.refreshToken.isBlank() || json.encodeToString(session) != payload) throw Invalid(StoreFailure.INVALID)
                }
                if (envelope.committed) {
                    if (!validDigest(receipt) || receipt != envelope.asCommitted().receipt) throw Invalid(StoreFailure.INVALID)
                } else if (receipt.isNotEmpty()) throw Invalid(StoreFailure.INVALID)
                if (!bytes.contentEquals(envelope.bytes())) throw Invalid(StoreFailure.INVALID)
                return envelope
            }
        } catch (invalid: Invalid) { throw invalid }
          catch (_: Exception) { throw Invalid(StoreFailure.INVALID) }
    }

    private class LegacyRecord : Exception()

    /** Seal the exact observed bytes before treating a surviving commit as durable. */
    private fun seal() {
        RandomAccessFile(record, "rw").use { it.fd.sync() }
        files.syncDirectory(directory)
    }

    private suspend fun replace(envelope: Envelope) {
        val bytes = envelope.bytes()
        if (bytes.size > MAX_RECORD) throw Invalid(StoreFailure.OVERSIZE)
        val stage = File(directory, ".session-${UUID.randomUUID()}.stage")
        try {
            currentCoroutineContext().ensureActive()
            fault(StorePoint.BEFORE_STAGE_WRITE)
            FileOutputStream(stage).use { it.write(bytes); it.fd.sync() }
            fault(StorePoint.AFTER_STAGE_SYNC)
            currentCoroutineContext().ensureActive()
            files.replace(stage, record)
            fault(StorePoint.AFTER_REPLACE)
            files.syncDirectory(directory)
            fault(StorePoint.AFTER_DIRECTORY_SYNC)
            fault(StorePoint.BEFORE_READBACK)
            if (!record.inputStream().use { it.readBytesBounded(MAX_RECORD) }.contentEquals(bytes)) {
                throw Invalid(StoreFailure.INVALID)
            }
            currentCoroutineContext().ensureActive()
        } finally {
            // Only this operation's untrusted stage, never session/lock/legacy data.
            stage.delete()
        }
    }

    private fun inspected(envelope: Envelope?): StoreOutcome = when {
        envelope == null -> StoreOutcome.Absent
        !envelope.committed -> StoreOutcome.Prepared(envelope.ticket())
        else -> {
            seal()
            StoreOutcome.VerifiedCommitted(envelope.ticket(), envelope.session())
        }
    }

    override suspend fun readVerified(): StoreOutcome = outcome {
        try { inspected(read()) } catch (_: LegacyRecord) { StoreOutcome.LegacyUnverified }
    }

    override suspend fun prepare(expected: StoreHead?, operationId: String, session: UserSession?): StoreOutcome = outcome {
        try {
            fault(StorePoint.BEFORE_PREPARE)
            if (!validUuid(operationId)) return@outcome StoreOutcome.Unavailable(StoreFailure.INVALID)
            val old = read()
            if (old?.ticket()?.head != expected) return@outcome StoreOutcome.Conflict
            if (old?.generation == Long.MAX_VALUE) return@outcome StoreOutcome.Unavailable(StoreFailure.OVERFLOW)
            if (old?.operation == operationId) return@outcome StoreOutcome.Conflict
            val owner = session?.user?.id ?: ""
            val payload = session?.let { json.encodeToString(it) } ?: ""
            if (session != null && (owner.isBlank() || owner.toByteArray(Charsets.UTF_8).size > 4096 ||
                    session.accessToken.isBlank() || session.refreshToken.isBlank())) {
                return@outcome StoreOutcome.Unavailable(StoreFailure.INVALID)
            }
            if (payload.toByteArray(Charsets.UTF_8).size > MAX_SESSION) return@outcome StoreOutcome.Unavailable(StoreFailure.OVERSIZE)
            val prepared = Envelope(old?.incarnation ?: UUID.randomUUID().toString(), (old?.generation ?: 0) + 1,
                operationId, old?.ticket()?.head?.digest ?: "", false, owner, payload, "")
            replace(prepared)
            StoreOutcome.Prepared(prepared.ticket())
        } catch (_: LegacyRecord) { StoreOutcome.LegacyUnverified }
    }

    override suspend fun verifyPrepared(ticket: StoreTicket): StoreOutcome = outcome {
        val current = read()
        if (current == null || current.committed || current.ticket() != ticket) StoreOutcome.Conflict
        else { seal(); StoreOutcome.Prepared(current.ticket()) }
    }

    override suspend fun commitIfCurrent(ticket: StoreTicket): StoreOutcome = outcome {
        fault(StorePoint.BEFORE_COMMIT)
        val current = read()
        if (current == null || current.committed || current.ticket() != ticket) return@outcome StoreOutcome.Conflict
        val committed = current.asCommitted()
        replace(committed)
        fault(StorePoint.AFTER_COMMIT)
        inspected(committed)
    }

    override suspend fun resolve(ticket: StoreTicket): StoreOutcome = outcome {
        val current = read() ?: return@outcome StoreOutcome.Conflict
        // A committed head differs in phase/receipt; bind every other exact field, not just owner/ID.
        val candidate = current.ticket()
        val expectedPrepared = Envelope(current.incarnation, current.generation, current.operation,
            current.predecessor, false, current.owner, current.sessionJson, "").ticket()
        if (candidate != ticket && expectedPrepared != ticket) StoreOutcome.Conflict else inspected(current)
    }

    override suspend fun publishIfCurrent(ticket: StoreTicket, publication: () -> Unit): Boolean = try {
        locked {
            val current = read()
            if (current == null || !current.committed || current.ticket() != ticket) false
            else {
                seal(); fault(StorePoint.BEFORE_PUBLICATION)
                currentCoroutineContext().ensureActive()
                publication()
                true
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { false }

    override suspend fun loadSession(): UserSession? = (readCommitted() as? StoreOutcome.VerifiedCommitted)?.session

    override suspend fun saveSession(session: UserSession) = save(session)
    override suspend fun deleteSession() = save(null)

    private suspend fun save(session: UserSession?) {
        val initial = readVerified()
        val expected = when (initial) {
            StoreOutcome.Absent -> null
            is StoreOutcome.Prepared -> initial.ticket.head
            is StoreOutcome.VerifiedCommitted -> initial.ticket.head
            else -> throw AuthStorageUnavailableException()
        }
        val prepared = prepare(expected, UUID.randomUUID().toString(), session) as? StoreOutcome.Prepared
            ?: throw AuthStorageUnavailableException()
        if (verifyPrepared(prepared.ticket) !is StoreOutcome.Prepared ||
            commitIfCurrent(prepared.ticket) !is StoreOutcome.VerifiedCommitted) throw AuthStorageUnavailableException()
    }

    companion object {
        private const val MAGIC = 0x494d4153 // IMAS
        private const val VERSION = 1
        internal const val MAX_RECORD = 1024 * 1024
        internal const val MAX_SESSION = 512 * 1024
        private val RECEIPT_DOMAIN = "IMFIT-AUTH-COMMITTED-v1\u0000".toByteArray(Charsets.UTF_8)
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun validUuid(value: String) = try { UUID.fromString(value).toString() == value } catch (_: Exception) { false }
        private fun validDigest(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun DataOutputStream.text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size); write(bytes)
        }
        private fun DataInputStream.text(maximum: Int): String {
            val size = readInt()
            if (size < 0 || size > maximum || size > available()) throw Invalid(StoreFailure.INVALID)
            val bytes = ByteArray(size).also { readFully(it) }
            val decoded = bytes.toString(Charsets.UTF_8)
            if (!bytes.contentEquals(decoded.toByteArray(Charsets.UTF_8))) throw Invalid(StoreFailure.INVALID)
            return decoded
        }
        private fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = read(buffer)
                if (read < 0) return output.toByteArray()
                if (output.size() > maximum - read) throw Invalid(StoreFailure.OVERSIZE)
                output.write(buffer, 0, read)
            }
        }
    }
}
