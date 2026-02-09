package org.jellyfin.androidtv.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.profile.createDeviceProfile
import org.jellyfin.playback.core.playbackManager
import org.jellyfin.playback.jellyfin.jellyfinPlugin
import org.jellyfin.playback.media3.session.MediaSessionOptions
import org.jellyfin.playback.media3.session.media3SessionPlugin
import org.jellyfin.playback.mpv.MpvPlayerOptions
import org.jellyfin.playback.mpv.mpvPlayerPlugin
import org.koin.android.ext.koin.androidContext
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Duration.Companion.milliseconds
import org.jellyfin.androidtv.ui.playback.PlaybackManager as LegacyPlaybackManager

val playbackModule = module {
	single { LegacyPlaybackManager(get()) }
	single { VideoQueueManager() }
	single<MediaManager> { RewriteMediaManager(get(), get()) }

	single { PlaybackLauncher(get(), get(), get(), get()) }

	single { createPlaybackManager() }
}

fun Scope.createPlaybackManager() = playbackManager(androidContext()) {
	val activityIntent = Intent(get(), MainActivity::class.java)
	val pendingIntent = PendingIntent.getActivity(get(), 0, activityIntent, PendingIntent.FLAG_IMMUTABLE)

	val notificationChannelId = "session"
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		val channel = NotificationChannel(
			notificationChannelId,
			notificationChannelId,
			NotificationManager.IMPORTANCE_LOW
		)
		channel.setShowBadge(false)
		NotificationManagerCompat.from(get()).createNotificationChannel(channel)
	}

	val userPreferences = get<UserPreferences>()
	val mpvPlayerOptions = MpvPlayerOptions(
		enableDebugLogging = userPreferences[UserPreferences.debuggingEnabled],
	)
	install(mpvPlayerPlugin(get(), mpvPlayerOptions))

	val mediaSessionOptions = MediaSessionOptions(
		channelId = notificationChannelId,
		notificationId = 1,
		iconSmall = R.drawable.app_icon_foreground,
		openIntent = pendingIntent,
	)
	install(media3SessionPlugin(get(), mediaSessionOptions))

	val deviceProfileBuilder = { createDeviceProfile(androidContext(), userPreferences, get()) }
	install(jellyfinPlugin(get(), deviceProfileBuilder, ProcessLifecycleOwner.get().lifecycle))

	// Options
	val userSettingPreferences = get<UserSettingPreferences>()
	defaultRewindAmount = { userSettingPreferences[UserSettingPreferences.skipBackLength].milliseconds }
	defaultFastForwardAmount = { userSettingPreferences[UserSettingPreferences.skipForwardLength].milliseconds }
}
