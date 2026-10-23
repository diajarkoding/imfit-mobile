package io.github.jan.supabase.auth

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.UUID

internal object ProbeCommand {
    const val SNAPSHOT = 1
    const val PREPARE_B = 2
    const val COMMIT = 3
    const val PREPARE_AND_DIE = 4
    const val COMMIT_AND_DIE = 5
    const val HOLD_LOCK = 6
    const val RELEASE_LOCK = 7
    const val DIE = 8
}

class StoreProbeServiceA : StoreProbeService()
class StoreProbeServiceB : StoreProbeService()

/** Separate diagnostic-UID processes. Self-kill never targets the runner, normal apps or device. */
open class StoreProbeService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private val incarnation = UUID.randomUUID().toString()
    private var expected: StoreHead? = null
    private var prepared: StoreTicket? = null
    private var heldFile: RandomAccessFile? = null
    private var heldLock: FileLock? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("isolated-auth-store-probe").also { it.start() }
        messenger = Messenger(Handler(thread.looper) { request ->
            val reply = Bundle().apply {
                putInt("pid", Process.myPid()); putString("incarnation", incarnation)
            }
            var die = false
            try {
                check(request.data.getInt("callerPid") != Process.myPid()) { "Probe must be a separate process" }
                val directory = File(requireNotNull(request.data.getString("directory"))).canonicalFile
                check(directory.parentFile == cacheDir.canonicalFile && directory.name.startsWith("imfit-auth-proof-"))
                val store = FileAtomicSessionStore(directory)
                // HandlerThread, NOT the main/UI thread. No network, only bounded local storage work.
                runBlocking {
                    when (request.what) {
                        ProbeCommand.SNAPSHOT -> {
                            val read = store.readVerified()
                            expected = when (read) {
                                is StoreOutcome.VerifiedCommitted -> read.ticket.head
                                is StoreOutcome.Prepared -> read.ticket.head
                                StoreOutcome.Absent -> null
                                else -> error("Unverified probe snapshot")
                            }
                            reply.putString("status", "READY")
                        }
                        ProbeCommand.PREPARE_B -> {
                            when (val result = store.prepare(expected, UUID.randomUUID().toString(), syntheticSession("B"))) {
                                is StoreOutcome.Prepared -> { prepared = result.ticket; reply.putString("status", "PREPARED") }
                                StoreOutcome.Conflict -> reply.putString("status", "CONFLICT")
                                else -> error("Unexpected probe prepare outcome")
                            }
                        }
                        ProbeCommand.COMMIT -> {
                            val result = store.commitIfCurrent(requireNotNull(prepared)) as StoreOutcome.VerifiedCommitted
                            reply.putString("status", "COMMITTED"); reply.putString("owner", result.session!!.user!!.id)
                        }
                        ProbeCommand.PREPARE_AND_DIE, ProbeCommand.COMMIT_AND_DIE -> {
                            val ticket = store.stage("B")
                            if (request.what == ProbeCommand.COMMIT_AND_DIE) {
                                check(store.commitIfCurrent(ticket) is StoreOutcome.VerifiedCommitted)
                                reply.putString("status", "COMMITTED")
                            } else reply.putString("status", "PREPARED")
                            die = true
                        }
                        ProbeCommand.HOLD_LOCK -> {
                            check(heldFile == null)
                            heldFile = RandomAccessFile(File(directory, "session.lock"), "rw")
                            heldLock = heldFile!!.channel.lock()
                            reply.putString("status", "LOCKED")
                        }
                        ProbeCommand.RELEASE_LOCK -> {
                            releaseLock(); reply.putString("status", "RELEASED")
                        }
                        ProbeCommand.DIE -> { reply.putString("status", "DYING"); die = true }
                        else -> error("Unknown probe command")
                    }
                }
            } catch (failure: Exception) {
                reply.putString("status", "ERROR")
                reply.putString("failureType", failure.javaClass.simpleName) // never tokens/session contents
                die = false
            }
            request.replyTo.send(Message.obtain().apply { arg1 = request.arg1; data = reply })
            if (die) Process.killProcess(Process.myPid())
            true
        })
    }

    private fun releaseLock() {
        try { heldLock?.release() } finally { heldFile?.close(); heldLock = null; heldFile = null }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        releaseLock(); thread.quitSafely(); super.onDestroy()
    }
}
