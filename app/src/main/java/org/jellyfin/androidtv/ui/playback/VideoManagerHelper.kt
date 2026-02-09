package org.jellyfin.androidtv.ui.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.jellyfin.sdk.model.api.MediaStream
import timber.log.Timber

/**
 * Return the media type for the codec found in this media stream. First tries to infer the media type from the streams delivery URL and
 * falls back to the original stream codec.
 */
fun getSubtitleMediaStreamCodec(stream: MediaStream): String {
	val codec = requireNotNull(stream.codec)
	val codecMediaType = getSubtitleMimeType(codec, "").ifBlank { null }

	val urlSubtitleExtension = stream.deliveryUrl?.toUri()?.lastPathSegment?.split('.')?.last()
	val urlExtensionMediaType = urlSubtitleExtension?.let { getSubtitleMimeType(it, "") }?.ifBlank { null }

	return urlExtensionMediaType ?: codecMediaType ?: urlSubtitleExtension ?: codec
}

private val ffmpegSubtitleMimeTypes = mapOf(
	"mp4" to MimeTypes.VIDEO_MP4,
	"ass" to MimeTypes.TEXT_SSA,
	"dvbsub" to MimeTypes.APPLICATION_DVBSUBS,
	"idx" to MimeTypes.APPLICATION_VOBSUB,
	"pgs" to MimeTypes.APPLICATION_PGS,
	"pgssub" to MimeTypes.APPLICATION_PGS,
	"srt" to MimeTypes.APPLICATION_SUBRIP,
	"ssa" to MimeTypes.TEXT_SSA,
	"subrip" to MimeTypes.APPLICATION_SUBRIP,
	"vtt" to MimeTypes.TEXT_VTT,
	"ttml" to MimeTypes.APPLICATION_TTML,
	"webvtt" to MimeTypes.TEXT_VTT,
)

@OptIn(UnstableApi::class)
private fun getSubtitleMimeType(codec: String, fallback: String = codec): String {
	val normalized = codec.lowercase()
	return ffmpegSubtitleMimeTypes[normalized]
		?: MimeTypes.getTextMediaMimeType(normalized)
		?: fallback
}

private var audioEffect: AudioEffect? = null

fun applyAudioNightmode(audioSessionId: Int) {
	Timber.i("Enabling audio night mode for session $audioSessionId")

	audioEffect?.release()

	audioEffect = when {
		// Use dynamics processinc on Android 9 (API 28) and newer
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> DynamicsProcessing(0, audioSessionId, null).apply {
			setLimiterAllChannelsTo(
				DynamicsProcessing.Limiter(
					true,
					true,
					1,
					30f,
					300f,
					10f,
					-24f,
					3f
				)
			)

			setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, true, 5).apply {
				getBand(0).gain = 0f
				getBand(1).gain = 0.02f
				getBand(2).gain = 0.03f
				getBand(3).gain = 0.02f
				getBand(4).gain = 0f
			})

			enabled = true
		}

		// Use more simple equalizer implementation on older versions
		else -> Equalizer(0, audioSessionId).apply {
			setBandLevel(0, 0)
			setBandLevel(1, 2)
			setBandLevel(2, 3)
			setBandLevel(3, 2)
			setBandLevel(4, 0)
			enabled = true
		}
	}
}
