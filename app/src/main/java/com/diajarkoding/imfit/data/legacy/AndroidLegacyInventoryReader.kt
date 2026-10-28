package com.diajarkoding.imfit.data.legacy

import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Opens only this existing file, never Room/open-helper migration or CREATE_IF_NECESSARY. */
class AndroidLegacyInventoryReader(private val file: File, private val maxRows: Int = 10_000) : LegacyInventoryReader {
    override suspend fun inspect(ownerId: String): LegacyInventory = scan(ownerId)
    override suspend fun record(ownerId: String, item: LegacyItem): Map<String, String?> {
        var fields: Map<String, String?>? = null
        val inventory = scan(ownerId) { rows ->
            fields = rows[item.kind]?.singleOrNull { row ->
                val id = when (item.kind) {
                    LegacyKind.TEMPLATE_EXERCISE -> "${row["template_id"]}:${row["exercise_id"]}"
                    LegacyKind.TEMPLATE_INTENT -> row["key"]
                    else -> row["id"]
                }
                id == item.id
            }
        }
        require(inventory is LegacyInventory.Present && inventory.items.any { it == item }) { "Legacy record changed or is unavailable" }
        return requireNotNull(fields)
    }
    private suspend fun scan(ownerId: String,
        payload: (Map<LegacyKind, List<Map<String, String?>>>) -> Unit = {}): LegacyInventory = withContext(Dispatchers.IO) {
        require(ownerId.isNotBlank())
        try {
            val stat = try { Os.lstat(file.absolutePath) } catch (failure: ErrnoException) {
                val parent = file.absoluteFile.parentFile
                if (failure.errno != OsConstants.ENOENT || parent == null || !parent.isDirectory || !parent.canRead()) throw failure
                if (listOf("-wal", "-shm", "-journal").any { File(file.path + it).exists() }) {
                    return@withContext LegacyInventory.Unknown(ownerId, LegacyReadFailure.INTEGRITY)
                }
                return@withContext LegacyInventory.Absent(ownerId)
            }
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size == 0L) {
                return@withContext LegacyInventory.Unknown(ownerId, LegacyReadFailure.CORRUPT)
            }
            val result = SQLiteDatabase.openDatabase(file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                // Android's default corruption handler may DELETE a database even on open failure.
                DatabaseErrorHandler { /* Preserve the exact legacy file for explicit recovery. */ }).use { database ->
                check(database.isReadOnly)
                // Public read-only transactions are only available on newer Android APIs. Do not
                // issue BEGIN IMMEDIATE/EXCLUSIVE against legacy storage. This private pool has no
                // WAL-enable flag (one connection); data_version fences reject any concurrent commit.
                check(!database.isWriteAheadLoggingEnabled)
                fun stamp(): List<Long> = listOf("data_version", "schema_version", "user_version").map { name ->
                    database.rawQuery("PRAGMA $name", null).use { check(it.moveToFirst()); it.getLong(0) }
                }
                val before = stamp()
                val reader = LegacySqlReader { sql, args, consume ->
                    database.rawQuery(sql, args.toTypedArray()).use { cursor ->
                        while (cursor.moveToNext()) {
                            consume(cursor.columnNames.associateWith { column ->
                                val index = cursor.getColumnIndexOrThrow(column)
                                if (cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) throw LegacyScanException(LegacyReadFailure.CORRUPT)
                                if (cursor.isNull(index)) null else cursor.getString(index)
                            })
                        }
                    }
                }
                val inspected = LegacySqlInventory(reader, maxRows, payload).inspect(ownerId)
                val after = Os.lstat(file.absolutePath)
                if (before != stamp() || stat.st_ino != after.st_ino || stat.st_dev != after.st_dev ||
                    stat.st_size != after.st_size || stat.st_mtime != after.st_mtime) {
                    throw LegacyScanException(LegacyReadFailure.CHANGED_DURING_READ)
                }
                inspected
            }
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LegacyScanException) { LegacyInventory.Unknown(ownerId, failure.failure) }
        catch (_: SQLiteDatabaseCorruptException) { LegacyInventory.Unknown(ownerId, LegacyReadFailure.CORRUPT) }
        catch (_: Exception) { LegacyInventory.Unknown(ownerId, LegacyReadFailure.UNAVAILABLE) }
    }
}
