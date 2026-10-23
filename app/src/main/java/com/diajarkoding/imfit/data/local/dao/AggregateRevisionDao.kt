package com.diajarkoding.imfit.data.local.dao

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AggregateRevisionDao {
    @Query("""SELECT revision FROM aggregate_revisions
        WHERE owner_id = :ownerId AND aggregate_type = :type AND aggregate_id = :id""")
    suspend fun revision(ownerId: String, type: String, id: String): Long?

    /** Used only by the existing owner-data purge, not an automatic retention policy. */
    @Query("DELETE FROM aggregate_revisions WHERE owner_id = :ownerId AND aggregate_type IN ('workout', 'template')")
    suspend fun deleteForOwner(ownerId: String)
}
