package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Local mutation identity only; never serialized to Supabase. No cascade from aggregate rows. */
@Entity(tableName = "aggregate_revisions", primaryKeys = ["owner_id", "aggregate_type", "aggregate_id"])
data class AggregateRevisionEntity(
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "aggregate_type") val aggregateType: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    val revision: Long,
)
