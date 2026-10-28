package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LegacyRecoveryArchiveTest {
    private val reads = ScriptedReadGateway()
    private fun item(kind: LegacyKind = LegacyKind.WORKOUT, payload: String = "original", disposition: LegacyDisposition = LegacyDisposition.PENDING_CREATE) =
        LegacyItem(kind, "id-$kind", disposition, fingerprint(payload))
    private fun local(vararg items: LegacyItem, version: Int = 9) = LegacyInventory.Present("A", version,
        fingerprint(items.joinToString() + version), items.toList(), emptyList())
    private var source: LegacyInventory = local(item())
    private val reader = LegacyInventoryReader { source }
    private val saved = mutableMapOf<String, LegacyArchiveReview>()
    private val archive = object : LegacyArchiveStore {
        override suspend fun load(ownerId: String) = saved[ownerId]
        override suspend fun save(review: LegacyArchiveReview) { saved[review.ownerId] = review }
    }
    private val recovery = LegacyRecoveryArchive(reads.auth, reader, archive)
    private val checkpoints = object : LegacyCheckpointStore {
        override suspend fun load(ownerId: String): LegacyCheckpoint? = null
        override suspend fun record(checkpoint: LegacyCheckpoint) = Unit
    }
    private suspend fun preflight(): LegacyStartReport = coroutineScope {
        val job = async { LegacyStartPreflight(reads, reader, checkpoints, archive = archive).inspect() }
        reads.api.requests.receive().reply("""{"found":false,"session":null}""")
        reads.api.requests.receive().reply("""{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":100}}""")
        job.await()
    }

    @Test fun `historical acknowledgement permits new start but source remains pending and accessible`() = runTest {
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
        val original = source as LegacyInventory.Present
        val review = recovery.keepHistory(reads.auth.capture().identity, original)
        assertTrue(review.covers(original.items.single()))
        assertEquals(original, source)
        assertEquals(LegacyDisposition.PENDING_CREATE, (source as LegacyInventory.Present).items.single().disposition)
        assertEquals(LegacyStartDecision.READY, preflight().decision)
        assertEquals(original, recovery.review().first)
    }

    @Test fun `archiving history never resolves an active draft or uncertain intent`() = runTest {
        source = local(item(), item(LegacyKind.ACTIVE_SESSION), item(LegacyKind.TEMPLATE_INTENT))
        recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present)
        assertEquals(1, saved.getValue("A").entries.size)
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
    }

    @Test fun `restored changed payload cannot reuse an old review`() = runTest {
        recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present)
        source = local(item(payload = "changed restored original"))
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
    }

    @Test fun `changed inventory cannot confirm the previously displayed list`() = runTest {
        val displayed = source as LegacyInventory.Present
        source = local(item(payload = "new"))
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, displayed) }.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test fun `schema 6 and 7 read only bridge requires explicit matching history review`() = runTest {
        for (version in listOf(6, 7)) {
            saved.clear(); source = local(item(), version = version)
            assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
            recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present)
            assertEquals(LegacyStartDecision.READY, preflight().decision)
        }
    }

    @Test fun `unknown schema and read failure cannot be archived as empty`() = runTest {
        val displayed = source as LegacyInventory.Present
        source = LegacyInventory.Unknown("A", LegacyReadFailure.UNSUPPORTED_SCHEMA)
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, displayed) }.isFailure)
        assertEquals(LegacyStartDecision.UNKNOWN, preflight().decision)
        assertTrue(saved.isEmpty())
    }

    @Test fun `corrupt item cannot receive an archive acknowledgement`() = runTest {
        source = local(item(disposition = LegacyDisposition.PROBLEM))
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present) }.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test fun `account A B A cannot confirm under previous login epoch`() = runTest {
        val original = reads.auth.capture().identity
        reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new A")
        val failure = runCatching { recovery.keepHistory(original, source as LegacyInventory.Present) }.exceptionOrNull()
        assertTrue(failure is StaleAuthException); assertTrue(saved.isEmpty())
    }

    @Test fun `owner B cannot load or acknowledge A records`() = runTest {
        reads.auth.signIn("B", "B")
        assertTrue(runCatching { recovery.review() }.isFailure)
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present) }.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test fun `foreign archive cannot release preflight`() = runTest {
        saved["A"] = LegacyArchiveReview("B", 9, listOf(LegacyArchiveEntry(item().kind.name, item().id, item().fingerprint)))
        assertEquals(LegacyStartDecision.UNKNOWN, preflight().decision)
    }
}
