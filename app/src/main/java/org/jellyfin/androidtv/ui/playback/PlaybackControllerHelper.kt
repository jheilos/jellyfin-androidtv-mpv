package org.jellyfin.androidtv.ui.playback

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.util.sdk.end
import org.jellyfin.androidtv.util.sdk.start
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

fun PlaybackController.getLiveTvChannel(
	id: UUID,
	callback: (channel: BaseItemDto) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getChannel(id).content
			}
		}.onSuccess { channel ->
			callback(channel)
		}
	}
}

/**
 * Disable subtitle display for mpv player.
 */
fun PlaybackController.disableDefaultSubtitles() {
	Timber.i("Disabling non-baked subtitles")
	mVideoManager.setExoPlayerTrack(-1, MediaStreamType.SUBTITLE, null)
}

/**
 * Set the subtitle track index for mpv player.
 *
 * @param index The Jellyfin stream index to select, or -1 to disable subtitles
 * @param force If true, apply the change even if the index hasn't changed
 */
@JvmOverloads
fun PlaybackController.setSubtitleIndex(index: Int, force: Boolean = false) {
	Timber.i("Switching subtitles from index ${mCurrentOptions.subtitleStreamIndex} to $index")

	// Already using this subtitle index
	if (mCurrentOptions.subtitleStreamIndex == index && !force) return

	// Disable subtitles
	if (index == -1) {
		mCurrentOptions.subtitleStreamIndex = -1

		if (burningSubs) {
			Timber.i("Disabling subtitle baking")

			stop()
			burningSubs = false
			play(mCurrentPosition, -1)
		} else {
			disableDefaultSubtitles()
		}
	} else if (burningSubs) {
		Timber.i("Restarting playback to disable subtitle baking")

		// If we're currently burning subs and want to switch streams we need some special behavior
		// to stop the current baked subs. We can just stop & start with the new subtitle index for that
		stop()
		burningSubs = false
		mCurrentOptions.subtitleStreamIndex = index
		play(mCurrentPosition, index)
	} else {
		val mediaSource = currentMediaSource
		val stream = mediaSource.mediaStreams?.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
		if (stream == null) {
			Timber.w("Failed to find correct media stream")
			return setSubtitleIndex(-1)
		}

		when (stream.deliveryMethod) {
			SubtitleDeliveryMethod.ENCODE -> {
				Timber.i("Restarting playback for subtitle baking")

				stop()
				burningSubs = true
				mCurrentOptions.subtitleStreamIndex = index
				play(mCurrentPosition, index)
			}

			SubtitleDeliveryMethod.EXTERNAL,
			SubtitleDeliveryMethod.EMBED,
			SubtitleDeliveryMethod.HLS -> {
				// For mpv, use the track manager to select subtitles
				// mpv handles both embedded and external subtitles through its unified track system
				Timber.i("Enabling subtitle track $index via method ${stream.deliveryMethod}")
				mCurrentOptions.subtitleStreamIndex = index

				// Use setExoPlayerTrack which maps Jellyfin indices to mpv track IDs
				val success = mVideoManager.setExoPlayerTrack(
					index,
					MediaStreamType.SUBTITLE,
					currentlyPlayingItem.mediaStreams
				)

				if (!success) {
					Timber.w("Failed to set subtitle track for index $index")
				}
			}

			SubtitleDeliveryMethod.DROP, null -> {
				Timber.i("Dropping subtitles")
				setSubtitleIndex(-1)
			}
		}
	}
}

/**
 * Data class to hold media segment action information for mpv position monitoring.
 */
private data class MediaSegmentActionData(
	val segment: MediaSegmentDto,
	val action: MediaSegmentAction,
	var triggered: Boolean = false,
)

/**
 * Storage for pending media segment actions.
 */
private val pendingMediaSegments = mutableListOf<MediaSegmentActionData>()
private var mediaSegmentHandler: Handler? = null
private var mediaSegmentRunnable: Runnable? = null

/**
 * Apply media segments for the current item.
 * For mpv, we use a position-monitoring approach since we don't have ExoPlayer's message system.
 */
fun PlaybackController.applyMediaSegments(
	item: BaseItemDto,
	callback: () -> Unit,
) {
	val mediaSegmentRepository by fragment.inject<MediaSegmentRepository>()

	fragment?.clearSkipOverlay()

	// Clear any existing segment monitoring
	clearMediaSegmentMonitoring()

	fragment.lifecycleScope.launch {
		val mediaSegments = runCatching {
			mediaSegmentRepository.getSegmentsForItem(item)
		}.getOrNull().orEmpty()

		// Collect segments with their actions
		pendingMediaSegments.clear()
		for (mediaSegment in mediaSegments) {
			val action = mediaSegmentRepository.getMediaSegmentAction(mediaSegment)
			if (action != MediaSegmentAction.NOTHING) {
				pendingMediaSegments.add(MediaSegmentActionData(mediaSegment, action))
			}
		}

		// Start position monitoring if we have segments to handle
		if (pendingMediaSegments.isNotEmpty()) {
			startMediaSegmentMonitoring()
		}

		callback()
	}
}

/**
 * Start monitoring playback position for media segment triggers.
 */
private fun PlaybackController.startMediaSegmentMonitoring() {
	if (mediaSegmentHandler == null) {
		mediaSegmentHandler = Handler(Looper.getMainLooper())
	}

	mediaSegmentRunnable = object : Runnable {
		override fun run() {
			if (!isPlaying) {
				// Continue monitoring even when paused
				mediaSegmentHandler?.postDelayed(this, 500)
				return
			}

			val currentPosition = currentPosition

			// Check each pending segment
			for (segmentData in pendingMediaSegments) {
				if (segmentData.triggered) continue

				val segmentStart = segmentData.segment.start.inWholeMilliseconds

				// Trigger if we're at or past the segment start
				// Use a small window to account for polling interval
				if (currentPosition >= segmentStart && currentPosition < segmentStart + 1000) {
					segmentData.triggered = true

					when (segmentData.action) {
						MediaSegmentAction.SKIP -> {
							Timber.i("Auto-skipping media segment at position $currentPosition")
							fragment.lifecycleScope.launch(Dispatchers.Main) {
								seek(segmentData.segment.end.inWholeMilliseconds, true)
							}
						}
						MediaSegmentAction.ASK_TO_SKIP -> {
							Timber.i("Showing skip prompt at position $currentPosition")
							fragment?.askToSkip(segmentData.segment.end)
						}
						MediaSegmentAction.NOTHING -> Unit
					}
				}
			}

			// Continue monitoring
			mediaSegmentHandler?.postDelayed(this, 250)
		}
	}

	mediaSegmentHandler?.post(mediaSegmentRunnable!!)
}

/**
 * Clear media segment monitoring.
 */
private fun clearMediaSegmentMonitoring() {
	mediaSegmentRunnable?.let { mediaSegmentHandler?.removeCallbacks(it) }
	mediaSegmentRunnable = null
	pendingMediaSegments.clear()
}
