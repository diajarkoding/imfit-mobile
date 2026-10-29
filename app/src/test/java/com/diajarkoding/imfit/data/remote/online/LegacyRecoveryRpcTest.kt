package com.diajarkoding.imfit.data.remote.online

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LegacyRecoveryRpcTest {
    private val request = "00000000-0000-0000-0000-000000000001"
    private val session = "00000000-0000-0000-0000-000000000002"
    private val fingerprint = "a".repeat(64)
    private val now = "2026-10-29T00:00:00Z"

    @Test fun archiveReceiptMustMatchOriginalSourceAndOperation() {
        val call = OnlineRpc.resolveLegacySession(request, session, fingerprint)
        val receipt = LegacySessionResolutionReceipt(request, "resolve_legacy_session", session, fingerprint,
            LegacyResolution.KEEP_ARCHIVED, now)
        call.validate(receipt)
        rejected { call.validate(receipt.copy(sourceFingerprint = "b".repeat(64))) }
        rejected { call.validate(receipt.copy(requestId = session)) }
        rejected { call.validate(receipt.copy(operation = "update_active_session")) }
        assertEquals("SESSION", call.mutation!!.resourceType)
        assertEquals(JsonPrimitive(fingerprint), call.parameters["p_expected_source_fingerprint"])
    }

    @Test fun hiddenReadinessBlockerCannotReportReady() {
        val call = OnlineRpc.startRecoveryStatus()
        call.validate(StartRecoveryStatus(1, now, true, emptyList(), false))
        rejected { call.validate(StartRecoveryStatus(1, now, true, emptyList(), true)) }
        rejected { call.validate(StartRecoveryStatus(2, now, true, emptyList(), false)) }
        val blocker = StartRecoveryBlocker(RecoveryBlockerKind.LEGACY_SESSION, session, null,
            fingerprint, RecoveryBlockerReason.LEGACY_REVIEW_REQUIRED)
        call.validate(StartRecoveryStatus(1, now, false, listOf(blocker), false))
        rejected { call.validate(StartRecoveryStatus(1, now, true, listOf(blocker), false)) }
    }

    @Test fun importReceiptMustPreserveEveryIdentityAndCount() {
        val source = buildJsonObject {
            put("schema_version", 9); put("kind", "WORKOUT"); put("id", session)
            put("fingerprint", fingerprint); put("mapping_version", 1)
        }
        val call = OnlineRpc.importLegacyWorkout(request, source,
            buildJsonObject {
                put("id", session); put("user_id", request); put("template_name", "Archive"); put("date", "2026-10-29")
                put("start_time", now); put("end_time", now); put("created_at", now); put("updated_at", now)
                put("total_volume", "0"); put("total_sets", 0); put("total_reps", 0)
            }, JsonArray(emptyList()), JsonArray(emptyList()))
        val receipt = LegacyWorkoutImportReceipt(request, "import_legacy_workout", session, fingerprint,
            "b".repeat(64), LegacyImportOutcome.IMPORTED, buildJsonObject { put(session, session) }, 0, 0, now)
        call.validate(receipt)
        assertEquals("WORKOUT", call.mutation!!.resourceType)
        rejected { call.validate(receipt.copy(payloadDigest = "not-a-digest")) }
        rejected { call.validate(receipt.copy(setCount = 1)) }
        rejected { call.validate(receipt.copy(idMapping = buildJsonObject { put(session, request) })) }
    }

    @Test fun operationLookupRejectsWrongRecoveryResource() {
        val result = buildJsonObject { put("request_id", request); put("workout_id", session) }
        val receipt = OperationReceipt(request, "import_legacy_workout", "WORKOUT", session, now, CommitTimeSource.COMMIT, result)
        val lookup = OnlineRpc.operationStatus(request)
        lookup.validate(OperationLookup(OperationState.COMMITTED, receipt))
        rejected { lookup.validate(OperationLookup(OperationState.COMMITTED, receipt.copy(resourceType = "SESSION"))) }
        rejected { lookup.validate(OperationLookup(OperationState.COMMITTED, receipt.copy(operation = "import_legacy_workout_unsafe"))) }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid server evidence accepted") } catch (_: IllegalArgumentException) { }
    }
}
