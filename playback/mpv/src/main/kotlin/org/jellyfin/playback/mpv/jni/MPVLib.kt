package org.jellyfin.playback.mpv.jni

import android.content.Context
import android.view.Surface
import timber.log.Timber
import `is`.xyz.mpv.MPVLib as NativeMPV

/**
 * Wrapper around the native mpv library.
 * Delegates to the JNI shim at is.xyz.mpv.MPVLib which matches
 * the native libplayer.so function names.
 */
object MPVLib {
	/**
	 * Property format constants for [observeProperty].
	 * These correspond to mpv_format values in the native mpv API.
	 */
	object PropertyFormat {
		const val NONE = 0
		const val STRING = 1
		const val OSD_STRING = 2
		const val FLAG = 3
		const val INT64 = 4
		const val DOUBLE = 5
		const val NODE = 6
	}

	/**
	 * Log levels for mpv logging.
	 */
	object LogLevel {
		const val NONE = 0
		const val FATAL = 10
		const val ERROR = 20
		const val WARN = 30
		const val INFO = 40
		const val V = 50
		const val DEBUG = 60
		const val TRACE = 70
	}

	private var isLibraryLoaded = false

	/**
	 * Load the native mpv library.
	 * Must be called before any other methods in this class.
	 *
	 * @return true if library was loaded successfully, false otherwise
	 */
	@Synchronized
	fun loadLibrary(): Boolean {
		if (isLibraryLoaded) return true

		return try {
			System.loadLibrary("player") // loads libplayer.so (JNI bridge)
			// libmpv.so and FFmpeg .so files are loaded transitively
			isLibraryLoaded = true
			Timber.d("libmpv loaded successfully")
			true
		} catch (e: UnsatisfiedLinkError) {
			Timber.e(e, "Failed to load libmpv native library")
			false
		}
	}

	/**
	 * Check if the native library is loaded.
	 */
	fun isLoaded(): Boolean = isLibraryLoaded

	// --- Lifecycle ---
	fun create(context: Context) = NativeMPV.create(context)
	fun initialize() = NativeMPV.init()
	fun destroy() = NativeMPV.destroy()

	// --- Surface ---
	fun attachSurface(surface: Surface) = NativeMPV.attachSurface(surface)
	fun detachSurface() = NativeMPV.detachSurface()
	fun setDrawableSurfaceSize(width: Int, height: Int) { /* no-op, mpv handles internally */ }

	// --- Commands ---
	fun command(cmd: Array<String>) = NativeMPV.command(cmd)

	// --- Options ---
	fun setOptionString(name: String, value: String) { NativeMPV.setOptionString(name, value) }

	// --- Property Getters ---
	fun getPropertyString(name: String): String? = NativeMPV.getPropertyString(name)
	fun getPropertyInt(name: String): Int = NativeMPV.getPropertyInt(name) ?: 0
	fun getPropertyLong(name: String): Long = (NativeMPV.getPropertyInt(name) ?: 0).toLong()
	fun getPropertyDouble(name: String): Double = NativeMPV.getPropertyDouble(name) ?: 0.0
	fun getPropertyBoolean(name: String): Boolean = NativeMPV.getPropertyBoolean(name) ?: false

	// --- Property Setters ---
	fun setPropertyString(name: String, value: String) = NativeMPV.setPropertyString(name, value)
	fun setPropertyInt(name: String, value: Int) = NativeMPV.setPropertyInt(name, value)
	fun setPropertyLong(name: String, value: Long) = NativeMPV.setPropertyInt(name, value.toInt())
	fun setPropertyDouble(name: String, value: Double) = NativeMPV.setPropertyDouble(name, value)
	fun setPropertyBoolean(name: String, value: Boolean) = NativeMPV.setPropertyBoolean(name, value)

	// --- Observation ---
	fun observeProperty(property: String, format: Int) = NativeMPV.observeProperty(property, format)

	// --- Log Level ---
	fun setLogLevel(level: String) { /* no-op - not available in mpv-android JNI */ }
}
