package io.github.jan.supabase.auth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real separate JVMs and OS locks; directory sync is SIMULATED, not Android durability proof. */
class AtomicStoreProcessTest {
    private fun command(directory: File): List<String> {
        val classpath = requireNotNull(System.getProperty("imfit.proof.runtimeClasspath"))
        val executable = File(requireNotNull(System.getProperty("java.home")), "bin/java" +
            if (System.getProperty("os.name", "")?.startsWith("Windows") == true) ".exe" else "")
        return listOf(executable.path, "-cp", classpath, StoreProcessProbe::class.java.name, directory.path)
    }

    @Test fun twoActualProcessesHaveOneExactCASWinnerAndFreshReopen() = runBlocking<Unit> {
        val directory = proofDirectory()
        val processes = mutableListOf<Process>()
        try {
            FileAtomicSessionStore(directory, ProtocolTestFiles).committed("A")
            repeat(2) { processes += ProcessBuilder(command(directory)).redirectError(ProcessBuilder.Redirect.INHERIT).start() }
            withTimeout(20_000) {
                val readers = processes.map { it.inputStream.bufferedReader() }
                readers.forEach { assertEquals("READY", withContext(Dispatchers.IO) { it.readLine() }) }
                processes.forEach { it.outputStream.bufferedWriter().also { writer -> writer.write("GO\n"); writer.flush() } }
                val replies = readers.map { withContext(Dispatchers.IO) { it.readLine() } }
                assertEquals(1, replies.count { it == "COMMITTED" })
                assertEquals(1, replies.count { it == "CONFLICT" })
                processes.forEach { assertTrue(it.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, it.exitValue()) }
                assertEquals("B", (FileAtomicSessionStore(directory, ProtocolTestFiles).readCommitted() as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
            }
        } finally {
            processes.forEach { it.destroyForcibly(); it.waitFor(10, TimeUnit.SECONDS) }
            directory.deleteRecursively()
        }
    }
}

object StoreProcessProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        val store = FileAtomicSessionStore(File(args.single()), ProtocolTestFiles)
        val before = store.readVerified() as StoreOutcome.VerifiedCommitted
        println("READY")
        check(System.`in`.bufferedReader().readLine() == "GO")
        val prepared = store.prepare(before.ticket.head, UUID.randomUUID().toString(), syntheticSession("B"))
        when (prepared) {
            is StoreOutcome.Prepared -> {
                check(store.commitIfCurrent(prepared.ticket) is StoreOutcome.VerifiedCommitted)
                println("COMMITTED")
            }
            StoreOutcome.Conflict -> println("CONFLICT")
            else -> error("Unexpected cross-process outcome: $prepared")
        }
    }
}
