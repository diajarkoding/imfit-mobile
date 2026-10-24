package com.diajarkoding.imfit.data.local.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import com.diajarkoding.imfit.domain.model.ExerciseCatalogFilter

internal fun ExerciseCatalogFilter.roomQuery(): SimpleSQLiteQuery {
    val clauses = mutableListOf("is_active = 1")
    val args = mutableListOf<Any>()
    muscleCategory?.let { clauses += "muscle_category_id = ?"; args += it.ordinal + 1 }
    includedIds?.let { ids ->
        if (ids.isEmpty()) clauses += "0"
        else { clauses += "id IN (${ids.joinToString { "?" }})"; args.addAll(ids.sorted()) }
    }
    if (excludedIds.isNotEmpty()) {
        clauses += "id NOT IN (${excludedIds.joinToString { "?" }})"
        args.addAll(excludedIds.sorted())
    }
    if (query.isNotBlank()) {
        clauses += "name LIKE ? ESCAPE '\\'"
        val literal = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        args += "%$literal%"
    }
    return SimpleSQLiteQuery("SELECT * FROM exercises WHERE ${clauses.joinToString(" AND ")} ORDER BY id COLLATE BINARY ASC", args.toTypedArray())
}
