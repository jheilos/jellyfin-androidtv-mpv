package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import timber.log.Timber

class ClosedCaptionsAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null

	init {
		initializeWithIcon(R.drawable.ic_select_subtitle)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		val videoManager = playbackController.videoManager
		if (videoManager == null) {
			Timber.w("VideoManager null trying to obtain subtitles")
			Toast.makeText(context, "Unable to obtain subtitle info", Toast.LENGTH_LONG).show()
			return
		}

		val trackManager = videoManager.trackManager
		val mpvTracks = trackManager.tracks.value
		val selectedSubId = trackManager.selectedSubtitleTrackId.value

		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		removePopup()
		popup = PopupMenu(context, view, Gravity.END).apply {
			with(menu) {
				var order = 0
				// "None" option to disable subtitles
				add(0, -1, order++, context.getString(R.string.lbl_none)).apply {
					isChecked = selectedSubId == null
				}

				// List all subtitle tracks from mpv
				for (sub in mpvTracks.subtitleTracks) {
					add(0, sub.id, order++, sub.displayName).apply {
						isChecked = sub.id == selectedSubId
					}
				}

				setGroupCheckable(0, true, false)
			}
			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
			setOnMenuItemClickListener { item ->
				val trackId = item.itemId
				if (trackId == -1) {
					// Disable subtitles
					trackManager.selectSubtitleTrack(null)
				} else {
					// Select the subtitle track by mpv track ID
					trackManager.selectSubtitleTrack(trackId)
				}
				true
			}
		}
		popup?.show()
	}

	fun removePopup() {
		popup?.dismiss()
	}
}
