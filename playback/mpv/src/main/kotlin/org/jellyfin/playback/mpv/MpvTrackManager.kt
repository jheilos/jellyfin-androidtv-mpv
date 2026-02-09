package org.jellyfin.playback.mpv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.playback.mpv.jni.MPVLib
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Track type enum matching mpv's track type strings.
 */
enum class MpvTrackType(val mpvValue: String) {
	VIDEO("video"),
	AUDIO("audio"),
	SUBTITLE("sub");

	companion object {
		fun fromMpvValue(value: String): MpvTrackType? = entries.find { it.mpvValue == value }
	}
}

/**
 * Base sealed interface for all track types.
 */
sealed interface MpvTrack {
	val id: Int
	val type: MpvTrackType
	val title: String?
	val language: String?
	val codec: String?
	val isDefault: Boolean
	val isForced: Boolean
	val isSelected: Boolean
}

/**
 * Video track information from mpv.
 */
data class MpvVideoTrack(
	override val id: Int,
	override val title: String?,
	override val language: String?,
	override val codec: String?,
	override val isDefault: Boolean,
	override val isForced: Boolean,
	override val isSelected: Boolean,
	val width: Int,
	val height: Int,
	val fps: Double,
) : MpvTrack {
	override val type: MpvTrackType = MpvTrackType.VIDEO

	val displayName: String
		get() = buildString {
			title?.let { append(it) }
			if (width > 0 && height > 0) {
				if (isNotEmpty()) append(" - ")
				append("${width}x${height}")
			}
			codec?.let {
				if (isNotEmpty()) append(" ")
				append("($it)")
			}
			if (isEmpty()) append("Video Track $id")
		}
}

/**
 * Audio track information from mpv.
 */
data class MpvAudioTrack(
	override val id: Int,
	override val title: String?,
	override val language: String?,
	override val codec: String?,
	override val isDefault: Boolean,
	override val isForced: Boolean,
	override val isSelected: Boolean,
	val channels: Int,
	val sampleRate: Int,
) : MpvTrack {
	override val type: MpvTrackType = MpvTrackType.AUDIO

	val displayName: String
		get() = buildString {
			title?.let { append(it) }
			language?.let {
				if (isNotEmpty()) append(" - ")
				append(it.uppercase())
			}
			if (channels > 0) {
				if (isNotEmpty()) append(" ")
				append("(${formatChannels(channels)})")
			}
			codec?.let {
				if (isNotEmpty()) append(" ")
				append("[$it]")
			}
			if (isEmpty()) append("Audio Track $id")
		}

	companion object {
		private fun formatChannels(channels: Int): String = when (channels) {
			1 -> "Mono"
			2 -> "Stereo"
			6 -> "5.1"
			8 -> "7.1"
			else -> "$channels ch"
		}
	}
}

/**
 * Subtitle track information from mpv.
 */
data class MpvSubtitleTrack(
	override val id: Int,
	override val title: String?,
	override val language: String?,
	override val codec: String?,
	override val isDefault: Boolean,
	override val isForced: Boolean,
	override val isSelected: Boolean,
	val isExternal: Boolean,
	val externalFilename: String?,
) : MpvTrack {
	override val type: MpvTrackType = MpvTrackType.SUBTITLE

	val displayName: String
		get() = buildString {
			title?.let { append(it) }
			language?.let {
				if (isNotEmpty()) append(" - ")
				append(it.uppercase())
			}
			if (isForced) {
				if (isNotEmpty()) append(" ")
				append("[Forced]")
			}
			if (isExternal) {
				if (isNotEmpty()) append(" ")
				append("[External]")
			}
			codec?.let {
				if (isNotEmpty()) append(" ")
				append("($it)")
			}
			if (isEmpty()) append("Subtitle Track $id")
		}
}

/**
 * Container for all tracks grouped by type.
 */
data class MpvTracks(
	val videoTracks: List<MpvVideoTrack> = emptyList(),
	val audioTracks: List<MpvAudioTrack> = emptyList(),
	val subtitleTracks: List<MpvSubtitleTrack> = emptyList(),
) {
	val isEmpty: Boolean
		get() = videoTracks.isEmpty() && audioTracks.isEmpty() && subtitleTracks.isEmpty()

	val selectedVideoTrack: MpvVideoTrack?
		get() = videoTracks.find { it.isSelected }

	val selectedAudioTrack: MpvAudioTrack?
		get() = audioTracks.find { it.isSelected }

	val selectedSubtitleTrack: MpvSubtitleTrack?
		get() = subtitleTracks.find { it.isSelected }

	companion object {
		val EMPTY = MpvTracks()
	}
}

