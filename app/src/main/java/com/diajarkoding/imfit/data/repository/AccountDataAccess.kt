package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Defense at the repository boundary: a caller-supplied owner/record ID is not authority. */
internal class AccountDataAccess(private val auth: AuthRepository) {
    suspend fun <T> read(expectedOwner: String? = null, empty: T, query: suspend (String) -> T): T {
        val owner = auth.getLocalUserId() ?: return empty
        if (expectedOwner != null && owner != expectedOwner) return empty
        val result = query(owner)
        return if (auth.getLocalUserId() == owner) result else empty
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> observe(expectedOwner: String? = null, empty: T, query: (String) -> Flow<T>): Flow<T> =
        auth.observeLocalUserId().flatMapLatest { owner ->
            if (owner == null || (expectedOwner != null && owner != expectedOwner)) flowOf(empty)
            else query(owner).map { value -> if (auth.getLocalUserId() == owner) value else empty }
        }.buffer(0)
}
