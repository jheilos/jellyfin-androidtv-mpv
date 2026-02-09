package org.jellyfin.playback.mpv

import android.content.Context
import android.view.SurfaceHolder
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.mpv.jni.MPVLib
import org.jellyfin.playback.mpv.jni.MpvEventHandler
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * mpv-based player backend implementation.
 *
 * This backend uses libmpv for media playback, providing broad format support
 * including advanced subtitle rendering via libass.
 *
 * @param context Android context for accessing app directories
 * @param options Configuration options for the mpv player
 */
class MpvPlayerBackend(
	private val context: Context,
	private val options: MpvPlayerOptions,
) : BasePlayerBackend(), MpvEventHandler.EventListener, SurfaceHolder.Callback {
	private var currentStream: PlayableMediaStream? = null
	private var surfaceView: PlayerSurfaceView? = null
	private var subtitleView: PlayerSubtitleView? = null
	private var isMpvInitialized = false
	private var isPaused = true
	private var isIdle = true
	private var isSeeking = false

	/**
	 * Initialize the mpv player.
	 * Must be called before any playback operations.
	 */
	fun initialize() {
		if (isMpvInitialized) {
			Timber.w("mpv already initialized")
			return
		}

		// Load native library
		if (!MPVLib.loadLibrary()) {
			Timber.e("Failed to load libmpv native library")
			listener?.onPlayStateChange(PlayState.ERROR)
			return
		}

		// Attach event handler to native callback system
		MpvEventHandler.attach()

		try {
			// Create mpv instance
			MPVLib.create(context)

			// Apply options before initialization
			options.applyOptions { name, value ->
				Timber.d("Setting mpv option: %s = %s", name, value)
				MPVLib.setOptionString(name, value)
			}

			// Set log level
			if (options.enableDebugLogging) {
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
			listener?.onPlayStateChange(PlayState.ERROR)
		}
	}

	/**
	 * Observe mpv properties for state tracking.
	 */
	private fun observeProperties() {
		// Observe pause state
		MPVLib.observeProperty("pause", MPVLib.PropertyFormat.FLAG)

		// Observe idle state
		MPVLib.observeProperty("idle-active", MPVLib.PropertyFormat.FLAG)

		// Observe EOF
		MPVLib.observeProperty("eof-reached", MPVLib.PropertyFormat.FLAG)

		// Observe video dimensions
		MPVLib.observeProperty("video-params/w", MPVLib.PropertyFormat.INT64)
		MPVLib.observeProperty("video-params/h", MPVLib.PropertyFormat.INT64)

		// Observe duration (for when it becomes available)
		MPVLib.observeProperty("duration", MPVLib.PropertyFormat.DOUBLE)
	}

	/**
	 * Release the mpv player and free resources.
	 */
	fun release() {
		if (!isMpvInitialized) return

		try {
			MpvEventHandler.removeEventListener(this)
			MpvEventHandler.detach()
			MPVLib.destroy()
			isMpvInitialized = false
			currentStream = null
			Timber.i("mpv released")
		} catch (e: Exception) {
			Timber.e(e, "Error releasing mpv")
		}
	}

	// ========================================================================
	// PlayerBackend Implementation
	// ========================================================================

	override fun supportsStream(stream: MediaStream): PlaySupportReport {
		// mpv with FFmpeg supports virtually all formats
		// In the future, we could check for specific codec support if needed
		return MpvPlaySupportReport.SUPPORTED
	}

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		// Remove callback from old surface
		this.surfaceView?.surface?.holder?.removeCallback(this)

		this.surfaceView = surfaceView

		if (surfaceView != null) {
			// Add callback to new surface
			surfaceView.surface.holder.addCallback(this)

			// If surface is already created, attach it
			val holder = surfaceView.surface.holder
			if (holder.surface.isValid) {
				attachSurface(holder)
			}
		} else {
			// Detach surface
			if (isMpvInitialized) {
				MPVLib.detachSurface()
			}
		}
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {
		// mpv renders subtitles directly to the video surface via libass
		// The PlayerSubtitleView is not used for mpv, but we store the reference
		// in case we want to implement custom subtitle rendering in the future
		this.subtitleView = surfaceView
	}

	override fun getPositionInfo(): PositionInfo {
		if (!isMpvInitialized || isIdle) {
			return PositionInfo.EMPTY
		}

		return try {
			val position = MPVLib.getPropertyDouble("time-pos")
			val duration = MPVLib.getPropertyDouble("duration")
			val cacheTime = MPVLib.getPropertyDouble("demuxer-cache-time")

			// Calculate buffer position
			val bufferPosition = if (cacheTime > 0) {
				(position + cacheTime).coerceAtMost(duration)
			} else {
				position
			}

			PositionInfo(
				active = position.seconds,
				buffer = bufferPosition.seconds,
				duration = if (duration > 0) duration.seconds else Duration.ZERO,
			)
		} catch (e: Exception) {
			Timber.w(e, "Error getting position info")
			PositionInfo.EMPTY
		}
	}

	override fun prepareItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream) { "QueueEntry must have a media stream" }

		ensureInitialized()

		Timber.d("Preparing item: %s", stream.url)

		// mpv doesn't have a separate prepare step, but we can pre-load the file
		// without starting playback by using loadfile with "replace" and pausing
		// For now, we'll just store the reference for playItem
	}

	override fun playItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream) { "QueueEntry must have a media stream" }

		// Don't reload if already playing this stream
		if (currentStream == stream) {
			Timber.d("Already playing this stream, resuming")
			play()
			return
		}

		ensureInitialized()

		currentStream = stream
		Timber.i("Playing: %s", stream.url)

		try {
			// Load the file and start playback
			MPVLib.command(arrayOf("loadfile", stream.url, "replace"))

			// Ensure we're not paused
			MPVLib.setPropertyBoolean("pause", false)
			isPaused = false
			isIdle = false
		} catch (e: Exception) {
			Timber.e(e, "Error loading file")
			listener?.onPlayStateChange(PlayState.ERROR)
		}
	}

	override fun play() {
		if (!isMpvInitialized) return

		// If idle (no file loaded), do nothing
		if (isIdle && currentStream == null) {
			Timber.w("Cannot play: no file loaded")
			return
		}

		try {
			MPVLib.setPropertyBoolean("pause", false)
			isPaused = false
		} catch (e: Exception) {
			Timber.e(e, "Error resuming playback")
		}
	}

	override fun pause() {
		if (!isMpvInitialized) return

		try {
			MPVLib.setPropertyBoolean("pause", true)
			isPaused = true
		} catch (e: Exception) {
			Timber.e(e, "Error pausing playback")
		}
	}

	override fun stop() {
		if (!isMpvInitialized) return

		try {
			MPVLib.command(arrayOf("stop"))
			currentStream = null
			isIdle = true
			listener?.onPlayStateChange(PlayState.STOPPED)
		} catch (e: Exception) {
			Timber.e(e, "Error stopping playback")
		}
	}

	override fun seekTo(position: Duration) {
		if (!isMpvInitialized) return

		try {
			isSeeking = true
			val seconds = position.inWholeSeconds.toString()
			MPVLib.command(arrayOf("seek", seconds, "absolute"))
			Timber.d("Seeking to %s seconds", seconds)
		} catch (e: Exception) {
			Timber.e(e, "Error seeking")
			isSeeking = false
		}
	}

	override fun setScrubbing(scrubbing: Boolean) {
		// mpv doesn't have a specific scrubbing mode
		// We could implement this by setting hr-seek based on scrubbing state
		// For now, we just use the default seek behavior
		if (!isMpvInitialized) return

		try {
			if (scrubbing) {
				// Use fast seeking during scrubbing
				MPVLib.setPropertyString("hr-seek", "no")
			} else {
				// Use accurate seeking when not scrubbing
				MPVLib.setPropertyString("hr-seek", "yes")
			}
		} catch (e: Exception) {
			Timber.w(e, "Error setting scrubbing mode")
		}
	}

	override fun setSpeed(speed: Float) {
		if (!isMpvInitialized) return

		try {
			MPVLib.setPropertyDouble("speed", speed.toDouble())
			Timber.d("Speed set to %f", speed)
		} catch (e: Exception) {
			Timber.e(e, "Error setting speed")
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
				val state = if (paused) PlayState.PAUSED else PlayState.PLAYING
				listener?.onPlayStateChange(state)
			}

			"idle-active" -> {
				val idle = value as? Boolean ?: return
				isIdle = idle
				if (idle) {
					listener?.onPlayStateChange(PlayState.STOPPED)
				}
			}

			"eof-reached" -> {
				val eofReached = value as? Boolean ?: return
				if (eofReached && currentStream != null) {
					Timber.d("End of file reached")
					listener?.onMediaStreamEnd(currentStream!!)
				}
			}

			"video-params/w", "video-params/h" -> {
				updateVideoSize()
			}

			"duration" -> {
				// Duration became available, might want to notify UI
				Timber.d("Duration: %s", value)
			}
		}
	}

	override fun onEndFile(reason: Int) {
		Timber.d("File ended with reason: %d", reason)

		when (reason) {
			MpvEventHandler.EndFileReason.EOF -> {
				currentStream?.let { stream ->
					listener?.onMediaStreamEnd(stream)
				}
			}

			MpvEventHandler.EndFileReason.ERROR -> {
				listener?.onPlayStateChange(PlayState.ERROR)
			}

			MpvEventHandler.EndFileReason.STOP -> {
				listener?.onPlayStateChange(PlayState.STOPPED)
			}
		}
	}

	override fun onStartFile() {
		Timber.d("File started loading")
		isIdle = false
	}

	override fun onFileLoaded() {
		Timber.d("File loaded")
		isIdle = false
		// File is loaded, playback should start if not paused
		if (!isPaused) {
			listener?.onPlayStateChange(PlayState.PLAYING)
		}
	}

	override fun onSeek() {
		Timber.d("Seek started")
		isSeeking = true
	}

	override fun onPlaybackRestart() {
		Timber.d("Playback restarted after seek")
		isSeeking = false
	}

	override fun onVideoReconfig() {
		Timber.d("Video reconfigured")
		updateVideoSize()
	}

	override fun onShutdown() {
		Timber.d("mpv shutdown")
		isMpvInitialized = false
	}

	override fun onIdle() {
		Timber.d("mpv idle")
		isIdle = true
		listener?.onPlayStateChange(PlayState.STOPPED)
	}

	// ========================================================================
	// SurfaceHolder.Callback Implementation
	// ========================================================================

	override fun surfaceCreated(holder: SurfaceHolder) {
		Timber.d("Surface created")
		attachSurface(holder)
	}

	override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
		Timber.d("Surface changed: %dx%d", width, height)
		if (isMpvInitialized) {
			MPVLib.setDrawableSurfaceSize(width, height)
		}
	}

	override fun surfaceDestroyed(holder: SurfaceHolder) {
		Timber.d("Surface destroyed")
		if (isMpvInitialized) {
			MPVLib.detachSurface()
		}
	}

	// ========================================================================
	// Private Helpers
	// ========================================================================

	private fun ensureInitialized() {
		if (!isMpvInitialized) {
			initialize()
		}
	}

	private fun attachSurface(holder: SurfaceHolder) {
		if (!isMpvInitialized) {
			// Defer attachment until mpv is initialized
			return
		}

		try {
			MPVLib.attachSurface(holder.surface)
			val rect = holder.surfaceFrame
			MPVLib.setDrawableSurfaceSize(rect.width(), rect.height())
			Timber.d("Surface attached: %dx%d", rect.width(), rect.height())
		} catch (e: Exception) {
			Timber.e(e, "Error attaching surface")
		}
	}

	private fun updateVideoSize() {
		if (!isMpvInitialized) return

		try {
			val width = MPVLib.getPropertyInt("video-params/w")
			val height = MPVLib.getPropertyInt("video-params/h")

			if (width > 0 && height > 0) {
				Timber.d("Video size: %dx%d", width, height)
				listener?.onVideoSizeChange(width, height)
			}
		} catch (e: Exception) {
			Timber.w(e, "Error getting video size")
		}
	}
}