/**
 * Manages track information and selection for mpv playback.
 *
 * This class provides:
 * - Real-time track information via StateFlow
 * - Methods to select video, audio, and subtitle tracks
 * - Support for dual subtitles (primary and secondary)
 *
 * Track information is refreshed from mpv's `track-list` property.
 */
class MpvTrackManager {
	private val _tracks = MutableStateFlow(MpvTracks.EMPTY)

	/**
	 * Current tracks available in the media.
	 * Updates when [refreshTracks] is called.
	 */
	val tracks: StateFlow<MpvTracks> = _tracks.asStateFlow()

	private val _selectedVideoTrackId = MutableStateFlow<Int?>(null)
	private val _selectedAudioTrackId = MutableStateFlow<Int?>(null)
	private val _selectedSubtitleTrackId = MutableStateFlow<Int?>(null)
	private val _selectedSecondarySubtitleTrackId = MutableStateFlow<Int?>(null)

	/**
	 * Currently selected video track ID.
	 */
	val selectedVideoTrackId: StateFlow<Int?> = _selectedVideoTrackId.asStateFlow()

	/**
	 * Currently selected audio track ID.
	 */
	val selectedAudioTrackId: StateFlow<Int?> = _selectedAudioTrackId.asStateFlow()

	/**
	 * Currently selected primary subtitle track ID.
	 * Null means subtitles are disabled.
	 */
	val selectedSubtitleTrackId: StateFlow<Int?> = _selectedSubtitleTrackId.asStateFlow()

	/**
	 * Currently selected secondary subtitle track ID.
	 * Null means secondary subtitles are disabled.
	 */
	val selectedSecondarySubtitleTrackId: StateFlow<Int?> = _selectedSecondarySubtitleTrackId.asStateFlow()

	/**
	 * Refresh track information from mpv.
	 * Call this after a file is loaded or when tracks may have changed.
	 */
	fun refreshTracks() {
		try {
			val trackListJson = MPVLib.getPropertyString("track-list")
			if (trackListJson.isNullOrEmpty()) {
				Timber.d("No track-list available")
				_tracks.value = MpvTracks.EMPTY
				return
			}

			val parsedTracks = parseTrackList(trackListJson)
			_tracks.value = parsedTracks

			// Update selected track IDs from current mpv state
			refreshSelectedTracks()

			Timber.d(
				"Tracks refreshed: %d video, %d audio, %d subtitle",
				parsedTracks.videoTracks.size,
				parsedTracks.audioTracks.size,
				parsedTracks.subtitleTracks.size
			)
		} catch (e: Exception) {
			Timber.e(e, "Error refreshing tracks")
			_tracks.value = MpvTracks.EMPTY
		}
	}

	/**
	 * Refresh selected track IDs from mpv properties.
	 */
	private fun refreshSelectedTracks() {
		try {
			val vid = MPVLib.getPropertyInt("vid")
			val aid = MPVLib.getPropertyInt("aid")
			val sid = MPVLib.getPropertyInt("sid")
			val secondarySid = MPVLib.getPropertyInt("secondary-sid")

			_selectedVideoTrackId.value = if (vid > 0) vid else null
			_selectedAudioTrackId.value = if (aid > 0) aid else null
			_selectedSubtitleTrackId.value = if (sid > 0) sid else null
			_selectedSecondarySubtitleTrackId.value = if (secondarySid > 0) secondarySid else null
		} catch (e: Exception) {
			Timber.w(e, "Error refreshing selected tracks")
		}
	}

