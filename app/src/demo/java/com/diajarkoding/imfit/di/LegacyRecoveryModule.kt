package com.diajarkoding.imfit.di

import android.content.Context
import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.data.remote.online.RemoteReadGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Demo has no production legacy storage. Synthetic recovery fixtures bind their own reader. */
@Module
@InstallIn(SingletonComponent::class)
object LegacyRecoveryModule {
    @Provides @Singleton fun inventory(): LegacyInventoryReader = LegacyInventoryReader { LegacyInventory.Absent(it) }
    @Provides @Singleton fun archive(@ApplicationContext context: Context): LegacyArchiveStore =
        FileLegacyArchiveStore(File(context.noBackupFilesDir, "demo_legacy_archive"))
    @Provides @Singleton fun checkpoints(@ApplicationContext context: Context): LegacyCheckpointStore =
        FileLegacyCheckpointStore(File(context.noBackupFilesDir, "demo_legacy_checkpoints"))

    @Provides @Singleton fun recovery(reads: RemoteReadGateway, inventory: LegacyInventoryReader, archive: LegacyArchiveStore) =
        LegacyRecoveryArchive(reads.auth, inventory, archive)

    @Provides @Singleton fun preflight(reads: RemoteReadGateway, inventory: LegacyInventoryReader, checkpoints: LegacyCheckpointStore, archive: LegacyArchiveStore) =
        LegacyStartPreflight(reads, inventory, checkpoints, archive = archive)
}
