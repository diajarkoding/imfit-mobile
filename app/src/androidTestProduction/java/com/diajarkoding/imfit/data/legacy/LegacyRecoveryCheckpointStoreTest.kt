package com.diajarkoding.imfit.data.legacy

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Android AtomicFile implementation, not android.jar host stubs. */
@RunWith(AndroidJUnit4::class)
class LegacyRecoveryCheckpointStoreTest {
    private val owner = "synthetic-owner-A"
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun path(directory: File) = File(directory, "${hash(owner)}.json")
    private fun fixture() = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
        "checkpoint-store-test-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    private fun checkpoint(source: String = "source") = LegacyRecoveryCheckpoint(owner, localResolutions = listOf(
        LegacyLocalArchiveResolution(owner, 9, "active-id", hash(source), hash("aggregate-$source"), hash("dependencies"))))
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Unsafe checkpoint accepted") } catch (_: LegacyRecoveryCheckpointException) { }
    }

    @Test fun atomicRoundTripAndOwnerIsolation() = runBlocking {
        val directory = fixture()
        try {
            val store = FileLegacyRecoveryCheckpointStore(directory)
            store.save(checkpoint())
            assertEquals(checkpoint(), FileLegacyRecoveryCheckpointStore(directory).load(owner))
            assertNull(store.load("synthetic-owner-B"))
            assertTrue(path(directory).isFile)
            assertFalse(File(directory, "$owner.json").exists())
            assertFalse(directory.listFiles().orEmpty().any { it.extension == "bak" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun malformedForeignUnsupportedAndOversizeAreNeverOverwritten() = runBlocking {
        val directory = fixture()
        try {
            val store = FileLegacyRecoveryCheckpointStore(directory)
            for (raw in listOf("{broken", json.encodeToString(checkpoint().copy(ownerId = "foreign")),
                json.encodeToString(checkpoint().copy(formatVersion = 2)), "x".repeat(4_194_305))) {
                path(directory).writeText(raw)
                val before = path(directory).readBytes()
                rejected { store.load(owner) }
                rejected { store.save(checkpoint()) }
                assertArrayEquals(before, path(directory).readBytes())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun atomicBackupIsRecoveredAndReviewHistoryCannotBeDropped() = runBlocking {
        val directory = fixture()
        try {
            val original = checkpoint()
            // Simulate the historical AtomicFile .bak crash boundary supported by Android.
            File(path(directory).path + ".bak").writeText(json.encodeToString(original))
            path(directory).writeText("incomplete-new-value")
            val store = FileLegacyRecoveryCheckpointStore(directory)
            assertEquals(original, store.load(owner))
            val next = original.copy(localResolutions = original.localResolutions + checkpoint("changed").localResolutions)
            store.save(next)
            rejected { store.save(original) }
            assertEquals(next, store.load(owner))
        } finally { directory.deleteRecursively() }
    }

    @Test fun rowCountOverflowIsRejectedWithoutReplacingLastGoodCheckpoint() = runBlocking {
        val directory = fixture()
        try {
            val store = FileLegacyRecoveryCheckpointStore(directory)
            val original = checkpoint()
            store.save(original)
            val overflowing = original.copy(localResolutions = (0..10_000).map { index ->
                original.localResolutions.single().copy(sourceId = "active-$index")
            })
            rejected { store.save(overflowing) }
            assertEquals(original, store.load(owner))
        } finally { directory.deleteRecursively() }
    }

    @Test fun unfinishedAtomicWriteLeavesLastCommittedCheckpointReadable() = runBlocking {
        val directory = fixture()
        try {
            val store = FileLegacyRecoveryCheckpointStore(directory)
            val original = checkpoint()
            store.save(original)
            val file = AtomicFile(path(directory))
            val output = file.startWrite()
            output.write("incomplete".toByteArray())
            output.fd.sync()
            file.failWrite(output)
            assertEquals(original, FileLegacyRecoveryCheckpointStore(directory).load(owner))
        } finally { directory.deleteRecursively() }
    }
}
