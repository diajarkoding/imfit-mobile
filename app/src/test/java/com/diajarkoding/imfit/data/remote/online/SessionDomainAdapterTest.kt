package com.diajarkoding.imfit.data.remote.online

import org.junit.Assert.*
import org.junit.Test

class SessionDomainAdapterTest {
    private val snapshot = onlineJson.decodeFromString<SessionSnapshot>(OnlineFixtures.snapshot)
    @Test fun `duplicate exercise and set identity cannot produce ambiguous domain projection`() {
        assertTrue(runCatching { snapshot.copy(exercises = snapshot.exercises + snapshot.exercises).toDomain() }.isFailure)
        assertTrue(runCatching { snapshot.copy(exercises = snapshot.exercises.map { it.copy(sets = it.sets + it.sets) }).toDomain() }.isFailure)
    }
}
