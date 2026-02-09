package org.jellyfin.playback.mpv

/**
 * Hardware decoding mode for mpv.
 *
 * @property value The mpv option value for hwdec
 */
enum class HwdecMode(val value: String) {
	/**
	 * Disable hardware decoding, use software decoding only.
	 */
	NO("no"),

	/**
	 * Automatically select the best hardware decoder.
	 * Falls back to software decoding if none available.
	 */
	AUTO("auto"),

	/**
	 * Use MediaCodec with copy-back mode.
	 * More compatible but slightly slower than direct rendering.
	 * Recommended for Android TV.
	 */
	MEDIACODEC_COPY("mediacodec-copy"),

	/**
	 * Use MediaCodec with direct rendering.
	 * Best performance but may have compatibility issues with some devices.
	 */
	MEDIACODEC("mediacodec"),
}

/**
 * Audio output channel configuration.
 *
 * @property value The mpv option value for audio-channels
 */
enum class AudioChannels(val value: String) {
	/**
	 * Auto-detect audio channels from the source.
	 */
	AUTO("auto"),

	/**
	 * Downmix to stereo.
	 * Useful for devices without surround sound support.
	 */
	STEREO("stereo"),

	/**
	 * Force mono output.
	 */
	MONO("mono"),

	/**
	 * 5.1 surround sound.
	 */
	SURROUND_5_1("5.1"),

	/**
	 * 7.1 surround sound.
	 */
	SURROUND_7_1("7.1"),
}

/**
 * Audio output driver configuration.
 *
 * @property value The mpv option value for ao
 */
enum class AudioOutput(val value: String) {
	/**
	 * Auto-select audio output driver.
	 */
	AUTO("auto"),

	/**
	 * Use Android AudioTrack API (recommended for most cases).
	 */
	AUDIOTRACK("audiotrack"),

	/**
	 * Use OpenSL ES (legacy, wider device support).
	 */
	OPENSLES("opensles"),

	/**
	 * Use AAudio (Android 8.0+, lowest latency).
	 */
	AAUDIO("aaudio"),

	/**
	 * Try AudioTrack first, then fall back to OpenSL ES.
	 */
	AUDIOTRACK_OPENSLES("audiotrack,opensles"),
}

/**
 * GPU rendering API for video output.
 *
 * @property value The mpv option value for gpu-api
 */
enum class GpuApi(val value: String) {
	/**
	 * Auto-select GPU API.
	 */
	AUTO("auto"),

	/**
	 * Use OpenGL ES.
	 */
	OPENGL("opengl"),

	/**
	 * Use Vulkan (if available, may offer better performance).
	 */
	VULKAN("vulkan"),
}

/**
 * Subtitle styling options.
 */
data class SubtitleOptions(
	/**
	 * Subtitle font size in scaled pixels.
	 * Default: 55
	 */
	val fontSize: Int = 55,

	/**
	 * Subtitle position from bottom as percentage (0-100).
	 * 100 = bottom of video, lower values move subtitles up.
	 * Default: 100
	 */
	val position: Int = 100,

	/**
	 * Subtitle border size in scaled pixels.
	 * Default: 3
	 */
	val borderSize: Double = 3.0,

	/**
	 * Subtitle shadow offset in scaled pixels.
	 * Default: 0
	 */
	val shadowOffset: Double = 0.0,

	/**
	 * Use margins for subtitle placement.
	 * When enabled, subtitles can be placed outside the video area
	 * on devices with letterboxing.
	 * Default: true
	 */
	val useMargins: Boolean = true,

	/**
	 * Override ASS/SSA subtitle styles with the settings above.
	 * When false, styled subtitles retain their embedded styling.
	 * Default: false (preserve ASS styling)
	 */
	val overrideAssStyle: Boolean = false,

	/**
	 * Force subtitles to render in the visible video area.
	 * Useful for preventing subtitles from being cut off.
	 * Default: false
	 */
	val forceInVideoArea: Boolean = false,
)

/**
 * Video output options.
 */
data class VideoOptions(
	/**
	 * GPU rendering API.
	 * Default: AUTO
	 */
	val gpuApi: GpuApi = GpuApi.AUTO,

	/**
	 * Enable HDR output when available.
	 * Uses target-colorspace-hint to signal HDR capability to the display.
	 * Default: true
	 */
	val enableHdr: Boolean = true,

	/**
	 * Debanding filter strength (0 = disabled).
	 * Higher values reduce color banding but may blur details.
	 * Default: 0 (disabled)
	 */
	val debandStrength: Int = 0,

	/**
	 * Interpolation for smoother playback (frame blending).
	 * May increase CPU/GPU usage.
	 * Default: false
	 */
	val enableInterpolation: Boolean = false,
)

/**
 * Cache and buffer options.
 */
