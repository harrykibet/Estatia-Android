package com.estatia.realestate.apps.di

import androidx.media3.common.util.UnstableApi
import com.estatia.realestate.apps.core.architecture.annotations.Identity.Contract
import com.estatia.realestate.apps.core.player_engine.core.PlayerManager
import com.estatia.realestate.apps.core.player_engine.core.PlayerPool
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only access to the production player singletons for instrumentation diagnostics.
 *
 * This belongs to the debug application source set so Hilt includes it in the real
 * SingletonComponent used by EstatiaApplication, without requiring HiltTestApplication.
 */
@Contract
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeedGestureChaosEntryPoint {
    @UnstableApi
    fun playerPool(): PlayerPool
    @UnstableApi
    fun playerManager(): PlayerManager
}
