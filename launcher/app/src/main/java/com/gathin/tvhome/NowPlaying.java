package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Local addition (theme key "now_playing"): a quiet card under the date with what a media app (Spotify and the
 * like) is playing, and its controls for the Down panel. Reads the active media sessions through MediaListener's
 * notification access; without that access it simply never shows. Listens only while Home is in front.
 */
final class NowPlaying {
    interface Listener {
        void changed();
    }

    private final Context ctx;
    private final MediaSessionManager msm;
    private final ComponentName listener;
    private final float dp;
    private final LinearLayout card;
    private final ImageView art;
    private final TextView source, title, artist;
    private Listener onChange;
    private MediaController controller;
    private boolean resumed;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { refresh(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { refresh(); }
        @Override public void onSessionDestroyed() { pick(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = list -> pick();

    NowPlaying(Context ctx, ViewGroup root) {
        this.ctx = ctx;
        this.msm = ctx.getSystemService(MediaSessionManager.class);
        this.listener = new ComponentName(ctx, MediaListener.class);
        this.dp = ctx.getResources().getDisplayMetrics().density;

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * dp);
        bg.setColor(0x26000000);
        bg.setStroke(Math.round(1 * dp), 0x1FFFFFFF);
        card.setBackground(bg);
        int pad = Math.round(10 * dp);
        card.setPadding(pad, pad, Math.round(16 * dp), pad);
        card.setAlpha(0f);
        card.setVisibility(View.GONE);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackgroundColor(0x33FFFFFF);
        final float r = 8 * dp;
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        art.setClipToOutline(true);
        int a = Math.round(48 * dp);
        card.addView(art, new LinearLayout.LayoutParams(a, a));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Math.round(12 * dp), 0, 0, 0);
        source = label(11f, 0x99FFFFFF);
        source.setLetterSpacing(0.12f);
        source.setAllCaps(true);
        title = label(15f, 0xF2FFFFFF);
        artist = label(13f, 0xA6FFFFFF);
        text.addView(source);
        text.addView(title);
        text.addView(artist);
        card.addView(text, new LinearLayout.LayoutParams(Math.round(260 * dp), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round(56 * dp);
        lp.topMargin = Math.round(122 * dp);
        root.addView(card, lp);
    }

    private TextView label(float size, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    void setListener(Listener l) {
        onChange = l;
    }

    void resume() {
        resumed = true;
        try {
            msm.addOnActiveSessionsChangedListener(sessions, listener);
        } catch (SecurityException e) {
            return;   // no notification access granted: nothing to show
        }
        pick();
    }

    void pause() {
        resumed = false;
        try {
            msm.removeOnActiveSessionsChangedListener(sessions);
        } catch (Exception ignored) {
            // not registered
        }
        if (controller != null) controller.unregisterCallback(callback);
    }

    /** Takes the session that is playing, else the most recent one that is paused. */
    private void pick() {
        if (!resumed) return;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(listener);
        } catch (SecurityException e) {
            list = null;
        }
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s == null || c.getMetadata() == null) continue;
                if (s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null && s.getState() == PlaybackState.STATE_PAUSED) best = c;
            }
        }
        if (controller != null && (best == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            controller.unregisterCallback(callback);
        }
        if (best != null && (controller == null || !controller.getSessionToken().equals(best.getSessionToken()))) {
            best.registerCallback(callback);
        }
        controller = best;
        refresh();
    }

    private void refresh() {
        MediaController c = controller;
        PlaybackState s = c == null ? null : c.getPlaybackState();
        MediaMetadata m = c == null ? null : c.getMetadata();
        boolean show = s != null && m != null
                && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_PAUSED);
        if (show) {
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String ar = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (ar == null) ar = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            title.setText(t == null ? "" : t);
            artist.setText(ar == null ? "" : ar);
            String app = appName(c.getPackageName());
            source.setText(ctx.getString(isPlaying() ? R.string.now_playing_on : R.string.now_paused_on, app));
            Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null) {
                int size = Math.round(48 * dp);
                art.setImageBitmap(b.getWidth() > size * 2 ? Bitmap.createScaledBitmap(b, size, size, true) : b);
            } else {
                art.setImageDrawable(null);
            }
            if (card.getVisibility() != View.VISIBLE) {
                card.setVisibility(View.VISIBLE);
                card.setTranslationY(8 * dp);
                card.animate().alpha(1f).translationY(0f).setDuration(DownDrawer.ms(260)).start();
            }
        } else if (card.getVisibility() == View.VISIBLE) {
            card.animate().alpha(0f).setDuration(DownDrawer.ms(200)).withEndAction(() -> card.setVisibility(View.GONE)).start();
        }
        if (onChange != null) onChange.changed();
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    boolean active() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_PAUSED);
    }

    boolean isPlaying() {
        PlaybackState s = controller == null ? null : controller.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    void playPause() {
        if (controller == null) return;
        if (isPlaying()) controller.getTransportControls().pause();
        else controller.getTransportControls().play();
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
    }

    void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
    }

    /** Hide or show the card with the Down panel, which covers that part of Home. */
    void setHidden(boolean hidden) {
        card.animate().cancel();
        if (card.getVisibility() == View.VISIBLE) card.animate().alpha(hidden ? 0f : 1f).setDuration(DownDrawer.ms(200)).start();
    }
}
