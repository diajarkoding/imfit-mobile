package com.diajarkoding.imfit.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Decode the JSON value, not its quoted/escaped serialization. Never rewrite stored names. */
internal fun JsonObject?.displayNameOrDefault(): String =
    (this?.get("name") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?.takeUnless { it.isBlank() }
        ?: "User"
