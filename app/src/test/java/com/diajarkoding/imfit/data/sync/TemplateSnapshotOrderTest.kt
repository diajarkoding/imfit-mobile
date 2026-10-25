package com.diajarkoding.imfit.data.sync

import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import org.junit.Assert.*
import org.junit.Test

class TemplateSnapshotOrderTest {
    private val local = WorkoutTemplateEntity("routine", "owner", "Routine", syncStatus = "SYNCED",
        updatedAt = 100, remoteRevision = 10)
    @Test fun olderRevisionCannotOverwriteCacheEvenWithLaterWallTime() {
        assertFalse(acceptsTemplateSnapshot(local, local.copy(updatedAt = 200, remoteRevision = 9)))
    }
    @Test fun newerRevisionWinsEvenWhenWaitingTransactionHasEarlierStatementTime() {
        assertTrue(acceptsTemplateSnapshot(local, local.copy(updatedAt = 50, remoteRevision = 11)))
    }
    @Test fun foreignOrPendingLocalDataCannotBeReplacedByAnyRevision() {
        assertFalse(acceptsTemplateSnapshot(local.copy(pendingOperation = "UPDATE"), local.copy(remoteRevision = 20)))
        assertFalse(acceptsTemplateSnapshot(local.copy(userId = "other"), local.copy(remoteRevision = 20)))
    }
    @Test fun missingRemoteRevisionCannotDowngradeAlreadyRevisionedCache() {
        assertFalse(acceptsTemplateSnapshot(local, local.copy(updatedAt = 200, remoteRevision = null)))
    }
}
