package com.diajarkoding.imfit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.diajarkoding.imfit.data.local.entity.TemplateWriteIntentEntity

@Dao
interface TemplateWriteIntentDao {
    @Query("SELECT * FROM template_write_intents WHERE `key` = :key AND user_id = :owner")
    suspend fun get(key: String, owner: String): TemplateWriteIntentEntity?
    @Insert suspend fun insert(intent: TemplateWriteIntentEntity)
    @Query("DELETE FROM template_write_intents WHERE `key` = :key AND user_id = :owner AND payload = :payload")
    suspend fun acknowledge(key: String, owner: String, payload: String): Int
    @Query("SELECT COUNT(*) FROM template_write_intents WHERE user_id = :owner")
    suspend fun count(owner: String): Int
}
