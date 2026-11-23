package com.estatia.realestate.apps.core.player_engine.core

import com.estatia.realestate.apps.core.architecture.annotations.Logic.Helper
import kotlinx.coroutines.CancellationException

import java.io.IOException

/**
 * Thrown when a non-urgent player request is rejected because the pool is at capacity.
 */
@Helper
class PoolCapacityExceededException(message: String) : IOException(message)

/**
 * Thrown when the media was released while its player was still being created.
 *
 * Extends [CancellationException] on purpose: the caller asked for a player that it has since
 * let go of, so a coroutine awaiting it should unwind quietly like any cancelled work rather
 * than surface as a playback failure.
 */
@Helper
class PlayerReleasedDuringCreationException(message: String) : CancellationException(message)
