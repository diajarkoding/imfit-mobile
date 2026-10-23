package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Real files/OS locks/atomic replacement, but DIRECTORY SYNC IS SIMULATED for protocol tests. */
internal object ProtocolTestFiles : AtomicStoreFiles {
    override fun replace(stage: File, destination: File) {
        if (onAndroid()) AndroidAtomicStoreFiles.replace(stage, destination)
        else Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    override fun syncDirectory(directory: File) = Unit // not a platform durability proof
}

internal object HostDurableTestFiles : AtomicStoreFiles {
    override fun replace(stage: File, destination: File) = ProtocolTestFiles.replace(stage, destination)
    override fun syncDirectory(directory: File) {
        if (onAndroid()) AndroidAtomicStoreFiles.syncDirectory(directory)
        else FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}

internal fun onAndroid() = System.getProperty("java.vm.name", "")?.contains("Dalvik", ignoreCase = true) == true

internal fun proofDirectory(): File {
    val base = if (onAndroid()) {
        val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        val instrumentation = registry.getMethod("getInstrumentation").invoke(null)
        val context = Class.forName("android.app.Instrumentation").getMethod("getTargetContext").invoke(instrumentation)
        Class.forName("android.content.Context").getMethod("getCacheDir").invoke(context) as File
    } else File(requireNotNull(System.getProperty("java.io.tmpdir")))
    return File(base, "imfit-auth-proof-${UUID.randomUUID()}").also { check(it.mkdir()) }
}

internal fun syntheticSession(owner: String) = UserSession("synthetic-$owner", "refresh-$owner",
    expiresIn = 86_400, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = owner))

internal suspend fun FileAtomicSessionStore.stage(owner: String? = "A"): StoreTicket {
    val expected = when (val read = readVerified()) {
        StoreOutcome.Absent -> null
        is StoreOutcome.Prepared -> read.ticket.head
        is StoreOutcome.VerifiedCommitted -> read.ticket.head
        else -> error("Unexpected proof outcome: $read")
    }
    return (prepare(expected, UUID.randomUUID().toString(), owner?.let(::syntheticSession)) as StoreOutcome.Prepared).ticket
}

internal suspend fun FileAtomicSessionStore.committed(owner: String? = "A"): StoreOutcome.VerifiedCommitted {
    val ticket = stage(owner)
    check(verifyPrepared(ticket) is StoreOutcome.Prepared)
    return commitIfCurrent(ticket) as StoreOutcome.VerifiedCommitted
}
