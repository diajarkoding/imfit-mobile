package com.diajarkoding.imfit.data.legacy

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Synthetic standalone files under test cache only; never opens the app's imfit_database. */
class LegacyInventoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun directory() = File(context.cacheDir, "f6-inventory-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private fun fixture(version: Int, prepare: (SQLiteDatabase) -> Unit = {}): File {
        val file = File(directory(), "synthetic.db")
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.diajarkoding.imfit.data.local.database.IMFITDatabase/$version.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("database").jsonObject
        }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            schema.getValue("entities").jsonArray.forEach { entry ->
                val entity = entry.jsonObject
                database.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content))
            }
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getValue("identityHash").jsonPrimitive.content))
            database.version = version
            prepare(database)
        }
        return file
    }
    private fun insert(database: SQLiteDatabase, table: String, vararg given: Pair<String, Any?>) {
        val supplied = given.toMap()
        val fields = ContentValues()
        database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                val value = if (name in supplied) supplied[name] else when {
                    name == "sync_status" -> "SYNCED"
                    name == "pending_operation" || cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 0 -> null
                    type == "TEXT" -> "fixture-$name"
                    else -> 1L
                }
                when (value) {
                    null -> fields.putNull(name)
                    is String -> fields.put(name, value)
                    is Number -> fields.put(name, value.toLong())
                    else -> error("Unsupported synthetic value")
                }
            }
        }
        database.insertOrThrow(table, null, fields)
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    @Test fun missingFileIsAbsentWithoutCreatingDatabaseOrSidecars() = runBlocking {
        val parent = directory(); val file = File(parent, "never-created.db")
        assertTrue(AndroidLegacyInventoryReader(file).inspect("A") is LegacyInventory.Absent)
        assertTrue(parent.listFiles()!!.isEmpty())
    }

    @Test fun zeroCorruptUnknownVersionAndMissingParentAreNeverFresh() = runBlocking {
        val parent = directory()
        for ((name, bytes) in listOf("zero.db" to byteArrayOf(), "corrupt.db" to "not sqlite".toByteArray())) {
            val file = File(parent, name).apply { writeBytes(bytes) }
            val before = hash(file)
            assertTrue(AndroidLegacyInventoryReader(file).inspect("A") is LegacyInventory.Unknown)
            assertEquals(before, hash(file))
        }
        val unsupported = fixture(9) { it.version = 8 }
        assertEquals(LegacyReadFailure.UNSUPPORTED_SCHEMA, (AndroidLegacyInventoryReader(unsupported).inspect("A") as LegacyInventory.Unknown).failure)
        assertTrue(AndroidLegacyInventoryReader(File(parent, "missing-parent/legacy.db")).inspect("A") is LegacyInventory.Unknown)
    }

    @Test fun leftoverWalWithoutMainDatabaseBlocksInsteadOfPretendingFresh() = runBlocking {
        val file = File(directory(), "missing.db")
        File(file.path + "-wal").writeText("unresolved synthetic WAL")
        assertEquals(LegacyReadFailure.INTEGRITY, (AndroidLegacyInventoryReader(file).inspect("A") as LegacyInventory.Unknown).failure)
        assertFalse(file.exists())
    }

    @Test fun symbolicLinkIsBlockedWithoutOpeningItsTarget() = runBlocking {
        val target = fixture(9)
        val link = File(directory(), "legacy-link.db")
        android.system.Os.symlink(target.path, link.path)
        val before = hash(target)
        assertTrue(AndroidLegacyInventoryReader(link).inspect("A") is LegacyInventory.Unknown)
        assertEquals(before, hash(target))
    }

    @Test fun allExportedVersionsReadWithoutMigrationAndKeepExactSourceBytes() = runBlocking {
        for (version in listOf(6, 7, 9)) {
            val file = fixture(version) { database ->
                insert(database, "users", "id" to "A", "sync_status" to "SYNC_FAILED", "pending_operation" to "REVIEW")
                insert(database, "users", "id" to "B", "name" to "private B", "pending_operation" to "CREATE")
            }
            val before = hash(file)
            val result = AndroidLegacyInventoryReader(file).inspect("A")
            assertTrue("v$version must read, not return $result", result is LegacyInventory.Present)
            result as LegacyInventory.Present
            assertEquals(version, result.schemaVersion)
            assertEquals(listOf("A"), result.items.map { it.id })
            assertFalse(result.toString().contains("private B"))
            assertEquals(before, hash(file))
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(version, it.version) }
        }
    }

    @Test fun committedWalRowsAreVisibleWithoutCheckpointingOrMutatingLegacyBusinessData() = runBlocking {
        val file = fixture(9)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            insert(writer, "users", "id" to "A", "pending_operation" to "REVIEW")
            val beforeMain = hash(file)
            val wal = File(file.path + "-wal"); assertTrue(wal.exists()); val beforeWal = hash(wal)
            val result = AndroidLegacyInventoryReader(file).inspect("A")
            assertTrue("WAL-backed owner inventory must be visible: $result", result is LegacyInventory.Present)
            assertEquals(listOf("A"), (result as LegacyInventory.Present).items.map { it.id })
            assertEquals(beforeMain, hash(file)); assertEquals(beforeWal, hash(wal))
        }
    }

    @Test fun checkpointsSurviveNewStoreAndRemainOwnerScopedWithoutBusinessDraft() = runBlocking {
        val folder = directory()
        val first = FileLegacyCheckpointStore(folder)
        val a = LegacyCheckpoint("A", 1, fingerprint("A-state"), 9, "RECOVERY_REQUIRED", listOf("LEGACY_ITEMS_REVIEW"), 4, "2026-10-28T00:00:00Z")
        val b = a.copy(ownerId = "B", evidence = fingerprint("B-state"), itemCount = 2)
        first.record(a); first.record(b)
        val restarted = FileLegacyCheckpointStore(folder)
        assertEquals(a, restarted.load("A")); assertEquals(b, restarted.load("B"))
        assertNull(restarted.load("C"))
        assertEquals(2, folder.listFiles()!!.size)
        folder.listFiles()!!.forEach { file ->
            val payload = file.readText()
            assertFalse(payload.contains("session_data_json")); assertFalse(payload.contains("weight")); assertFalse(payload.contains("payload"))
        }
    }

    @Test fun atomicBackupRestoresPreviousCheckpointAndCorruptOrForeignPayloadFailsClosed() = runBlocking {
        val folder = directory(); val store = FileLegacyCheckpointStore(folder)
        val a = LegacyCheckpoint("A", 1, fingerprint("state"), 9, "READY", emptyList(), 0, "2026-10-28T00:00:00Z")
        store.record(a)
        val file = folder.listFiles()!!.single()
        assertTrue(file.renameTo(File(file.path + ".bak")))
        file.writeText("partial interrupted replacement")
        assertEquals(a, FileLegacyCheckpointStore(folder).load("A"))
        file.writeText("{broken")
        assertTrue(runCatching { store.load("A") }.isFailure)
        store.record(a.copy(ownerId = "B"))
        val b = folder.listFiles()!!.first { it.name != file.name }
        file.writeBytes(b.readBytes())
        assertTrue(runCatching { store.load("A") }.isFailure)
    }
}
