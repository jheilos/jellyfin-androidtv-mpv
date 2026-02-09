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

class SelectAudioAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null

	init {
		initializeWithIcon(R.drawable.ic_select_audio)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		val videoManager = playbackController.videoManager
		if (videoManager == null) {
			Timber.w("VideoManager null trying to obtain audio tracks")
			Toast.makeText(context, "Unable to obtain audio track info", Toast.LENGTH_LONG).show()
			return
		}

		val trackManager = videoManager.trackManager
		val mpvTracks = trackManager.tracks.value
		val selectedAudioId = trackManager.selectedAudioTrackId.value

		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		dismissPopup()
		popup = PopupMenu(context, view, Gravity.END).apply {
			with(menu) {
				var order = 0
				for (track in mpvTracks.audioTracks) {
					add(0, track.id, order++, track.displayName).apply {
						isChecked = track.id == selectedAudioId
					}
				}
				setGroupCheckable(0, true, false)
			}

			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
			setOnMenuItemClickListener { item ->
				trackManager.selectAudioTrack(item.itemId)
				true
			}
		}
		popup?.show()
	}

	fun dismissPopup() {
		popup?.dismiss()
	}
}
