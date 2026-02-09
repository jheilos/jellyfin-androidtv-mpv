package org.jellyfin.playback.mpv

import android.content.Context
import org.jellyfin.playback.core.plugin.playbackPlugin

/**
 * Creates a playback plugin that provides the mpv-based player backend.
 *
 * This plugin integrates libmpv into the Jellyfin playback system, providing
 * broad format support and advanced subtitle rendering capabilities.
 *
 * @param androidContext Android context for accessing app directories and resources
 * @param mpvPlayerOptions Configuration options for the mpv player (hardware decoding,
 *                         audio output, subtitle styling, etc.)
 * @return A [PlaybackPlugin] that provides [MpvPlayerBackend]
 */
fun mpvPlayerPlugin(
	androidContext: Context,
	mpvPlayerOptions: MpvPlayerOptions = MpvPlayerOptions(),
) = playbackPlugin {
	provide(MpvPlayerBackend(androidContext, mpvPlayerOptions))
}
