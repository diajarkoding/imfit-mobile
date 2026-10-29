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
import com.diajarkoding.imfit.data.legacy.*

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteReadModule {
    @Binds @Singleton abstract fun reads(implementation: ProductionReadGateway): RemoteReadGateway
    companion object {
        @Provides @Singleton fun onlineRepository(reads: ProductionReadGateway): com.diajarkoding.imfit.data.remote.online.OnlineRepository = reads.repository
        @Provides @Singleton fun legacyGuard(
            reads: RemoteReadGateway,
            inventory: LegacyInventoryReader,
            archive: LegacyArchiveStore,
            recovery: LegacyRecoveryCoordinator,
        ) = LegacyTemplateGuard { owner, id ->
            val identity = reads.auth.capture().identity
            if (identity.ownerId != owner) throw com.diajarkoding.imfit.data.remote.online.StaleAuthException()
            val local = inventory.inspect(owner)
            if (local.ownerId != owner || local is LegacyInventory.Unknown) {
                throw OnlineFailure(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null))
            }
            val resolved = recovery.validatedCoverage(identity, local)
            val pending = recovery.pendingActions(identity)
            if (pending.any { it.state == LegacyRecoveryState.SUBMITTED }) {
                throw OnlineFailure(RemoteError.UnresolvedMutation)
            }
            val historical = (local as? LegacyInventory.Present)?.let { archive.load(owner)?.coveredItems(it) }.orEmpty()
            if (local is LegacyInventory.Present && local.items.any { item ->
                (item.kind == LegacyKind.TEMPLATE_INTENT && item !in resolved) ||
                    (id != null && (item.kind == LegacyKind.TEMPLATE && item.id == id ||
                        item.kind == LegacyKind.TEMPLATE_EXERCISE && item.parentId == id) &&
                        item.disposition != LegacyDisposition.SYNCED_CACHE_CANDIDATE &&
                        item !in resolved && item !in historical)
            }) {
                throw OnlineFailure(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null))
            }
            if (!reads.auth.isCurrent(identity)) throw com.diajarkoding.imfit.data.remote.online.StaleAuthException()
        }
    }
}
