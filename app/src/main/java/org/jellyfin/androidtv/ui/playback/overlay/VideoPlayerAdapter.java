package org.jellyfin.androidtv.ui.playback.overlay;

import androidx.annotation.NonNull;
import androidx.leanback.media.PlayerAdapter;

import org.jellyfin.androidtv.auth.repository.UserRepository;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment;
import org.jellyfin.androidtv.ui.playback.PlaybackController;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.StreamHelper;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.ChapterInfo;
import org.jellyfin.sdk.model.api.MediaSourceInfo;
import org.koin.java.KoinJavaComponent;

import java.util.List;

public class VideoPlayerAdapter extends PlayerAdapter {

    private final PlaybackController playbackController;
    private final UserPreferences userPreferences = KoinJavaComponent.get(UserPreferences.class);
    private CustomPlaybackOverlayFragment customPlaybackOverlayFragment;
    private LeanbackOverlayFragment leanbackOverlayFragment;

    VideoPlayerAdapter(PlaybackController playbackController, LeanbackOverlayFragment leanbackOverlayFragment) {
        this.playbackController = playbackController;
        this.leanbackOverlayFragment = leanbackOverlayFragment;
    }

    @Override
    public void play() {
        playbackController.play(playbackController.getCurrentPosition());
    }

    @Override
    public void pause() {
        playbackController.pause();
    }

    @Override
    public void rewind() {
        playbackController.rewind();
        updateCurrentPosition();
    }

    @Override
    public void fastForward() {
        playbackController.fastForward();
        updateCurrentPosition();
    }

    @Override
    public void seekTo(long positionInMs) {
        playbackController.seek(positionInMs);
        updateCurrentPosition();
    }

    @Override
    public void next() {
        if (userPreferences.get(UserPreferences.Companion.getSkipButtonsChapterNavigation())) {
            if (!skipToNextChapter()) {
                playbackController.next();
            }
            return;
        }

        playbackController.next();
    }

    @Override
    public void previous() {
        if (userPreferences.get(UserPreferences.Companion.getSkipButtonsChapterNavigation())) {
            if (!skipToPreviousChapter()) {
                playbackController.prev();
            }
            return;
        }

        playbackController.prev();
    }

    @Override
    public long getDuration() {
        Long runTimeTicks = null;
        if (getCurrentMediaSource() != null) runTimeTicks = getCurrentMediaSource().getRunTimeTicks();
        if (runTimeTicks == null && getCurrentlyPlayingItem() != null) runTimeTicks = getCurrentlyPlayingItem().getRunTimeTicks();
        if (runTimeTicks != null) return runTimeTicks / 10000;
        return -1;
    }

    @Override
    public long getCurrentPosition() {
        return playbackController.getCurrentPosition();
    }

    @Override
    public boolean isPlaying() {
        return playbackController.isPlaying();
    }

    @Override
    public long getBufferedPosition() {
        return playbackController.getBufferedPosition();
    }

    void updateCurrentPosition() {
        getCallback().onCurrentPositionChanged(this);
        getCallback().onBufferedPositionChanged(this);
    }

    void updatePlayState() {
        getCallback().onPlayStateChanged(this);
    }

    void updateDuration() {
        getCallback().onDurationChanged(this);
    }

    public boolean hasSubs() {
        return StreamHelper.getSubtitleStreams(playbackController.getCurrentMediaSource()).size() > 0;
    }

    public boolean hasMultiAudio() {
        return StreamHelper.getAudioStreams(playbackController.getCurrentMediaSource()).size() > 1;
    }

    boolean hasNextItem() {
        return playbackController.hasNextItem();
    }

    boolean hasPreviousItem() {
        return playbackController.hasPreviousItem();
    }

    boolean canSeek() {
        return playbackController.canSeek();
    }

    boolean isLiveTv() {
        return playbackController.isLiveTv();
    }

    void setMasterOverlayFragment(CustomPlaybackOverlayFragment customPlaybackOverlayFragment) {
        this.customPlaybackOverlayFragment = customPlaybackOverlayFragment;
    }

