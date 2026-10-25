package com.diajarkoding.imfit.data.sync

import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import org.junit.Assert.*
import org.junit.Test

class WorkoutUploadSnapshotTest {
    private val parent = WorkoutLogEntity("log", "owner", null, "Routine", 1, 1, 2, 100f,
        pendingOperation = "CREATE", createdAt = 1, updatedAt = 1)
    private val set = WorkoutSetEntity("set", "exercise-log", "log", "exercise", 1, 10f, 10, true,
        pendingOperation = "CREATE", createdAt = 1, updatedAt = 1)
    private val snapshot = WorkoutUploadSnapshot(parent, emptyList(), listOf(set))

    @Test fun unchangedExactContentCanBeAcknowledged() { assertTrue(snapshot.matches(snapshot.copy())) }
    @Test fun sameMillisecondChildEditCannotClearNewerPending() {
        assertFalse(snapshot.matches(snapshot.copy(sets = listOf(set.copy(weight = 20f)))))
    }
    @Test fun differentOwnerOrOperationCannotBeAcknowledged() {
        assertFalse(snapshot.matches(snapshot.copy(parent = parent.copy(userId = "other"))))
        assertFalse(snapshot.matches(snapshot.copy(parent = parent.copy(pendingOperation = "UPDATE"))))
    }
    @Test fun removedChildCannotBeAcknowledged() { assertFalse(snapshot.matches(snapshot.copy(sets = emptyList()))) }
}
