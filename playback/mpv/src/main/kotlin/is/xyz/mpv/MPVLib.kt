package `is`.xyz.mpv

import android.content.Context
import android.view.Surface

/**
 * JNI shim matching mpv-android's native library expectations.
 *
 * libplayer.so expects JNI functions at Java_is_xyz_mpv_MPVLib_*.
 * This class name must be "MPVLib" in package "is.xyz.mpv" to match.
 *
 * All actual logic lives in org.jellyfin.playback.mpv.jni.MPVLib which
 * delegates to this class.
 */
object MPVLib {
	var eventCallback: EventCallback? = null

	interface EventCallback {
		fun onEvent(eventId: Int)
		fun onEndFile(reason: Int)
		fun onPropertyChange(property: String, value: String?)
		fun onPropertyChange(property: String, value: Boolean)
		fun onPropertyChange(property: String, value: Long)
		fun onPropertyChange(property: String, value: Double)
		fun onLogMessage(prefix: String, level: Int, text: String)
	}

	// --- Native methods (called by our wrapper) ---

	@JvmStatic external fun create(appctx: Context)
	@JvmStatic external fun init()
	@JvmStatic external fun destroy()

	@JvmStatic external fun attachSurface(surface: Surface)
	@JvmStatic external fun detachSurface()

	@JvmStatic external fun command(cmd: Array<out String>)
	@JvmStatic external fun setOptionString(name: String, value: String): Int

	@JvmStatic external fun getPropertyString(name: String): String?
	@JvmStatic external fun getPropertyInt(name: String): Int?
	@JvmStatic external fun getPropertyDouble(name: String): Double?
	@JvmStatic external fun getPropertyBoolean(name: String): Boolean?
	@JvmStatic external fun setPropertyString(name: String, value: String)
	@JvmStatic external fun setPropertyInt(name: String, value: Int)
	@JvmStatic external fun setPropertyDouble(name: String, value: Double)
	@JvmStatic external fun setPropertyBoolean(name: String, value: Boolean)

	@JvmStatic external fun observeProperty(property: String, format: Int)

	// --- Callback methods (called FROM native code) ---
	// The native event loop in libplayer.so calls these static methods.
	// Method names and signatures must exactly match what the native code expects.

	@JvmStatic
	fun eventProperty(property: String) {
		eventCallback?.onPropertyChange(property, null)
	}

	@JvmStatic
	fun eventProperty(property: String, value: Long) {
		eventCallback?.onPropertyChange(property, value)
	}

	@JvmStatic
	fun eventProperty(property: String, value: Double) {
		eventCallback?.onPropertyChange(property, value)
	}

	@JvmStatic
	fun eventProperty(property: String, value: Boolean) {
		eventCallback?.onPropertyChange(property, value)
	}

	@JvmStatic
	fun eventProperty(property: String, value: String) {
		eventCallback?.onPropertyChange(property, value)
	}

	@JvmStatic
	fun event(eventId: Int) {
		eventCallback?.onEvent(eventId)
	}

	@JvmStatic
	fun logMessage(prefix: String, level: Int, text: String) {
		eventCallback?.onLogMessage(prefix, level, text)
	}
}
