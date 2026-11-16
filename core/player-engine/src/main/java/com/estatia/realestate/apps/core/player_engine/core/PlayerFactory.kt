package com.estatia.realestate.apps.core.player_engine.core

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.estatia.realestate.apps.core.player_engine.advanced.SpatialAudioRenderer
import com.estatia.realestate.apps.core.player_engine.analytics.PlaybackAnalyticsListener
import com.estatia.realestate.apps.core.model.property.MediaType
import com.estatia.realestate.apps.core.player_engine.configuration.IPlaybackConfigurationProvider
import com.estatia.realestate.apps.core.player_engine.configuration.PlayerConfiguration
import com.estatia.realestate.apps.core.player_engine.utils.EnvironmentCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import com.estatia.realestate.apps.core.architecture.annotations.Logic.Utility

// PlayerFactory.kt — request a fresh listener instance per player via Provider<T>
// Justification: Required for low-level Media3 playback orchestration.
@UnstableApi
@Singleton
@Utility
class PlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bandwidthMeter: BandwidthMeter,
    private val analyticsListenerProvider: Provider<PlaybackAnalyticsListener>,
    private val spatialAudioRendererProvider: Provider<SpatialAudioRenderer>,
    private val playbackConfigurationProvider: IPlaybackConfigurationProvider,
    private val environmentCoordinator: EnvironmentCoordinator
) {
@Utility
    data class CreatedPlayer(
        val player: ExoPlayer,
        val analyticsListener: PlaybackAnalyticsListener
    )

    fun create(configuration: PlayerConfiguration): CreatedPlayer {
        val renderersFactory = buildRenderersFactory()

        val builder = ExoPlayer.Builder(context, renderersFactory)
            .setLooper(Looper.getMainLooper())
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(configuration.mediaSourceFactory)
            .setLoadControl(configuration.loadControl)

        configuration.livePlaybackSpeedControl?.let { builder.setLivePlaybackSpeedControl(it) }

        val player = builder.build()
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        player.trackSelectionParameters = configuration.trackSelectionParameters
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaItem(configuration.mediaItem)

        val listener = analyticsListenerProvider.get()
        player.addAnalyticsListener(listener)

        return CreatedPlayer(player, listener)
    }

    /**
     * Creates an idle player that is later bound to media by the pool.
     *
     * Everything that ExoPlayer only accepts at build time must be decided here, so an idle
     * player is built with the same renderers (spatial audio), scaling mode and a baseline
     * load control as [create]. The per-media pieces (media source with the cache/CDN/header
     * pipeline, track selection) are applied at bind time via [bindMedia].
     */
    fun createIdle(): ExoPlayer {
        val loadControl = try {
            playbackConfigurationProvider.createLoadControl(
                MediaType.VOD,
                environmentCoordinator.environment.value
            )
        } catch (_: Exception) {
            DefaultLoadControl.Builder().build()
        }

        val player = ExoPlayer.Builder(context, buildRenderersFactory())
            .setLooper(Looper.getMainLooper())
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl)
            .build()

        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.playWhenReady = false

        return player
    }

    /**
     * Applies [configuration] to an idle [player]: the configured media source (so requests go
     * through the same cache, CDN failover and header handling as non-idle players), the track
     * selection parameters and, for live media, nothing further that is build-time only.
     */
    fun bindMedia(player: ExoPlayer, configuration: PlayerConfiguration) {
        player.trackSelectionParameters = configuration.trackSelectionParameters
        player.clearMediaItems()
        player.setMediaSource(
            configuration.mediaSourceFactory.createMediaSource(configuration.mediaItem)
        )
    }

    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val spatialAudioRenderer = spatialAudioRendererProvider.get()
        spatialAudioRenderer.logSpatialAudioStatus()

        return object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .build()
                    .apply { setListener(spatialAudioRenderer) }
            }
        }
    }
}
