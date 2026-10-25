package com.diajarkoding.imfit.data.repository

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthMetadataTest {
    @Test fun readsStringInsteadOfJsonSerialization() {
        assertEquals("Cecep", buildJsonObject { put("name", "Cecep") }.displayNameOrDefault())
    }
    @Test fun preservesMeaningfulQuotesAndEscapedCharacters() {
        for (name in listOf("Cecep \"Cep\"", "O’Connor", "José 王", "A\\B")) {
            assertEquals(name, Json.parseToJsonElement(buildJsonObject { put("name", name) }.toString()).jsonObject.displayNameOrDefault())
        }
    }
    @Test fun absentNullBlankOrNonStringMetadataUsesFallback() {
        for (metadata in listOf(null, buildJsonObject {}, buildJsonObject { put("name", JsonNull) },
            buildJsonObject { put("name", "  ") }, buildJsonObject { put("name", 42) },
            buildJsonObject { put("name", true) }, buildJsonObject { put("name", buildJsonObject {}) })) {
            assertEquals("User", metadata.displayNameOrDefault())
        }
    }
}
