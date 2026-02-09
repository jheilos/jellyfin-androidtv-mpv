package org.jellyfin.androidtv.ui.playback

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.compat.StreamInfo
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.playback.mpv.MpvAudioTrack
import org.jellyfin.playback.mpv.MpvPlayerOptions
import org.jellyfin.playback.mpv.MpvSubtitleTrack
import org.jellyfin.playback.mpv.MpvTrackManager
import org.jellyfin.playback.mpv.jni.MPVLib
import org.jellyfin.playback.mpv.jni.MpvEventHandler
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.koin.java.KoinJavaComponent
import timber.log.Timber

/**
 * mpv-based video manager for the legacy Leanback playback UI.
 *
 * This is a drop-in replacement for [VideoManager] that uses libmpv instead of ExoPlayer.
 * It implements the same public interface used by [PlaybackController].
 *
 * @param activity The activity context
 * @param view The root view containing the video surface
 * @param helper The playback overlay fragment helper
 */
class MpvVideoManager(
	private val activity: Activity,
	view: View,
	private val helper: PlaybackOverlayFragmentHelper,
) : MpvEventHandler.EventListener, SurfaceHolder.Callback {

	// User preferences
	private val userPreferences: UserPreferences = KoinJavaComponent.get(UserPreferences::class.java)

	// mpv state
	private var isMpvInitialized = false
	private var isPaused = true
	private var isIdle = true
	private var currentVideoPath: String? = null

	// Track manager for audio/subtitle selection
	private val trackManager = MpvTrackManager()

	// Playback state
	private var metaDuration: Long = -1
	private var lastPosition: Long = -1
	private var zoomMode: ZoomMode = ZoomMode.FIT
	private var playbackControllerNotifiable: PlaybackControllerNotifiable? = null

	// Surface view for video rendering
	private val surfaceView: SurfaceView = view.findViewById(R.id.playerSurfaceView)
		?: throw IllegalStateException("Surface view not found in layout")
	private var isSurfaceAttached = false

	// Handler for progress loop
	private val handler = Handler(Looper.getMainLooper())
	private var progressLoop: Runnable? = null
	private var wasActiveForController = false

	// Video size tracking for contract/expand
	private var normalWidth: Int = 0
	private var normalHeight: Int = 0
	var isContracted: Boolean = false
		private set

	init {
		// Initialize mpv
		initializeMpv()

		// Set up surface
		surfaceView.holder.addCallback(this)

		// Apply user preferences for zoom mode
		setZoom(userPreferences.get(UserPreferences.playerZoomMode))
	}

	// ========================================================================
	// Initialization
	// ========================================================================

	private fun initializeMpv() {
		if (isMpvInitialized) {
			Timber.w("mpv already initialized")
			return
		}

		// Load native library
		if (!MPVLib.loadLibrary()) {
			Timber.e("Failed to load libmpv native library")
			return
		}

		// Attach event handler to native callback system
		MpvEventHandler.attach()

		try {
			// Create mpv instance
			MPVLib.create(activity)

			// Apply options
			val options = MpvPlayerOptions()
			options.applyOptions { name, value ->
				Timber.d("Setting mpv option: %s = %s", name, value)
				MPVLib.setOptionString(name, value)
			}

			// Set log level based on debug preference
			if (userPreferences.get(UserPreferences.debuggingEnabled)) {
				MPVLib.setLogLevel("v")
			} else {
				MPVLib.setLogLevel("warn")
			}

			// Initialize mpv
			MPVLib.initialize()

			// Register event listener
			MpvEventHandler.addEventListener(this)

			// Observe properties for state tracking
			observeProperties()

			isMpvInitialized = true
			Timber.i("mpv initialized successfully")
		} catch (e: Exception) {
			Timber.e(e, "Failed to initialize mpv")
		}
	}

	private fun observeProperties() {
		MPVLib.observeProperty("pause", MPVLib.PropertyFormat.FLAG)
		MPVLib.observeProperty("idle-active", MPVLib.PropertyFormat.FLAG)
		MPVLib.observeProperty("eof-reached", MPVLib.PropertyFormat.FLAG)
		MPVLib.observeProperty("duration", MPVLib.PropertyFormat.DOUBLE)
		MPVLib.observeProperty("speed", MPVLib.PropertyFormat.DOUBLE)
	}

	// ========================================================================
	// Public API - Matches VideoManager.java
	// ========================================================================

	/**
	 * Subscribe to playback notifications.
	 */
	fun subscribe(notifier: PlaybackControllerNotifiable) {
		playbackControllerNotifiable = notifier
	}

	/**
	 * Check if the player is initialized and ready.
	 */
	fun isInitialized(): Boolean = isMpvInitialized

	/**
	 * Get the current zoom mode.
	 */
	fun getZoomMode(): ZoomMode = zoomMode

	/**
	 * Set the zoom/aspect ratio mode.
	 */
	fun setZoom(mode: ZoomMode) {
		zoomMode = mode

		if (!isMpvInitialized) return

		try {
			// mpv uses video-aspect-override and panscan for aspect ratio control
			when (mode) {
				ZoomMode.FIT -> {
					// Fit within bounds, letterbox if needed
					MPVLib.setPropertyString("video-aspect-override", "no")
					MPVLib.setPropertyDouble("panscan", 0.0)
				}
				ZoomMode.AUTO_CROP -> {
					// Zoom to fill, crop edges if needed
					MPVLib.setPropertyString("video-aspect-override", "no")
					MPVLib.setPropertyDouble("panscan", 1.0)
				}
				ZoomMode.STRETCH -> {
					// Stretch to fill the entire surface
					val surfaceWidth = surfaceView.width
					val surfaceHeight = surfaceView.height
					if (surfaceWidth > 0 && surfaceHeight > 0) {
						val aspectRatio = surfaceWidth.toDouble() / surfaceHeight.toDouble()
						MPVLib.setPropertyString("video-aspect-override", aspectRatio.toString())
					}
					MPVLib.setPropertyDouble("panscan", 0.0)
				}
			}
			Timber.d("Zoom mode set to: %s", mode)
		} catch (e: Exception) {
			Timber.e(e, "Error setting zoom mode")
		}
	}

	/**
	 * Set the metadata duration (used when mpv reports incorrect duration).
	 */
	fun setMetaDuration(duration: Long) {
		metaDuration = duration
	}

	/**
	 * Get the current duration in milliseconds.
	 */
	fun getDuration(): Long {
		if (!isMpvInitialized) return metaDuration

		return try {
			val duration = MPVLib.getPropertyDouble("duration")
			if (duration > 0) {
				(duration * 1000).toLong()
			} else {
				metaDuration
			}
		} catch (e: Exception) {
			metaDuration
		}
	}

	/**
	 * Get the buffered position in milliseconds.
	 */
	fun getBufferedPosition(): Long {
		if (!isMpvInitialized) return -1

		return try {
			val position = MPVLib.getPropertyDouble("time-pos")
			val cacheTime = MPVLib.getPropertyDouble("demuxer-cache-time")
			val buffered = ((position + cacheTime) * 1000).toLong()
			val duration = getDuration()

			if (buffered in 0 until duration) {
				buffered
			} else {
				-1
			}
		} catch (e: Exception) {
			-1
		}
	}

	/**
	 * Get the current playback position in milliseconds.
	 */
	fun getCurrentPosition(): Long {
		if (!isMpvInitialized || !isPlaying()) {
			return if (lastPosition == -1L) 0 else lastPosition
		}

		return try {
			val position = (MPVLib.getPropertyDouble("time-pos") * 1000).toLong()
			lastPosition = position
			position
		} catch (e: Exception) {
			if (lastPosition == -1L) 0 else lastPosition
		}
	}

	/**
	 * Check if media is currently playing.
	 */
	fun isPlaying(): Boolean {
		if (!isMpvInitialized) return false

		return try {
			!MPVLib.getPropertyBoolean("pause") && !isIdle
		} catch (e: Exception) {
			false
		}
	}

	/**
	 * Start playback after media is loaded.
	 */
	fun start() {
		if (!isMpvInitialized) {
			Timber.e("mpv not initialized!")
			helper.fragment.closePlayer()
			return
		}

		try {
			MPVLib.setPropertyBoolean("pause", false)
			isPaused = false
			normalWidth = surfaceView.layoutParams.width
			normalHeight = surfaceView.layoutParams.height
		} catch (e: Exception) {
			Timber.e(e, "Error starting playback")
		}
	}

	/**
	 * Resume playback.
	 */
	fun play() {
		if (!isMpvInitialized) return

		try {
			MPVLib.setPropertyBoolean("pause", false)
			isPaused = false
		} catch (e: Exception) {
			Timber.e(e, "Error resuming playback")
		}
	}

	/**
	 * Pause playback.
	 */
	fun pause() {
		if (!isMpvInitialized) return

		try {
			MPVLib.setPropertyBoolean("pause", true)
			isPaused = true
		} catch (e: Exception) {
			Timber.e(e, "Error pausing playback")
		}
	}

	/**
	 * Stop playback completely.
	 */
	fun stopPlayback() {
		if (!isMpvInitialized) return

		try {
			MPVLib.command(arrayOf("stop"))
			isIdle = true
			currentVideoPath = null
			trackManager.clear()
		} catch (e: Exception) {
			Timber.e(e, "Error stopping playback")
		}

		stopProgressLoop()
	}

	/**
	 * Check if the current media is seekable.
	 */
	fun isSeekable(): Boolean {
		if (!isMpvInitialized) return false

		return try {
			val seekable = MPVLib.getPropertyBoolean("seekable")
			Timber.d("Current media item is%s seekable", if (seekable) "" else " not")
			seekable
		} catch (e: Exception) {
			// Assume seekable if we can't determine
			true
		}
	}

	/**
	 * Seek to a position in milliseconds.
	 */
	fun seekTo(pos: Long): Long {
		if (!isMpvInitialized) return -1

		Timber.i("mpv seeking to: %d ms (duration: %d ms)", pos, getDuration())

		try {
			val seconds = pos / 1000.0
			MPVLib.command(arrayOf("seek", seconds.toString(), "absolute"))
			return pos
		} catch (e: Exception) {
			Timber.e(e, "Error seeking")
			return -1
		}
	}

	/**
	 * Set the media stream info and load the video.
	 */
	fun setMediaStreamInfo(api: ApiClient, streamInfo: StreamInfo) {
		val path = streamInfo.mediaUrl
		if (path == null) {
			Timber.w("Video path is null, cannot continue")
			return
		}

		Timber.i("Video path set to: %s", path)
		currentVideoPath = path

		if (!isSurfaceAttached) {
			Timber.w("Surface not yet attached! mediacodec direct rendering may fail.")
		}

		try {
			val shouldAutoPlay = userPreferences.get(UserPreferences.videoAutoPlay)
			MPVLib.setPropertyBoolean("pause", !shouldAutoPlay)
			isPaused = !shouldAutoPlay

			// First load the main video file
			MPVLib.command(arrayOf("loadfile", path, "replace"))

			// Add external subtitles
			streamInfo.mediaSource?.mediaStreams?.forEach { mediaStream ->
				if (mediaStream.type != MediaStreamType.SUBTITLE) return@forEach
				if (mediaStream.deliveryMethod != SubtitleDeliveryMethod.EXTERNAL) return@forEach

				val subtitleUri = api.createUrl(
					mediaStream.deliveryUrl ?: return@forEach,
					emptyMap(),
					emptyMap(),
					true
				)

				Timber.i("Adding external subtitle track: %s", subtitleUri)

				// Use mpv's sub-add command to add external subtitles
				trackManager.addExternalSubtitle(
					path = subtitleUri,
					title = mediaStream.displayTitle,
					language = mediaStream.language,
					select = false
				)
			}

			isIdle = false
		} catch (e: Exception) {
			Timber.e(e, "Unable to set video path. Probably backing out.")
		}
	}

	/**
	 * Get the currently selected track of the given type.
	 *
	 * @param streamType The type of stream (AUDIO or SUBTITLE)
	 * @param allStreams All available streams from Jellyfin
	 * @return The stream index, or -1 if not found
	 */
	fun getExoPlayerTrack(
		streamType: MediaStreamType?,
		allStreams: List<MediaStream>?,
	): Int {
		if (!isMpvInitialized || streamType == null || allStreams == null) return -1
		if (streamType != MediaStreamType.SUBTITLE && streamType != MediaStreamType.AUDIO) return -1

		val tracks = trackManager.tracks.value

		return when (streamType) {
			MediaStreamType.AUDIO -> {
				val selectedId = trackManager.selectedAudioTrackId.value ?: return -1
				// Map mpv track ID to Jellyfin stream index
				mapMpvTrackToJellyfinIndex(selectedId, tracks.audioTracks, allStreams, streamType)
			}
			MediaStreamType.SUBTITLE -> {
				val selectedId = trackManager.selectedSubtitleTrackId.value ?: return -1
				mapMpvTrackToJellyfinIndex(selectedId, tracks.subtitleTracks, allStreams, streamType)
			}
		}
	}

	/**
	 * Set the track of the given type.
	 *
	 * @param index The Jellyfin stream index to select, or -1 to disable the track type
	 * @param streamType The type of stream (AUDIO or SUBTITLE)
	 * @param allStreams All available streams from Jellyfin (can be null when disabling)
	 * @return True if successful
	 */
	fun setExoPlayerTrack(
		index: Int,
		streamType: MediaStreamType?,
		allStreams: List<MediaStream>?,
	): Boolean {
		if (!isMpvInitialized) return false
		if (streamType != MediaStreamType.SUBTITLE && streamType != MediaStreamType.AUDIO) return false

		// Handle disabling track types
		if (index == -1) {
			return when (streamType) {
				MediaStreamType.SUBTITLE -> {
					trackManager.setSubtitleVisibility(false)
					Timber.i("Disabled subtitles")
					true
				}
				MediaStreamType.AUDIO -> {
					// Audio cannot be disabled, just return true
					Timber.i("Cannot disable audio track")
					true
				}
			}
		}

		// For selecting a specific track, we need the stream list
		if (allStreams.isNullOrEmpty()) return false

		// Find the Jellyfin stream
		val targetStream = allStreams.find {
			it.index == index && !it.isExternal && it.type == streamType
		} ?: return false

		val tracks = trackManager.tracks.value

		return when (streamType) {
			MediaStreamType.AUDIO -> {
				val mpvTrackId = mapJellyfinIndexToMpvTrack(index, tracks.audioTracks, allStreams, streamType)
				if (mpvTrackId > 0) {
					trackManager.selectAudioTrack(mpvTrackId)
					Timber.i("Selected audio track: mpv=%d, jellyfin=%d", mpvTrackId, index)
					true
				} else {
					Timber.w("Could not find mpv track for Jellyfin audio index %d", index)
					false
				}
			}
			MediaStreamType.SUBTITLE -> {
				// Re-enable subtitle visibility when selecting a track
				trackManager.setSubtitleVisibility(true)

				val mpvTrackId = mapJellyfinIndexToMpvTrack(index, tracks.subtitleTracks, allStreams, streamType)
				if (mpvTrackId > 0) {
					trackManager.selectSubtitleTrack(mpvTrackId)
					Timber.i("Selected subtitle track: mpv=%d, jellyfin=%d", mpvTrackId, index)
					true
				} else {
					Timber.w("Could not find mpv track for Jellyfin subtitle index %d", index)
					false
				}
			}
		}
	}

	/**
	 * Get the current playback speed.
	 */
	fun getPlaybackSpeed(): Float {
		if (!isMpvInitialized) return 1.0f

		return try {
			MPVLib.getPropertyDouble("speed").toFloat()
		} catch (e: Exception) {
			1.0f
		}
	}

	/**
	 * Set the playback speed.
	 */
	fun setPlaybackSpeed(speed: Float) {
		if (speed < 0.25f) {
			Timber.w("Invalid playback speed requested: %f", speed)
			return
		}

		Timber.d("Setting playback speed: %f", speed)

		if (!isMpvInitialized) return

		try {
			MPVLib.setPropertyDouble("speed", speed.toDouble())
		} catch (e: Exception) {
			Timber.e(e, "Error setting playback speed")
		}
	}

	/**
	 * Clean up and release resources.
	 */
	fun destroy() {
		playbackControllerNotifiable = null
		stopPlayback()
		releasePlayer()
	}

	private fun releasePlayer() {
		if (isMpvInitialized) {
			try {
				MpvEventHandler.removeEventListener(this)
				MpvEventHandler.detach()
				surfaceView.holder.removeCallback(this)
				MPVLib.destroy()
				isMpvInitialized = false
				Timber.i("mpv released")
			} catch (e: Exception) {
				Timber.e(e, "Error releasing mpv")
			}
		}
	}

	/**
	 * Contract the video view for picture-in-picture style display.
	 */
	fun contractVideo(height: Int) {
		if (isContracted) return

		val lp = surfaceView.layoutParams as FrameLayout.LayoutParams

		val sw = activity.window.decorView.width
		val sh = activity.window.decorView.height
		val ar = sw.toFloat() / sh.toFloat()

		lp.height = height
		lp.width = Math.ceil((height * ar).toDouble()).toInt()
		lp.rightMargin = ((lp.width - normalWidth) / 2) - 110
		lp.bottomMargin = ((lp.height - normalHeight) / 2) - 50

		surfaceView.layoutParams = lp
		surfaceView.invalidate()

		isContracted = true
	}

	/**
	 * Restore the video view to full size.
	 */
	fun setVideoFullSize(force: Boolean) {
		if (normalHeight == 0) return

		val lp = surfaceView.layoutParams as FrameLayout.LayoutParams

		if (force) {
			lp.height = FrameLayout.LayoutParams.MATCH_PARENT
			lp.width = FrameLayout.LayoutParams.MATCH_PARENT
		} else {
			lp.height = normalHeight
			lp.width = normalWidth
		}

		lp.rightMargin = 0
		lp.bottomMargin = 0

		surfaceView.layoutParams = lp
		surfaceView.invalidate()

		isContracted = false
	}

	// ========================================================================
	// Track Mapping Helpers
	// ========================================================================

	@Suppress("UNCHECKED_CAST")
	private fun <T> mapMpvTrackToJellyfinIndex(
		mpvTrackId: Int,
		mpvTracks: List<T>,
		jellyfinStreams: List<MediaStream>,
		streamType: MediaStreamType,
	): Int {
		// Find position of mpv track in list
		val mpvIndex = when (mpvTracks.firstOrNull()) {
			is MpvAudioTrack -> (mpvTracks as List<MpvAudioTrack>).indexOfFirst { it.id == mpvTrackId }
			is MpvSubtitleTrack -> (mpvTracks as List<MpvSubtitleTrack>).indexOfFirst { it.id == mpvTrackId }
			else -> -1
		}

		if (mpvIndex < 0) return -1

		// Get non-external Jellyfin streams of this type
		val jellyfinTracksOfType = jellyfinStreams.filter {
			it.type == streamType && !it.isExternal
		}

		// Return the Jellyfin stream index at the same position
		return jellyfinTracksOfType.getOrNull(mpvIndex)?.index ?: -1
	}

	private fun <T> mapJellyfinIndexToMpvTrack(
		jellyfinIndex: Int,
		mpvTracks: List<T>,
		jellyfinStreams: List<MediaStream>,
		streamType: MediaStreamType,
	): Int {
		// Get non-external Jellyfin streams of this type
		val jellyfinTracksOfType = jellyfinStreams.filter {
			it.type == streamType && !it.isExternal
		}

		// Find position of this index in Jellyfin list
		val position = jellyfinTracksOfType.indexOfFirst { it.index == jellyfinIndex }
		if (position < 0) return -1

		// Get mpv track at same position
		return when (val track = mpvTracks.getOrNull(position)) {
			is MpvAudioTrack -> track.id
			is MpvSubtitleTrack -> track.id
			else -> -1
		}
	}

	// ========================================================================
	// Progress Loop
	// ========================================================================

	private fun startProgressLoop() {
		stopProgressLoop()

		progressLoop = object : Runnable {
			override fun run() {
				playbackControllerNotifiable?.onProgress()
				handler.postDelayed(this, 500)
			}
		}
		handler.post(progressLoop!!)
	}

	private fun stopProgressLoop() {
		progressLoop?.let { handler.removeCallbacks(it) }
		progressLoop = null
	}

	// ========================================================================
	// SurfaceHolder.Callback Implementation
	// ========================================================================

	override fun surfaceCreated(holder: SurfaceHolder) {
		Timber.d("Surface created")
		if (isMpvInitialized) {
			try {
				MPVLib.attachSurface(holder.surface)
				isSurfaceAttached = true
			} catch (e: Exception) {
				Timber.e(e, "Error attaching surface")
			}
		}
	}

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
		Timber.d("Surface changed: %dx%d", width, height)
		if (isMpvInitialized) {
			try {
				MPVLib.setDrawableSurfaceSize(width, height)
			} catch (e: Exception) {
				Timber.e(e, "Error setting surface size")
			}
		}
	}

	override fun surfaceDestroyed(holder: SurfaceHolder) {
		Timber.d("Surface destroyed")
		isSurfaceAttached = false
		if (isMpvInitialized) {
			try {
				MPVLib.detachSurface()
			} catch (e: Exception) {
				Timber.e(e, "Error detaching surface")
			}
		}
	}

	// ========================================================================
	// MpvEventHandler.EventListener Implementation
	// ========================================================================

	override fun onPropertyChange(property: String, value: Any?) {
		when (property) {
			"pause" -> {
				val paused = value as? Boolean ?: return
				isPaused = paused
				updateControllerPlaybackState()
			}

			"idle-active" -> {
				val idle = value as? Boolean ?: return
				isIdle = idle
				if (!idle) {
					// Keep local pause state synchronized when a new file becomes active.
					// Some startup sequences can transition idle->false without an immediate pause event.
					try {
						isPaused = MPVLib.getPropertyBoolean("pause")
					} catch (_: Exception) {
						// Keep last known pause state when property is not yet readable.
					}
				}
				updateControllerPlaybackState()
			}

			"eof-reached" -> {
				val eofReached = value as? Boolean ?: return
				if (eofReached) {
					notifyCompletionOnMain()
					stopProgressLoop()
				}
			}

			"speed" -> {
				val speed = (value as? Double)?.toFloat() ?: return
				handler.post { playbackControllerNotifiable?.onPlaybackSpeedChange(speed) }
			}
		}
	}

	override fun onEndFile(reason: Int) {
		Timber.d("File ended with reason: %d", reason)

		when (reason) {
			MpvEventHandler.EndFileReason.EOF -> {
				notifyCompletionOnMain()
			}
			MpvEventHandler.EndFileReason.ERROR -> {
				handler.post { playbackControllerNotifiable?.onError() }
			}
		}

		stopProgressLoop()
	}

	override fun onFileLoaded() {
		Timber.d("File loaded")
		isIdle = false
		ensureAutoplayOnFileLoaded()

		// Refresh tracks when file is loaded
		trackManager.refreshTracks()
	}

	override fun onStartFile() {
		Timber.d("File started loading")
		isIdle = false
	}

	override fun onSeek() {
		Timber.d("Seek started")
	}

	override fun onPlaybackRestart() {
		Timber.d("Playback restarted after seek")
	}

	override fun onVideoReconfig() {
		Timber.d("Video reconfigured")
	}

	override fun onAudioReconfig() {
		Timber.d("Audio reconfigured")
	}

	override fun onShutdown() {
		Timber.d("mpv shutdown")
		isMpvInitialized = false
	}

	override fun onIdle() {
		Timber.d("mpv idle")
		isIdle = true
		updateControllerPlaybackState()
	}

	/**
	 * Keep controller/UI playback state in sync with mpv state.
	 *
	 * mpv can emit pause=false before idle-active=false on startup. Re-checking from both handlers
	 * guarantees we notify onPrepared() once playback actually becomes active.
	 */
	private fun updateControllerPlaybackState() {
		val isActive = !isPaused && !isIdle

		if (isActive) {
			if (!wasActiveForController) {
				wasActiveForController = true
				notifyPreparedOnMain()
			}
			startProgressLoop()
			handler.post { helper.setScreensaverLock(true) }
		} else {
			wasActiveForController = false
			stopProgressLoop()
			handler.post { helper.setScreensaverLock(false) }
		}
	}

	private fun notifyPreparedOnMain() {
		handler.post {
			if (!isPaused && !isIdle && wasActiveForController) {
				playbackControllerNotifiable?.onPrepared()
			}
		}
	}

	private fun notifyCompletionOnMain() {
		handler.post { playbackControllerNotifiable?.onCompletion() }
	}

	private fun ensureAutoplayOnFileLoaded() {
		if (!userPreferences.get(UserPreferences.videoAutoPlay)) return

		try {
			val paused = MPVLib.getPropertyBoolean("pause")
			if (paused) {
				Timber.d("File loaded in paused state, forcing autoplay")
				MPVLib.setPropertyBoolean("pause", false)
				isPaused = false
				updateControllerPlaybackState()
			}
		} catch (e: Exception) {
			Timber.d(e, "Unable to read or set pause state during file-loaded")
		}
	}
}
