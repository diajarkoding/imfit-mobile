package com.diajarkoding.imfit.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.paging.PagingSource
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.dao.roomQuery
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Room paging in an in-memory fixture, no network or personal records. */
class ExerciseCatalogPagingTest {
    @Test fun roomTraversalAndLiteralFilteredQueryCoverTheWholeCatalog() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IMFITDatabase::class.java).build()
        try {
            val dao = db.exerciseDao()
            dao.insertExercises((0..150).map { ExerciseEntity("id_%03d".format(it), "Exercise $it", 1, "") })
            val source = dao.pagingSource(ExerciseCategory.CHEST.catalogFilter("").roomQuery())
            var page = source.load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page<Int, ExerciseEntity>
            assertEquals(10, page.data.size)
            val ids = page.data.map { it.id }.toMutableList()
            while (page.nextKey != null) {
                page = source.load(PagingSource.LoadParams.Append(page.nextKey!!, 10, false)) as PagingSource.LoadResult.Page<Int, ExerciseEntity>
                assertEquals(minOf(10, 151 - ids.size), page.data.size)
                ids += page.data.map { it.id }
            }
            assertEquals(151, ids.size)
            assertEquals(151, ids.distinct().size)
            assertEquals(ids.sorted(), ids)
            val filtered = dao.pagingSource(ExerciseCategory.CHEST.catalogFilter("Exercise 149").roomQuery())
                .load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page<Int, ExerciseEntity>
            assertEquals(listOf("id_149"), filtered.data.map { it.id })
            dao.insertExercises(listOf(ExerciseEntity("literal", "Bench_100%", 1, ""), ExerciseEntity("wild", "BenchX1000", 1, "")))
            val literal = dao.pagingSource(ExerciseCategory.CHEST.catalogFilter("_100%").roomQuery())
                .load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page<Int, ExerciseEntity>
            assertEquals(listOf("literal"), literal.data.map { it.id })
        } finally { db.close() }
    }
}
