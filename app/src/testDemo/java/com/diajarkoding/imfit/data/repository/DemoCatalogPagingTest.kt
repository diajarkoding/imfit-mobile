package com.diajarkoding.imfit.data.repository

import androidx.paging.PagingSource
import androidx.paging.AsyncPagingDataDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import com.diajarkoding.imfit.domain.model.ExerciseCatalogFilter
import org.junit.Assert.*
import org.junit.Test

class DemoCatalogPagingTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun repositoryPagerInitiallyLoadsTenThenAppendsTen() = runTest {
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<Exercise>() {
                override fun areItemsTheSame(oldItem: Exercise, newItem: Exercise) = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: Exercise, newItem: Exercise) = oldItem == newItem
            },
            updateCallback = object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) = Unit
                override fun onRemoved(position: Int, count: Int) = Unit
                override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
            },
            mainDispatcher = StandardTestDispatcher(testScheduler),
            workerDispatcher = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            DemoExerciseRepository().pageExercises(ExerciseCatalogFilter()).collectLatest(differ::submitData)
        }
        runCurrent()
        assertEquals("Initial query must load exactly one10-item page", 10, differ.itemCount)
        differ.getItem(9)
        runCurrent()
        assertEquals("Approaching the end appends another10 items", 20, differ.itemCount)
        assertEquals(20, differ.snapshot().items.map { it.id }.distinct().size)
    }

    @Test fun refreshAtAnAnchorCanPrependWithoutSkippingThePreviousBatch() = runBlocking {
        val records = (0..150).map { Exercise("id_$it", "Exercise $it", MuscleCategory.CHEST, "") }
        val source = DemoCatalogPagingSource(records)
        val refreshed = source.load(PagingSource.LoadParams.Refresh(20, 10, false)) as PagingSource.LoadResult.Page<Int, Exercise>
        val previous = source.load(PagingSource.LoadParams.Prepend(refreshed.prevKey!!, 10, false)) as PagingSource.LoadResult.Page<Int, Exercise>
        assertEquals(records.subList(10, 20), previous.data)
        assertEquals(records.subList(20, 30), refreshed.data)
    }

    @Test fun initialAndAppendLoadsTraverse151RecordsExactlyOnce() = runBlocking {
        val records = (0..150).map { Exercise("id_%03d".format(it), "Exercise $it", MuscleCategory.CHEST, "") }
        val source = DemoCatalogPagingSource(records)
        val initial = source.load(PagingSource.LoadParams.Refresh(null, 10, false)) as PagingSource.LoadResult.Page<Int, Exercise>
        assertEquals(records.take(10), initial.data)
        val loaded = initial.data.toMutableList()
        var next = initial.nextKey
        while (next != null) {
            val page = source.load(PagingSource.LoadParams.Append(next, 10, false)) as PagingSource.LoadResult.Page<Int, Exercise>
            assertEquals(minOf(10, records.size - loaded.size), page.data.size)
            loaded += page.data
            next = page.nextKey
        }
        assertEquals(records, loaded)
        assertEquals(151, loaded.map { it.id }.distinct().size)
    }
}
