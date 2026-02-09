package org.jellyfin.androidtv.ui.playback.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.leanback.media.PlaybackTransportControlGlue;
import androidx.leanback.widget.AbstractDetailsDescriptionPresenter;
import androidx.leanback.widget.Action;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.PlaybackControlsRow;
import androidx.leanback.widget.PlaybackRowPresenter;
import androidx.leanback.widget.PlaybackTransportRowPresenter;
import androidx.leanback.widget.PlaybackTransportRowView;
import androidx.leanback.widget.RowPresenter;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.constant.ClockBehavior;
import org.jellyfin.androidtv.ui.playback.PlaybackController;
import org.jellyfin.androidtv.ui.playback.overlay.action.AndroidAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.ChannelBarChannelAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.ChapterAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.ClosedCaptionsAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.CustomAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.FastForwardAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.GuideAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.PlayPauseAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.PlaybackSpeedAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.PreviousLiveTvChannelAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.RecordAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.RewindAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.SelectAudioAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.SelectQualityAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.SkipNextAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.SkipPreviousAction;
import org.jellyfin.androidtv.ui.playback.overlay.action.ZoomAction;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.ChapterInfo;
import org.jellyfin.sdk.model.api.MediaSourceInfo;
import org.koin.java.KoinJavaComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class CustomPlaybackTransportControlGlue extends PlaybackTransportControlGlue<VideoPlayerAdapter> {

    // Normal playback actions
    private PlayPauseAction playPauseAction;
    private RewindAction rewindAction;
    private FastForwardAction fastForwardAction;
    private SkipPreviousAction skipPreviousAction;
    private SkipNextAction skipNextAction;
    private SelectAudioAction selectAudioAction;
    private ClosedCaptionsAction closedCaptionsAction;
    private SelectQualityAction selectQualityAction;
    private PlaybackSpeedAction playbackSpeedAction;
    private ZoomAction zoomAction;
    private ChapterAction chapterAction;

    // TV actions
    private PreviousLiveTvChannelAction previousLiveTvChannelAction;
    private ChannelBarChannelAction channelBarChannelAction;
    private GuideAction guideAction;
    private RecordAction recordAction;

    private final PlaybackController playbackController;
    private ArrayObjectAdapter primaryActionsAdapter;
    private ArrayObjectAdapter secondaryActionsAdapter;

    // Injected views
    private TextView mChapterText = null;
    private TextView mClockRangeText = null;

    private final Handler mHandler = new Handler();
    private final WeakHashMap<View, Drawable> chapterMarkerOverlays = new WeakHashMap<>();
    private final boolean showClockRange;
    private View boundRowView = null;

    CustomPlaybackTransportControlGlue(Context context, VideoPlayerAdapter playerAdapter, PlaybackController playbackController) {
        super(context, playerAdapter);
        this.playbackController = playbackController;
        ClockBehavior showClock = KoinJavaComponent.<UserPreferences>get(UserPreferences.class).get(UserPreferences.Companion.getClockBehavior());
        showClockRange = showClock == ClockBehavior.ALWAYS || showClock == ClockBehavior.IN_VIDEO;

        initActions(context);
    }

    @Override
    protected void onDetachedFromHost() {
        closedCaptionsAction.removePopup();
        playbackSpeedAction.dismissPopup();
        selectAudioAction.dismissPopup();
        selectQualityAction.dismissPopup();
        zoomAction.dismissPopup();

        super.onDetachedFromHost();
    }

    @Override
    protected PlaybackRowPresenter onCreateRowPresenter() {
        final AbstractDetailsDescriptionPresenter detailsPresenter = new AbstractDetailsDescriptionPresenter() {
            @Override
            protected void onBindDescription(ViewHolder vh, Object item) {

            }
        };
        PlaybackTransportRowPresenter rowPresenter = new PlaybackTransportRowPresenter() {
            @Override
            protected RowPresenter.ViewHolder createRowViewHolder(ViewGroup parent) {
                RowPresenter.ViewHolder vh = super.createRowViewHolder(parent);
                View rowView = vh.view;
                FrameLayout controlsDock = rowView.findViewById(androidx.leanback.R.id.controls_dock);
                View secondaryDock = rowView.findViewById(androidx.leanback.R.id.secondary_controls_dock);
                View controlBar = rowView.findViewById(androidx.leanback.R.id.control_bar);
                TextView currentTime = rowView.findViewById(androidx.leanback.R.id.current_time);

                if (secondaryDock != null) {
                    secondaryDock.setVisibility(View.GONE);
                }
                if (controlBar != null && controlBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) controlBar.getLayoutParams();
                    params.gravity = Gravity.CENTER_HORIZONTAL;
                    controlBar.setLayoutParams(params);
                    applyCompactControlSpacing(controlBar);
                }
                if (controlsDock != null) {
                    controlsDock.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                }

                if (currentTime != null && currentTime.getParent() instanceof RelativeLayout) {
                    RelativeLayout infoRow = (RelativeLayout) currentTime.getParent();
                    Context context = parent.getContext();
                    mChapterText = createTimeStyleTextView(context);
                    mChapterText.setSingleLine(true);
                    mChapterText.setEllipsize(TextUtils.TruncateAt.END);

                    RelativeLayout.LayoutParams chapterParams = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT
                    );
                    chapterParams.addRule(RelativeLayout.ALIGN_PARENT_START);
                    chapterParams.addRule(RelativeLayout.CENTER_VERTICAL);
                    chapterParams.addRule(RelativeLayout.LEFT_OF, androidx.leanback.R.id.current_time);
                    chapterParams.setMarginEnd(dp(context, 12));
                    infoRow.addView(mChapterText, chapterParams);

                    mClockRangeText = createTimeStyleTextView(context);
                    mClockRangeText.setSingleLine(true);
                    RelativeLayout.LayoutParams clockRangeParams = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT
                    );
                    clockRangeParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
                    clockRangeParams.addRule(RelativeLayout.CENTER_VERTICAL);
                    infoRow.addView(mClockRangeText, clockRangeParams);

                    if (!showClockRange) {
                        mClockRangeText.setVisibility(View.GONE);
                    }
                }

                updateOverlayMetadataText();
                return vh;
            }

            @Override
            protected void onProgressBarClicked(PlaybackTransportRowPresenter.ViewHolder vh) {
                CustomPlaybackTransportControlGlue controlglue = CustomPlaybackTransportControlGlue.this;
                controlglue.onActionClicked(controlglue.playPauseAction);
            }

            @Override
            protected void onBindRowViewHolder(RowPresenter.ViewHolder vh, Object item) {
                super.onBindRowViewHolder(vh, item);
                boundRowView = vh.view;
                vh.setOnKeyListener(CustomPlaybackTransportControlGlue.this);
                applyChapterMarkers(vh.view);
                alignPlayPauseToScreenCenter(vh.view);
            }

            @Override
            protected void onUnbindRowViewHolder(RowPresenter.ViewHolder vh) {
                clearChapterMarkers(vh.view);
                if (boundRowView == vh.view) {
                    boundRowView = null;
                }
                super.onUnbindRowViewHolder(vh);
                vh.setOnKeyListener(null);
            }
        };
        rowPresenter.setDescriptionPresenter(detailsPresenter);
        return rowPresenter;
    }

    private void initActions(Context context) {
        playPauseAction = new PlayPauseAction(context);
        rewindAction = new RewindAction(context);
        fastForwardAction = new FastForwardAction(context);
        skipPreviousAction = new SkipPreviousAction(context);
        skipNextAction = new SkipNextAction(context);
        selectAudioAction = new SelectAudioAction(context, this);
        selectAudioAction.setLabels(new String[]{context.getString(R.string.lbl_audio_track)});
        closedCaptionsAction = new ClosedCaptionsAction(context, this);
        closedCaptionsAction.setLabels(new String[]{context.getString(R.string.lbl_subtitle_track)});
        selectQualityAction = new SelectQualityAction(context, this, KoinJavaComponent.get(UserPreferences.class));
        selectQualityAction.setLabels(new String[]{context.getString(R.string.lbl_quality_profile)});
        playbackSpeedAction = new PlaybackSpeedAction(context, this, playbackController);
        playbackSpeedAction.setLabels(new String[]{context.getString(R.string.lbl_playback_speed)});
        zoomAction = new ZoomAction(context, this);
        zoomAction.setLabels(new String[]{context.getString(R.string.lbl_zoom)});
        chapterAction = new ChapterAction(context, this);
        chapterAction.setLabels(new String[]{context.getString(R.string.lbl_chapters)});

        previousLiveTvChannelAction = new PreviousLiveTvChannelAction(context, this);
        previousLiveTvChannelAction.setLabels(new String[]{context.getString(R.string.lbl_prev_item)});
        channelBarChannelAction = new ChannelBarChannelAction(context, this);
        channelBarChannelAction.setLabels(new String[]{context.getString(R.string.lbl_other_channels)});
        guideAction = new GuideAction(context, this);
        guideAction.setLabels(new String[]{context.getString(R.string.lbl_live_tv_guide)});
        recordAction = new RecordAction(context, this);
        recordAction.setLabels(new String[]{
                context.getString(R.string.lbl_record),
                context.getString(R.string.lbl_cancel_recording)
        });
    }

    @Override
    protected void onCreatePrimaryActions(ArrayObjectAdapter primaryActionsAdapter) {
        this.primaryActionsAdapter = primaryActionsAdapter;
    }

    @Override
    protected void onCreateSecondaryActions(ArrayObjectAdapter secondaryActionsAdapter) {
        this.secondaryActionsAdapter = secondaryActionsAdapter;
    }

    void addMediaActions() {
        if (primaryActionsAdapter.size() > 0)
            primaryActionsAdapter.clear();
        if (secondaryActionsAdapter.size() > 0)
            secondaryActionsAdapter.clear();

        VideoPlayerAdapter playerAdapter = getPlayerAdapter();

        // Single centered control row above seekbar.
        primaryActionsAdapter.add(selectAudioAction);

        if (!playerAdapter.isLiveTv()) {
            primaryActionsAdapter.add(skipPreviousAction);
        }

        if (playerAdapter.canSeek()) {
            primaryActionsAdapter.add(rewindAction);
        }

        primaryActionsAdapter.add(playPauseAction);

        if (playerAdapter.canSeek()) {
            primaryActionsAdapter.add(fastForwardAction);
        }

        if (!playerAdapter.isLiveTv()) {
            primaryActionsAdapter.add(skipNextAction);
        }

        primaryActionsAdapter.add(closedCaptionsAction);

        if (playerAdapter.isLiveTv()) {
            primaryActionsAdapter.add(previousLiveTvChannelAction);
            primaryActionsAdapter.add(channelBarChannelAction);
            primaryActionsAdapter.add(guideAction);
            if (playerAdapter.canRecordLiveTv()) {
                primaryActionsAdapter.add(recordAction);
                recordingStateChanged();
            }
        }

        // Keep the top row compact so subtitle+audio remain visible.
        // Extra options remain available via other player interactions/popups.
        alignPlayPauseToScreenCenter(boundRowView);
    }

    @Override
    public void onActionClicked(Action action) {
        if (action instanceof AndroidAction) {
            ((AndroidAction) action).onActionClicked(getPlayerAdapter());
        }
        notifyActionChanged(action);
    }

    public void onCustomActionClicked(Action action, View view) {
        // Handle custom action clicks which require a popup menu
        if (action instanceof CustomAction) {
            ((CustomAction) action).handleClickAction(playbackController, getPlayerAdapter(), getContext(), view);
        }

        if (action == playbackSpeedAction) {
            // Post a callback to calculate the new time, since Exoplayer updates this in an async fashion.
            // This is a hack, we should instead have onPlaybackParametersChanged call out to this
            // class to notify rather than poll. But communication is unidirectional at the moment:
            mHandler.postDelayed(this::updateOverlayMetadataText, 5000);  // 5 seconds
        }
    }

    private void notifyActionChanged(Action action) {
        ArrayObjectAdapter adapter = primaryActionsAdapter;
        if (adapter.indexOf(action) >= 0) {
            adapter.notifyArrayItemRangeChanged(adapter.indexOf(action), 1);
            alignPlayPauseToScreenCenter(boundRowView);
            return;
        }
        adapter = secondaryActionsAdapter;
        if (adapter.indexOf(action) >= 0) {
            adapter.notifyArrayItemRangeChanged(adapter.indexOf(action), 1);
        }
    }

    void setInitialPlaybackDrawable() {
        playPauseAction.setIndex(PlaybackControlsRow.PlayPauseAction.INDEX_PAUSE);
        notifyActionChanged(playPauseAction);
    }

    void invalidatePlaybackControls() {
        if (primaryActionsAdapter.size() > 0)
            primaryActionsAdapter.clear();
        if (secondaryActionsAdapter.size() > 0)
            secondaryActionsAdapter.clear();
        addMediaActions();
    }

    void recordingStateChanged() {
        if (getPlayerAdapter().isRecording()) {
            recordAction.setIndex(RecordAction.INDEX_RECORDING);
        } else {
            recordAction.setIndex(RecordAction.INDEX_INACTIVE);
        }
        notifyActionChanged(recordAction);
    }

    void updatePlayState() {
        playPauseAction.setIndex(isPlaying() ? PlaybackControlsRow.PlayPauseAction.INDEX_PAUSE : PlaybackControlsRow.PlayPauseAction.INDEX_PLAY);
        notifyActionChanged(playPauseAction);
        updateOverlayMetadataText();
    }

    public void setInjectedViewsVisibility() {
        updateOverlayMetadataText();
    }

    private void applyChapterMarkers(View rootView) {
        View progressBar = rootView.findViewById(androidx.leanback.R.id.playback_progress);
        if (progressBar == null) return;

        List<Float> markerPositions = getChapterMarkerPositions();
        progressBar.post(() -> {
            clearChapterMarkersFromProgressBar(progressBar);
            if (markerPositions.isEmpty()) return;

            int width = progressBar.getWidth();
            int height = progressBar.getHeight();
            if (width <= 0 || height <= 0) return;

            float markerHalfWidth = progressBar.getResources().getDisplayMetrics().density * 1.2f;
            float markerHalfHeight = progressBar.getResources().getDisplayMetrics().density * 3f;
            ChapterMarkersDrawable drawable = new ChapterMarkersDrawable(markerPositions, 0xFFFF3B30, markerHalfWidth, markerHalfHeight);
            drawable.setBounds(0, 0, width, height);
            progressBar.getOverlay().add(drawable);
            chapterMarkerOverlays.put(progressBar, drawable);
        });
    }

    private void clearChapterMarkers(View rootView) {
        View progressBar = rootView.findViewById(androidx.leanback.R.id.playback_progress);
        if (progressBar != null) clearChapterMarkersFromProgressBar(progressBar);
    }

    private void clearChapterMarkersFromProgressBar(View progressBar) {
        Drawable existing = chapterMarkerOverlays.remove(progressBar);
        if (existing != null) progressBar.getOverlay().remove(existing);
    }

    private List<Float> getChapterMarkerPositions() {
        BaseItemDto item = getPlayerAdapter().getCurrentlyPlayingItem();
        List<ChapterInfo> chapters = item == null ? null : item.getChapters();
        if (chapters == null || chapters.isEmpty()) return Collections.emptyList();

        Long durationTicks = item.getRunTimeTicks();
        if (durationTicks == null || durationTicks <= 0) {
            MediaSourceInfo mediaSource = getPlayerAdapter().getCurrentMediaSource();
            if (mediaSource != null) durationTicks = mediaSource.getRunTimeTicks();
        }
        if (durationTicks == null || durationTicks <= 0) return Collections.emptyList();

        List<Float> positions = new ArrayList<>();
        for (ChapterInfo chapter : chapters) {
            if (chapter == null) continue;
            Long startTicks = chapter.getStartPositionTicks();
            if (startTicks == null || startTicks <= 0 || startTicks >= durationTicks) continue;
            positions.add((float) startTicks / (float) durationTicks);
        }
        Collections.sort(positions);
        return positions;
    }

    private static final class ChapterMarkersDrawable extends Drawable {
        private final float[] markerPositions;
        private final Paint paint;
        private final float markerHalfWidth;
        private final float markerHalfHeight;

        ChapterMarkersDrawable(List<Float> markerPositions, int color, float markerHalfWidth, float markerHalfHeight) {
            this.markerPositions = new float[markerPositions.size()];
            for (int i = 0; i < markerPositions.size(); i++) {
                this.markerPositions[i] = markerPositions.get(i);
            }
            this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.paint.setColor(color);
            this.markerHalfWidth = Math.max(1f, markerHalfWidth);
            this.markerHalfHeight = Math.max(2f, markerHalfHeight);
        }

        @Override
        public void draw(Canvas canvas) {
            float centerY = getBounds().centerY();
            for (float marker : markerPositions) {
                if (marker <= 0f || marker >= 1f) continue;
                float x = getBounds().left + (marker * getBounds().width());
                canvas.drawRect(
                        x - markerHalfWidth,
                        centerY - markerHalfHeight,
                        x + markerHalfWidth,
                        centerY + markerHalfHeight,
                        paint
                );
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                && handleCircularControlNavigation(v, keyCode)) {
            return true;
        }

        if (event.getAction() != KeyEvent.ACTION_UP) {
            // The below actions are only handled on key up
            return super.onKey(v, keyCode, event);
        }

        VideoPlayerAdapter playerAdapter = getPlayerAdapter();

        if (keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            closedCaptionsAction.handleClickAction(playbackController, getPlayerAdapter(), getContext(), v);
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK) {
            selectAudioAction.handleClickAction(playbackController, getPlayerAdapter(), getContext(), v);
        }
        return super.onKey(v, keyCode, event);
    }

    private boolean handleCircularControlNavigation(View rootView, int keyCode) {
        if (rootView == null) return false;

        View controlBar = rootView.findViewById(androidx.leanback.R.id.control_bar);
        if (!(controlBar instanceof ViewGroup)) return false;
        ViewGroup actionBar = (ViewGroup) controlBar;
        if (actionBar.getChildCount() < 2) return false;

        View focused = rootView.findFocus();
        if (focused == null) return false;

        View focusedAction = focused;
        while (focusedAction.getParent() instanceof View && focusedAction.getParent() != actionBar) {
            focusedAction = (View) focusedAction.getParent();
        }
        if (focusedAction.getParent() != actionBar) return false;

        int focusedIndex = actionBar.indexOfChild(focusedAction);
        if (focusedIndex < 0) return false;

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focusedIndex == 0) {
            View wrapTarget = actionBar.getChildAt(actionBar.getChildCount() - 1);
            return wrapTarget != null && wrapTarget.requestFocus();
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focusedIndex == actionBar.getChildCount() - 1) {
            View wrapTarget = actionBar.getChildAt(0);
            return wrapTarget != null && wrapTarget.requestFocus();
        }

        return false;
    }

    @Override
    protected void onUpdateProgress() {
        super.onUpdateProgress();
        updateOverlayMetadataText();
    }

    @Override
    protected void onUpdateDuration() {
        super.onUpdateDuration();
        updateOverlayMetadataText();
    }

    private TextView createTimeStyleTextView(Context context) {
        TextView textView = new TextView(context);
        textView.setTextAppearance(context, androidx.leanback.R.style.Widget_Leanback_PlaybackControlsTimeStyle);
        return textView;
    }

    private void updateOverlayMetadataText() {
        updateChapterText();
        updateClockRangeText();
    }

    private void updateClockRangeText() {
        if (mClockRangeText == null || !showClockRange || getPlayerAdapter().getDuration() < 1) return;

        long msLeft = Math.max(0, getPlayerAdapter().getDuration() - getPlayerAdapter().getCurrentPosition());
        long realTimeLeft = (long) Math.ceil(msLeft / Math.max(0.01d, playbackController.getPlaybackSpeed()));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime endTime = now.plus(realTimeLeft, ChronoUnit.MILLIS);
        String nowText = DateTimeExtensionsKt.getTimeFormatter(getContext()).format(now);
        String endText = DateTimeExtensionsKt.getTimeFormatter(getContext()).format(endTime);
        mClockRangeText.setText(getContext().getString(R.string.lbl_playback_control_clock_range, nowText, endText));
    }

    private void updateChapterText() {
        if (mChapterText == null) return;
        BaseItemDto item = getPlayerAdapter().getCurrentlyPlayingItem();
        List<ChapterInfo> chapters = item == null ? null : item.getChapters();
        if (chapters == null || chapters.isEmpty()) {
            mChapterText.setText("");
            return;
        }

        int chapterCount = chapters.size();
        long currentPositionTicks = Math.max(0L, getPlayerAdapter().getCurrentPosition()) * 10000L;
        int activeChapterIndex = 0;
        for (int i = 0; i < chapters.size(); i++) {
            ChapterInfo chapter = chapters.get(i);
            Long chapterStartTicks = chapter == null ? null : chapter.getStartPositionTicks();
            if (chapterStartTicks != null && chapterStartTicks <= currentPositionTicks) {
                activeChapterIndex = i;
            } else {
                break;
            }
        }

        ChapterInfo activeChapter = chapters.get(activeChapterIndex);
        String chapterName = activeChapter == null ? "" : activeChapter.getName();
        String formatted = getContext().getString(
                R.string.lbl_playback_control_chapter,
                activeChapterIndex + 1,
                chapterCount
        );

        if (!TextUtils.isEmpty(chapterName)) {
            formatted = formatted + " \u2022 " + chapterName;
        }
        mChapterText.setText(formatted.toUpperCase(Locale.getDefault()));
    }

    private int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private void alignPlayPauseToScreenCenter(View rootView) {
        if (rootView == null || primaryActionsAdapter == null) return;

        View controlsDock = rootView.findViewById(androidx.leanback.R.id.controls_dock);
        View controlBar = rootView.findViewById(androidx.leanback.R.id.control_bar);
        if (!(controlBar instanceof ViewGroup) || controlsDock == null) return;
        applyCompactControlSpacing(controlBar);

        int playPauseIndex = primaryActionsAdapter.indexOf(playPauseAction);
        if (playPauseIndex < 0) return;

        controlBar.post(() -> {
            ViewGroup actionBar = (ViewGroup) controlBar;
            if (playPauseIndex >= actionBar.getChildCount() || actionBar.getChildCount() == 0) return;

            View playPauseView = actionBar.getChildAt(playPauseIndex);
            int[] dockPos = new int[2];
            int[] playPausePos = new int[2];
            controlsDock.getLocationOnScreen(dockPos);
            playPauseView.getLocationOnScreen(playPausePos);

            float currentCenterX = (playPausePos[0] - dockPos[0]) + (playPauseView.getWidth() / 2f);
            float targetCenterX = controlsDock.getWidth() / 2f;
            float delta = targetCenterX - currentCenterX;
            float desiredTranslationX = controlBar.getTranslationX() + delta;

            // Keep all controls visible while still trying to center play/pause.
            View firstButton = actionBar.getChildAt(0);
            View lastButton = actionBar.getChildAt(actionBar.getChildCount() - 1);
            float minTranslationX = -firstButton.getX();
            float maxTranslationX = controlsDock.getWidth() - (lastButton.getX() + lastButton.getWidth());
            float clampedTranslationX = Math.max(minTranslationX, Math.min(maxTranslationX, desiredTranslationX));

            controlBar.setTranslationX(clampedTranslationX);
        });
    }

    private void applyCompactControlSpacing(View controlBar) {
        // Leanback defaults can be too wide for many playback actions on some TVs.
        // Use reflection because ControlBar is package-private in androidx.leanback.widget.
        try {
            java.lang.reflect.Method method =
                    controlBar.getClass().getDeclaredMethod("setChildMarginFromCenter", int.class);
            method.setAccessible(true);
            method.invoke(controlBar, dp(controlBar.getContext(), 76));
            controlBar.requestLayout();
        } catch (Exception ignored) {
            // Keep default behavior if Leanback internals change.
        }
    }

}