data class CacheOptions(
	/**
	 * Enable network cache.
	 * Default: true
	 */
	val enabled: Boolean = true,

	/**
	 * Cache size in kilobytes.
	 * Default: 150MB (153600 KB)
	 */
	val sizeKb: Int = 153600,

	/**
	 * Seconds of media to cache ahead.
	 * Default: 150 seconds
	 */
	val cacheSecsAhead: Int = 150,

	/**
	 * Seconds of media to cache behind.
	 * Default: 150 seconds
	 */
	val cacheSecsBehind: Int = 150,

	/**
	 * Demuxer maximum bytes.
	 * Default: 150MB (157286400 bytes)
	 */
	val demuxerMaxBytes: Long = 150L * 1024 * 1024,
)

/**
 * Configuration options for the mpv player backend.
 *
 * These options are applied when initializing the mpv player.
 * Changes require recreating the player to take effect.
 */
data class MpvPlayerOptions(
	/**
	 * Hardware decoding mode.
	 * Default: MEDIACODEC (direct rendering to surface, best for Android TV)
	 */
	val hwdecMode: HwdecMode = HwdecMode.MEDIACODEC,

	/**
	 * Audio output channel configuration.
	 * Default: AUTO (preserve source channels)
	 */
	val audioChannels: AudioChannels = AudioChannels.AUTO,

	/**
	 * Audio output driver.
	 * Default: AUDIOTRACK_OPENSLES (wide compatibility)
	 */
	val audioOutput: AudioOutput = AudioOutput.AUDIOTRACK_OPENSLES,

	/**
	 * Subtitle display options.
	 */
	val subtitles: SubtitleOptions = SubtitleOptions(),

	/**
	 * Video output options.
	 */
	val video: VideoOptions = VideoOptions(),

	/**
	 * Cache and buffer options.
	 */
	val cache: CacheOptions = CacheOptions(),

	/**
	 * Enable debug logging from mpv.
	 * Logs will be forwarded to Timber.
	 * Default: false
	 */
	val enableDebugLogging: Boolean = false,

	/**
	 * Custom mpv options to apply.
	 * These are applied after all other options.
	 * Key: option name, Value: option value.
	 *
	 * Example: mapOf("vo" to "gpu", "gpu-context" to "android")
	 */
	val customOptions: Map<String, String> = emptyMap(),

	/**
	 * Directory for mpv configuration files (scripts, fonts, etc.).
	 * If null, a default cache directory will be used.
	 */
	val configDirectory: String? = null,
) {
	/**
	 * Apply all options to mpv via [applyTo] callback.
	 *
	 * @param applyTo Function that sets an mpv option (name, value)
	 */
	fun applyOptions(applyTo: (name: String, value: String) -> Unit) {
		// Hardware decoding
		applyTo("hwdec", hwdecMode.value)

		// Audio settings
		applyTo("ao", audioOutput.value)
		applyTo("audio-channels", audioChannels.value)

		// Video settings
		applyTo("vo", "gpu")
		applyTo("gpu-context", "android")
		applyTo("gpu-dumb-mode", "yes")
		if (video.gpuApi != GpuApi.AUTO) {
			applyTo("gpu-api", video.gpuApi.value)
		}
		if (video.enableHdr) {
			applyTo("target-colorspace-hint", "yes")
		}
		if (video.debandStrength > 0) {
			applyTo("deband", "yes")
			applyTo("deband-threshold", video.debandStrength.toString())
		}
		if (video.enableInterpolation) {
			applyTo("interpolation", "yes")
			applyTo("video-sync", "display-resample")
		}

		// Subtitle settings
		applyTo("sub-font-size", subtitles.fontSize.toString())
		applyTo("sub-pos", subtitles.position.toString())
		applyTo("sub-border-size", subtitles.borderSize.toString())
		applyTo("sub-shadow-offset", subtitles.shadowOffset.toString())
		if (subtitles.useMargins) {
			applyTo("sub-use-margins", "yes")
		}
		if (subtitles.overrideAssStyle) {
			applyTo("sub-ass-override", "force")
		}
		if (subtitles.forceInVideoArea) {
			applyTo("sub-ass-force-margins", "yes")
		}

		// Cache settings
		if (cache.enabled) {
			applyTo("cache", "yes")
			applyTo("demuxer-max-bytes", cache.demuxerMaxBytes.toString())
			applyTo("demuxer-readahead-secs", cache.cacheSecsAhead.toString())
			applyTo("demuxer-max-back-bytes", (cache.sizeKb * 1024L).toString())
			applyTo("cache-secs", cache.cacheSecsAhead.toString())
		} else {
			applyTo("cache", "no")
		}

		// Additional settings
		applyTo("keep-open", "yes")  // Keep player open after playback ends
		applyTo("save-position-on-quit", "no")  // Don't save position (app handles this)
		applyTo("force-window", "no")  // No window creation on Android

		// Apply custom options last (can override defaults)
		customOptions.forEach { (name, value) ->
			applyTo(name, value)
		}
	}
}
