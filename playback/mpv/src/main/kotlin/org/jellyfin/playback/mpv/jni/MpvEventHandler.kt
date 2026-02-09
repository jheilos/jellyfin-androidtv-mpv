package org.jellyfin.playback.mpv.jni

import timber.log.Timber

/**
 * Handles events from the native mpv library.
 *
 * This class receives callbacks from JNI and dispatches them to registered listeners.
 * The native code calls the static methods in this class when events occur.
 */
object MpvEventHandler {
	/**
	 * mpv event IDs from libmpv.
	 * These correspond to mpv_event_id in the native API.
	 */
	object EventId {
		const val NONE = 0
		const val SHUTDOWN = 1
		const val LOG_MESSAGE = 2
		const val GET_PROPERTY_REPLY = 3
		const val SET_PROPERTY_REPLY = 4
		const val COMMAND_REPLY = 5
		const val START_FILE = 6
		const val END_FILE = 7
		const val FILE_LOADED = 8
		const val IDLE = 11
		const val TICK = 14
		const val CLIENT_MESSAGE = 16
		const val VIDEO_RECONFIG = 17
		const val AUDIO_RECONFIG = 18
		const val SEEK = 20
		const val PLAYBACK_RESTART = 21
		const val PROPERTY_CHANGE = 22
		const val QUEUE_OVERFLOW = 24
	}

	/**
	 * End file reasons.
	 * These correspond to mpv_end_file_reason in the native API.
	 */
	object EndFileReason {
		const val EOF = 0
		const val STOP = 2
		const val QUIT = 3
		const val ERROR = 4
		const val REDIRECT = 5
	}

	/**
	 * Interface for receiving mpv events.
	 */
	interface EventListener {
		/**
		 * Called when a generic mpv event occurs.
		 *
		 * @param eventId The event type from [EventId]
		 */
		fun onEvent(eventId: Int) {}

		/**
		 * Called when an observed property changes.
		 *
		 * @param property The property name that changed
		 * @param value The new value (String, Int, Long, Double, or Boolean depending on format)
		 */
		fun onPropertyChange(property: String, value: Any?) {}

		/**
		 * Called when a file ends playback.
		 *
		 * @param reason The reason from [EndFileReason]
		 */
		fun onEndFile(reason: Int) {}

		/**
		 * Called when a file starts loading.
		 */
		fun onStartFile() {}

		/**
		 * Called when a file is fully loaded and ready for playback.
		 */
		fun onFileLoaded() {}

		/**
		 * Called when a seek operation completes.
		 */
		fun onSeek() {}

		/**
		 * Called when playback restarts after a seek.
		 */
		fun onPlaybackRestart() {}

		/**
		 * Called when the video is reconfigured (resolution, format change).
		 */
		fun onVideoReconfig() {}

		/**
		 * Called when the audio is reconfigured.
		 */
		fun onAudioReconfig() {}

		/**
		 * Called when mpv shuts down.
		 */
		fun onShutdown() {}

		/**
		 * Called when mpv becomes idle (no file playing).
		 */
		fun onIdle() {}
	}

	/**
	 * Interface for receiving mpv log messages.
	 */
	interface LogListener {
		/**
		 * Called when mpv emits a log message.
		 *
		 * @param prefix Log prefix (module name)
		 * @param level Log level (e.g., "v", "debug", "info", "warn", "error")
		 * @param text The log message text
		 */
		fun onLogMessage(prefix: String, level: String, text: String)
	}

	private val eventListeners = mutableListOf<EventListener>()
	private val logListeners = mutableListOf<LogListener>()

	/**
	 * Register this handler to receive callbacks from the native library.
	 * Must be called after loadLibrary() succeeds.
	 */
	fun attach() {
		`is`.xyz.mpv.MPVLib.eventCallback = object : `is`.xyz.mpv.MPVLib.EventCallback {
			override fun onEvent(eventId: Int) = onMpvEvent(eventId)
			override fun onEndFile(reason: Int) = onMpvEndFile(reason)
			override fun onPropertyChange(property: String, value: String?) = onMpvPropertyChange(property, value)
			override fun onPropertyChange(property: String, value: Boolean) = onMpvPropertyChange(property, value)
			override fun onPropertyChange(property: String, value: Long) = onMpvPropertyChange(property, value)
			override fun onPropertyChange(property: String, value: Double) = onMpvPropertyChange(property, value)
			override fun onLogMessage(prefix: String, level: Int, text: String) {
				val levelStr = when {
					level >= 50 -> "trace"
					level >= 40 -> "debug"
					level >= 30 -> "v"
					level >= 20 -> "info"
					level >= 10 -> "warn"
					level >= 5 -> "error"
					else -> "fatal"
				}
				onMpvLogMessage(prefix, levelStr, text)
			}
		}
		Timber.d("MpvEventHandler attached to native callback system")
	}

