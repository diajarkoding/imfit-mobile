package com.diajarkoding.imfit.di

import android.content.Context
import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.data.remote.online.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Read-only legacy source and durable, explicit transition checkpoints. No Room writer. */
@Module
@InstallIn(SingletonComponent::class)
object LegacyRecoveryModule {
    @Provides @Singleton fun reader(@ApplicationContext context: Context) =
        AndroidLegacyInventoryReader(context.getDatabasePath("imfit_database"))
    @Provides fun inventory(reader: AndroidLegacyInventoryReader): LegacyInventoryReader = reader
    @Provides fun aggregates(reader: AndroidLegacyInventoryReader): LegacyAggregateReader = reader

    @Provides @Singleton fun checkpoints(@ApplicationContext context: Context): LegacyCheckpointStore =
        FileLegacyCheckpointStore(File(context.noBackupFilesDir, "legacy_inventory"))

    @Provides @Singleton fun archive(@ApplicationContext context: Context): LegacyArchiveStore =
        FileLegacyArchiveStore(File(context.noBackupFilesDir, "legacy_archive"))

    @Provides @Singleton fun recovery(reads: RemoteReadGateway, inventory: LegacyInventoryReader, archive: LegacyArchiveStore) =
        LegacyRecoveryArchive(reads.auth, inventory, archive)

    @Provides @Singleton fun recoveryCheckpoints(@ApplicationContext context: Context): LegacyRecoveryCheckpointStore =
        FileLegacyRecoveryCheckpointStore(File(context.noBackupFilesDir, "legacy_recovery"))

    @Provides @Singleton fun coordinator(
        reads: RemoteReadGateway, repository: OnlineRepository, inventory: LegacyInventoryReader,
        aggregates: LegacyAggregateReader, checkpoints: LegacyRecoveryCheckpointStore, changes: OnlineMutationChanges,
    ) = LegacyRecoveryCoordinator(reads, repository, inventory, aggregates, checkpoints, changes)

    @Provides @Singleton fun preflight(
        reads: RemoteReadGateway, inventory: LegacyInventoryReader, checkpoints: LegacyCheckpointStore,
        recovery: LegacyRecoveryCoordinator, archive: LegacyArchiveStore,
    ) = LegacyStartPreflight(reads, inventory, checkpoints, recovery, archive = archive)
}
