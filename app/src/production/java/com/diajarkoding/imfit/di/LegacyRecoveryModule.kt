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

/** Inventory/preflight only. Does not bind or activate any remote session writer. */
@Module
@InstallIn(SingletonComponent::class)
object LegacyRecoveryModule {
    @Provides @Singleton fun inventory(@ApplicationContext context: Context): LegacyInventoryReader =
        AndroidLegacyInventoryReader(context.getDatabasePath("imfit_database"))

    @Provides @Singleton fun checkpoints(@ApplicationContext context: Context): LegacyCheckpointStore =
        FileLegacyCheckpointStore(File(context.noBackupFilesDir, "legacy_inventory"))

    @Provides @Singleton fun archive(@ApplicationContext context: Context): LegacyArchiveStore =
        FileLegacyArchiveStore(File(context.noBackupFilesDir, "legacy_archive"))

    @Provides @Singleton fun recovery(reads: RemoteReadGateway, inventory: LegacyInventoryReader, archive: LegacyArchiveStore) =
        LegacyRecoveryArchive(reads.auth, inventory, archive)

    @Provides @Singleton fun preflight(reads: RemoteReadGateway, inventory: LegacyInventoryReader, checkpoints: LegacyCheckpointStore, archive: LegacyArchiveStore) =
        LegacyStartPreflight(reads, inventory, checkpoints, archive = archive)
}