	/**
	 * Unregister from native library callbacks.
	 * Should be called during cleanup/destroy.
	 */
	fun detach() {
		`is`.xyz.mpv.MPVLib.eventCallback = null
		Timber.d("MpvEventHandler detached from native callback system")
	}

	/**
	 * Add an event listener.
	 *
	 * @param listener The listener to add
	 */
	@Synchronized
	fun addEventListener(listener: EventListener) {
		if (!eventListeners.contains(listener)) {
			eventListeners.add(listener)
		}
	}

	/**
	 * Remove an event listener.
	 *
	 * @param listener The listener to remove
	 */
	@Synchronized
	fun removeEventListener(listener: EventListener) {
		eventListeners.remove(listener)
	}

	/**
	 * Add a log listener.
	 *
	 * @param listener The listener to add
	 */
	@Synchronized
	fun addLogListener(listener: LogListener) {
		if (!logListeners.contains(listener)) {
			logListeners.add(listener)
		}
	}

	/**
	 * Remove a log listener.
	 *
	 * @param listener The listener to remove
	 */
	@Synchronized
	fun removeLogListener(listener: LogListener) {
		logListeners.remove(listener)
	}

	/**
	 * Remove all listeners.
	 */
	@Synchronized
	fun clearListeners() {
		eventListeners.clear()
		logListeners.clear()
	}

	// ========================================================================
	// JNI Callback Methods
	// These methods are called from native code. Do not change signatures.
	// ========================================================================

	/**
	 * Called by JNI when a generic mpv event occurs.
	 */
	@JvmStatic
	fun onMpvEvent(eventId: Int) {
		Timber.v("mpv event: %d", eventId)

		val listeners = synchronized(this) { eventListeners.toList() }

		when (eventId) {
			EventId.SHUTDOWN -> listeners.forEach { it.onShutdown() }
			EventId.START_FILE -> listeners.forEach { it.onStartFile() }
			EventId.FILE_LOADED -> listeners.forEach { it.onFileLoaded() }
			EventId.IDLE -> listeners.forEach { it.onIdle() }
			EventId.SEEK -> listeners.forEach { it.onSeek() }
			EventId.PLAYBACK_RESTART -> listeners.forEach { it.onPlaybackRestart() }
			EventId.VIDEO_RECONFIG -> listeners.forEach { it.onVideoReconfig() }
			EventId.AUDIO_RECONFIG -> listeners.forEach { it.onAudioReconfig() }
		}

		listeners.forEach { it.onEvent(eventId) }
	}

	/**
	 * Called by JNI when a file ends.
	 */
	@JvmStatic
	fun onMpvEndFile(reason: Int) {
		Timber.d("mpv end file: reason=%d", reason)

		val listeners = synchronized(this) { eventListeners.toList() }
		listeners.forEach { it.onEndFile(reason) }
	}

	/**
	 * Called by JNI when a string property changes.
	 */
	@JvmStatic
	fun onMpvPropertyChange(property: String, value: String?) {
		Timber.v("mpv property change: %s = %s", property, value)

		val listeners = synchronized(this) { eventListeners.toList() }
		listeners.forEach { it.onPropertyChange(property, value) }
	}

	/**
	 * Called by JNI when a boolean property changes.
	 */
	@JvmStatic
	fun onMpvPropertyChange(property: String, value: Boolean) {
		Timber.v("mpv property change: %s = %b", property, value)

		val listeners = synchronized(this) { eventListeners.toList() }
		listeners.forEach { it.onPropertyChange(property, value) }
	}

	/**
	 * Called by JNI when a long property changes.
	 */
	@JvmStatic
	fun onMpvPropertyChange(property: String, value: Long) {
		Timber.v("mpv property change: %s = %d", property, value)

		val listeners = synchronized(this) { eventListeners.toList() }
		listeners.forEach { it.onPropertyChange(property, value) }
	}

	/**
	 * Called by JNI when a double property changes.
	 */
	@JvmStatic
	fun onMpvPropertyChange(property: String, value: Double) {
		Timber.v("mpv property change: %s = %f", property, value)

		val listeners = synchronized(this) { eventListeners.toList() }
		listeners.forEach { it.onPropertyChange(property, value) }
	}

	/**
	 * Called by JNI when mpv emits a log message.
	 */
	@JvmStatic
	fun onMpvLogMessage(prefix: String, level: String, text: String) {
		// Forward to Timber based on level
		val trimmedText = text.trim()
		when (level) {
			"fatal", "error" -> Timber.tag("mpv/$prefix").e(trimmedText)
			"warn" -> Timber.tag("mpv/$prefix").w(trimmedText)
			"info" -> Timber.tag("mpv/$prefix").i(trimmedText)
			"v", "debug" -> Timber.tag("mpv/$prefix").d(trimmedText)
			"trace" -> Timber.tag("mpv/$prefix").v(trimmedText)
		}

		val listeners = synchronized(this) { logListeners.toList() }
		listeners.forEach { it.onLogMessage(prefix, level, trimmedText) }
	}
}
