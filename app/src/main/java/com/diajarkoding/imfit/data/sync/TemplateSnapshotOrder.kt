package com.diajarkoding.imfit.data.sync

import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity

/** Local pending/ownership always wins; server revision orders snapshots when available. */
internal fun acceptsTemplateSnapshot(local: WorkoutTemplateEntity?, remote: WorkoutTemplateEntity): Boolean {
    if (local == null) return true
    if (local.userId != remote.userId || local.syncStatus != "SYNCED" || local.pendingOperation != null) return false
    val localRevision = local.remoteRevision
    val remoteRevision = remote.remoteRevision
    if (localRevision != null) return remoteRevision != null && remoteRevision >= localRevision
    if (remoteRevision != null) return true
    return local.updatedAt <= remote.updatedAt
}