	/**
	 * Parse mpv's track-list JSON into our track data classes.
	 */
	private fun parseTrackList(json: String): MpvTracks {
		val videoTracks = mutableListOf<MpvVideoTrack>()
		val audioTracks = mutableListOf<MpvAudioTrack>()
		val subtitleTracks = mutableListOf<MpvSubtitleTrack>()

		val jsonArray = JSONArray(json)
		for (i in 0 until jsonArray.length()) {
			val trackObj = jsonArray.getJSONObject(i)
			val type = trackObj.optString("type")

			when (MpvTrackType.fromMpvValue(type)) {
				MpvTrackType.VIDEO -> parseVideoTrack(trackObj)?.let { videoTracks.add(it) }
				MpvTrackType.AUDIO -> parseAudioTrack(trackObj)?.let { audioTracks.add(it) }
				MpvTrackType.SUBTITLE -> parseSubtitleTrack(trackObj)?.let { subtitleTracks.add(it) }
				null -> Timber.w("Unknown track type: %s", type)
			}
		}

		return MpvTracks(
			videoTracks = videoTracks,
			audioTracks = audioTracks,
			subtitleTracks = subtitleTracks,
		)
	}

	private fun parseVideoTrack(json: JSONObject): MpvVideoTrack? {
		val id = json.optInt("id", -1)
		if (id < 0) return null

		return MpvVideoTrack(
			id = id,
			title = json.optStringOrNull("title"),
			language = json.optStringOrNull("lang"),
			codec = json.optStringOrNull("codec"),
			isDefault = json.optBoolean("default", false),
			isForced = json.optBoolean("forced", false),
			isSelected = json.optBoolean("selected", false),
			width = json.optInt("demux-w", 0),
			height = json.optInt("demux-h", 0),
			fps = json.optDouble("demux-fps", 0.0),
		)
	}

	private fun parseAudioTrack(json: JSONObject): MpvAudioTrack? {
		val id = json.optInt("id", -1)
		if (id < 0) return null

		return MpvAudioTrack(
			id = id,
			title = json.optStringOrNull("title"),
			language = json.optStringOrNull("lang"),
			codec = json.optStringOrNull("codec"),
			isDefault = json.optBoolean("default", false),
			isForced = json.optBoolean("forced", false),
			isSelected = json.optBoolean("selected", false),
			channels = json.optInt("demux-channel-count", json.optInt("audio-channels", 0)),
			sampleRate = json.optInt("demux-samplerate", 0),
		)
	}

	private fun parseSubtitleTrack(json: JSONObject): MpvSubtitleTrack? {
		val id = json.optInt("id", -1)
		if (id < 0) return null

		return MpvSubtitleTrack(
			id = id,
			title = json.optStringOrNull("title"),
			language = json.optStringOrNull("lang"),
			codec = json.optStringOrNull("codec"),
			isDefault = json.optBoolean("default", false),
			isForced = json.optBoolean("forced", false),
			isSelected = json.optBoolean("selected", false),
			isExternal = json.optBoolean("external", false),
			externalFilename = json.optStringOrNull("external-filename"),
		)
	}

	// ========================================================================
	// Track Selection
	// ========================================================================

	/**
	 * Select a video track by ID.
	 *
	 * @param trackId The track ID to select, or null/0 to use auto selection
	 */
	fun selectVideoTrack(trackId: Int?) {
		try {
			val id = trackId ?: 0
			MPVLib.setPropertyInt("vid", if (id > 0) id else 1) // vid=0 means auto, vid=no means disabled
			_selectedVideoTrackId.value = if (id > 0) id else null
			Timber.d("Selected video track: %d", id)
		} catch (e: Exception) {
			Timber.e(e, "Error selecting video track %d", trackId)
		}
	}

	/**
	 * Select an audio track by ID.
	 *
	 * @param trackId The track ID to select, or null/0 to use auto selection
	 */
	fun selectAudioTrack(trackId: Int?) {
		try {
			val id = trackId ?: 0
			MPVLib.setPropertyInt("aid", if (id > 0) id else 1) // aid=0 means auto
			_selectedAudioTrackId.value = if (id > 0) id else null
			Timber.d("Selected audio track: %d", id)
		} catch (e: Exception) {
			Timber.e(e, "Error selecting audio track %d", trackId)
		}
	}

	/**
	 * Select a subtitle track by ID.
	 *
	 * @param trackId The track ID to select, or null/0 to disable subtitles
	 */
	fun selectSubtitleTrack(trackId: Int?) {
		try {
			val id = trackId ?: 0
			if (id > 0) {
				MPVLib.setPropertyInt("sid", id)
				MPVLib.setPropertyBoolean("sub-visibility", true)
			} else {
				// Disable subtitles
				MPVLib.setPropertyBoolean("sub-visibility", false)
			}
			_selectedSubtitleTrackId.value = if (id > 0) id else null
			Timber.d("Selected subtitle track: %d", id)
		} catch (e: Exception) {
			Timber.e(e, "Error selecting subtitle track %d", trackId)
		}
	}

