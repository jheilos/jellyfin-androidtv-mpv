package org.jellyfin.playback.mpv

import org.jellyfin.playback.core.support.PlaySupportReport

/**
 * Play support report for mpv.
 *
 * mpv with FFmpeg supports virtually all media formats, so this implementation
 * is much simpler than ExoPlayer's format checking.
 *
 * @property canPlay Whether the stream can be played
 * @property reason Optional reason explaining the support status
 */
data class MpvPlaySupportReport(
	override val canPlay: Boolean,
	val reason: String? = null,
) : PlaySupportReport {
	companion object {
		/**
		 * Default supported report - mpv can play most formats.
		 */
		val SUPPORTED = MpvPlaySupportReport(canPlay = true)

		/**
		 * Unsupported report with a reason.
		 */
		fun unsupported(reason: String) = MpvPlaySupportReport(
			canPlay = false,
			reason = reason,
		)
	}
}
