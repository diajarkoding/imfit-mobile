package io.github.jan.supabase.auth

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Internal seam for deterministic fault tests; never a user-supplied durability boolean. */
internal interface AtomicStoreFiles {
    fun replace(stage: File, destination: File)
    fun syncDirectory(directory: File)
}

/** Android API24+ POSIX implementation. Errors are not downgraded to weaker operations. */
internal object AndroidAtomicStoreFiles : AtomicStoreFiles {
    override fun replace(stage: File, destination: File) = Os.rename(stage.path, destination.path)

    override fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "Session store parent must be a directory" }
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
}