	/**
	 * Select a secondary subtitle track by ID.
	 * This enables dual subtitle display in mpv.
	 *
	 * @param trackId The track ID to select, or null/0 to disable secondary subtitles
	 */
	fun selectSecondarySubtitleTrack(trackId: Int?) {
		try {
			val id = trackId ?: 0
			if (id > 0) {
				MPVLib.setPropertyInt("secondary-sid", id)
			} else {
				// Disable secondary subtitles by setting to "no"
				MPVLib.setPropertyString("secondary-sid", "no")
			}
			_selectedSecondarySubtitleTrackId.value = if (id > 0) id else null
			Timber.d("Selected secondary subtitle track: %d", id)
		} catch (e: Exception) {
			Timber.e(e, "Error selecting secondary subtitle track %d", trackId)
		}
	}

	/**
	 * Toggle subtitle visibility without changing the selected track.
	 */
	fun toggleSubtitleVisibility(): Boolean {
		return try {
			val currentVisibility = MPVLib.getPropertyBoolean("sub-visibility")
			val newVisibility = !currentVisibility
			MPVLib.setPropertyBoolean("sub-visibility", newVisibility)
			Timber.d("Subtitle visibility toggled to: %s", newVisibility)
			newVisibility
		} catch (e: Exception) {
			Timber.e(e, "Error toggling subtitle visibility")
			false
		}
	}

	/**
	 * Set subtitle visibility.
	 */
	fun setSubtitleVisibility(visible: Boolean) {
		try {
			MPVLib.setPropertyBoolean("sub-visibility", visible)
			Timber.d("Subtitle visibility set to: %s", visible)
		} catch (e: Exception) {
			Timber.e(e, "Error setting subtitle visibility")
		}
	}

	/**
	 * Get current subtitle visibility state.
	 */
	fun isSubtitleVisible(): Boolean {
		return try {
			MPVLib.getPropertyBoolean("sub-visibility")
		} catch (e: Exception) {
			Timber.w(e, "Error getting subtitle visibility")
			true
		}
	}

	/**
	 * Add an external subtitle file.
	 *
	 * @param path Path to the subtitle file
	 * @param title Optional title for the subtitle track
	 * @param language Optional language code
	 * @param select Whether to immediately select this subtitle
	 */
	fun addExternalSubtitle(
		path: String,
		title: String? = null,
		language: String? = null,
		select: Boolean = true,
	) {
		try {
			val cmd = mutableListOf("sub-add", path)
			if (select) {
				cmd.add("select")
			} else {
				cmd.add("auto")
			}
			title?.let { cmd.add(it) }
			language?.let { cmd.add(it) }

			MPVLib.command(cmd.toTypedArray())
			Timber.d("Added external subtitle: %s", path)

			// Refresh tracks to include the new subtitle
			refreshTracks()
		} catch (e: Exception) {
			Timber.e(e, "Error adding external subtitle: %s", path)
		}
	}

	/**
	 * Remove an external subtitle file.
	 *
	 * @param trackId The subtitle track ID to remove
	 */
	fun removeExternalSubtitle(trackId: Int) {
		try {
			MPVLib.command(arrayOf("sub-remove", trackId.toString()))
			Timber.d("Removed subtitle track: %d", trackId)

			// Refresh tracks
			refreshTracks()
		} catch (e: Exception) {
			Timber.e(e, "Error removing subtitle track: %d", trackId)
		}
	}

	/**
	 * Clear all tracks and reset state.
	 * Call this when stopping playback or loading a new file.
	 */
	fun clear() {
		_tracks.value = MpvTracks.EMPTY
		_selectedVideoTrackId.value = null
		_selectedAudioTrackId.value = null
		_selectedSubtitleTrackId.value = null
		_selectedSecondarySubtitleTrackId.value = null
	}

	/**
	 * Extension function to get optional string or null.
	 */
	private fun JSONObject.optStringOrNull(key: String): String? {
		val value = optString(key, "")
		return value.ifEmpty { null }
	}
}