    @NonNull
    public CustomPlaybackOverlayFragment getMasterOverlayFragment() {
        return customPlaybackOverlayFragment;
    }

    @NonNull
    public LeanbackOverlayFragment getLeanbackOverlayFragment() {
        return leanbackOverlayFragment;
    }

    @Override
    public void onDetachedFromHost() {
        customPlaybackOverlayFragment = null;
        leanbackOverlayFragment = null;
    }

    boolean canRecordLiveTv() {
        org.jellyfin.sdk.model.api.BaseItemDto currentlyPlayingItem = getCurrentlyPlayingItem();
        return currentlyPlayingItem.getCurrentProgram() != null
                && Utils.canManageRecordings(KoinJavaComponent.<UserRepository>get(UserRepository.class).getCurrentUser().getValue());
    }

    public void toggleRecording() {
        org.jellyfin.sdk.model.api.BaseItemDto currentlyPlayingItem = getCurrentlyPlayingItem();
        getMasterOverlayFragment().toggleRecording(currentlyPlayingItem);
    }

    boolean isRecording() {
        org.jellyfin.sdk.model.api.BaseItemDto currentProgram = getCurrentlyPlayingItem().getCurrentProgram();
        if (currentProgram == null) {
            return false;
        } else {
            return currentProgram.getTimerId() != null;
        }
    }

    org.jellyfin.sdk.model.api.BaseItemDto getCurrentlyPlayingItem() {
        return playbackController.getCurrentlyPlayingItem();
    }

    MediaSourceInfo getCurrentMediaSource() {
        return playbackController.getCurrentMediaSource();
    }

    boolean hasChapters() {
        BaseItemDto item = getCurrentlyPlayingItem();
        List<ChapterInfo> chapters = item.getChapters();
        return chapters != null && chapters.size() > 0;
    }

    boolean skipToNextChapter() {
        if (isLiveTv() || !canSeek()) return false;

        BaseItemDto item = getCurrentlyPlayingItem();
        List<ChapterInfo> chapters = item == null ? null : item.getChapters();
        if (chapters == null || chapters.isEmpty()) return false;

        long currentMs = Math.max(0L, getCurrentPosition());
        long currentTicks = currentMs * 10000L;
        long epsilonTicks = 250L * 10000L; // 250 ms tolerance at chapter boundaries

        Long targetMs = null;
        for (ChapterInfo chapter : chapters) {
            if (chapter == null) continue;
            Long startTicks = chapter.getStartPositionTicks();
            if (startTicks == null) continue;

            if (startTicks > currentTicks + epsilonTicks) {
                targetMs = startTicks / 10000L;
                break;
            }
        }

        if (targetMs == null) return false;
        seekTo(Math.max(0L, targetMs));
        return true;
    }

    boolean skipToPreviousChapter() {
        if (isLiveTv() || !canSeek()) return false;

        BaseItemDto item = getCurrentlyPlayingItem();
        List<ChapterInfo> chapters = item == null ? null : item.getChapters();
        if (chapters == null || chapters.isEmpty()) return false;

        long currentMs = Math.max(0L, getCurrentPosition());
        long currentTicks = currentMs * 10000L;
        long epsilonTicks = 250L * 10000L; // 250 ms tolerance at chapter boundaries

        Long targetMs = null;
        for (int i = chapters.size() - 1; i >= 0; i--) {
            ChapterInfo chapter = chapters.get(i);
            if (chapter == null) continue;
            Long startTicks = chapter.getStartPositionTicks();
            if (startTicks == null) continue;

            // Strictly before current position (with tolerance), so pressing back exactly
            // on a chapter start jumps to the chapter before it.
            if (startTicks < currentTicks - epsilonTicks) {
                targetMs = startTicks / 10000L;
                break;
            }
        }

        if (targetMs == null) {
            seekTo(0L);
            return true;
        }

        seekTo(Math.max(0L, targetMs));
        return true;
    }
}
