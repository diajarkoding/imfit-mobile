package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.remote.online.ProductionReadGateway
import com.diajarkoding.imfit.data.remote.online.RemoteReadGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import dagger.Provides
import com.diajarkoding.imfit.data.remote.online.LegacyTemplateGuard
import com.diajarkoding.imfit.data.remote.online.OnlineFailure
import com.diajarkoding.imfit.data.remote.online.RemoteError
import com.diajarkoding.imfit.data.local.database.IMFITDatabase

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteReadModule {
    @Binds @Singleton abstract fun reads(implementation: ProductionReadGateway): RemoteReadGateway
    companion object {
        @Provides @Singleton fun onlineRepository(reads: ProductionReadGateway): com.diajarkoding.imfit.data.remote.online.OnlineRepository = reads.repository
        @Provides @Singleton fun legacyGuard(database: IMFITDatabase) = LegacyTemplateGuard { owner, id ->
            // Read-only legacy inventory. Never acknowledge/delete/import old intents as a side effect.
            if (database.templateWriteIntentDao().count(owner) > 0) throw OnlineFailure(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null))
            if (id != null) {
                val parent = database.workoutTemplateDao().getTemplateByIdIncludingDeleted(id)
                if (parent != null && (parent.userId != owner || parent.syncStatus != "SYNCED" || parent.pendingOperation != null || parent.isDeleted)) {
                    throw OnlineFailure(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null))
                }
                val children = database.templateExerciseDao().getExercisesForTemplateList(id)
                if (children.any { it.syncStatus != "SYNCED" || it.pendingOperation != null }) throw OnlineFailure(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null))
            }
        }
    }
}
