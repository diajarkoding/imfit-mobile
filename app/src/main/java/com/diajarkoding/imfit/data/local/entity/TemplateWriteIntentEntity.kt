package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable request identity; an ambiguous network outcome never becomes a fresh create. */
@Entity(tableName = "template_write_intents")
data class TemplateWriteIntentEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "template_id") val templateId: String,
    val payload: String,
)
