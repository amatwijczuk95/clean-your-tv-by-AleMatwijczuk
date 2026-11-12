package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local experiment (theme key "video_wallpaper"): muted videos behind Home, from res/raw/bg_video_1, _2, ...
 * Two stacked layers make the loop seamless: about 2.5 s before a clip ends the other layer prepares the same clip
 * from its start (or the next clip, shuffled, once video_rotation_minutes have passed) and cross-fades over it, so
 * there is never a hard restart. At most two players exist, and the old one is released as soon as the fade ends;
 * stop() releases both, so no decoder is held while an app runs. If the second player cannot start (a decoder
 * busy elsewhere), the clip simply restarts on the same layer, as before.
 */
final class VideoBackdrop {
    private static final long PREPARE_BEFORE_END_MS = 2500;
    private static final long FADE_MS = 1500;

    /** One video layer: a TextureView and, while it plays, its player. */
    private final class Slot implements TextureView.SurfaceTextureListener {
        final TextureView view;
        MediaPlayer player;
        Surface surface;
        boolean prepared;

        Slot() {
            view = new TextureView(ctx);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.setSurfaceTextureListener(this);
        }

        boolean available() {
            return view.isAvailable();
        }

        /** Starts clip res on this layer; onReady runs once it plays (onFail when it cannot). */
        void play(int res, Runnable onReady, Runnable onFail) {
            release();
            if (!view.isAvailable()) {
                if (onFail != null) onFail.run();
                return;
            }
            try {
                surface = new Surface(view.getSurfaceTexture());
                player = new MediaPlayer();
                player.setSurface(surface);
                player.setVolume(0f, 0f);
                player.setOnPreparedListener(mp -> {
                    if (mp != player) return;
                    prepared = true;
                    mp.setVolume(0f, 0f);
                    mp.start();
                    if (onReady != null) onReady.run();
                });
                player.setOnErrorListener((mp, what, extra) -> {
                    if (mp == player) {
                        release();
                        if (onFail != null) onFail.run();
                    }
                    return true;
                });
                player.setOnCompletionListener(mp -> {
                    // Reached the end without a cross-fade (the other layer failed): restart here, as before.
                    if (mp == player && this == front && !crossing) {
                        mp.seekTo(0);
                        mp.start();
                    }
                });
                player.setDataSource(ctx, Uri.parse("android.resource://" + ctx.getPackageName() + "/" + res));
                player.prepareAsync();
            } catch (Exception e) {
                release();
                if (onFail != null) onFail.run();
            }
        }

        void release() {
            prepared = false;
            if (player != null) {
                try {
                    player.release();
                } catch (Exception ignored) {
                    // already gone
                }
                player = null;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }

        @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
            if (wanted && this == front && player == null) startFront();
        }

        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            release();
            return true;
        }

        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) { }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
    }

    private final Context ctx;
    private final List<Integer> clips = new ArrayList<>();
    private final long rotationMs;
    private int index;
    private long clipStarted;
    private boolean wanted, crossing;
    private final Slot bottom, top;
    private Slot front;
    private final View shade;
    private final int dayDim, eveningDim, nightDim;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable dimTick = new Runnable() {
        @Override public void run() {
            updateDim(true);
            handler.postDelayed(this, 60_000L);
        }
    };
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (!wanted) return;
            checkLoop();
            handler.postDelayed(this, 250);
        }
    };

    VideoBackdrop(Context ctx, ViewGroup root, int rotationMinutes, int dimPercent, int eveningDim, int nightDim) {
        this.ctx = ctx;
        this.rotationMs = Math.max(1, rotationMinutes) * 60_000L;
        for (int i = 1; i <= 50; i++) {
            int id = ctx.getResources().getIdentifier("bg_video_" + i, "raw", ctx.getPackageName());
            if (id == 0) break;
            clips.add(id);
        }
        Collections.shuffle(clips);
        bottom = new Slot();
        top = new Slot();
        ViewGroup.LayoutParams full = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(bottom.view, 0, full);
        root.addView(top.view, 1, new ViewGroup.LayoutParams(full));
        top.view.setAlpha(0f);
        front = bottom;
        this.dayDim = dimPercent;
        this.eveningDim = eveningDim;
        this.nightDim = nightDim;
        shade = new View(ctx);
        shade.setBackgroundColor(android.graphics.Color.BLACK);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(shade, 2, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        updateDim(false);
    }

    /** Darkness over the videos at a minute of the day: day, easing to evening 20-21 h, to night 22-23 h, back 6-7 h. */
    static float dimAt(int minute, int day, int evening, int night) {
        int h = minute / 60;
        float f = (minute % 60) / 60f;
        if (h >= 23 || h < 6) return night;
        if (h == 6) return night + (day - night) * f;
        if (h < 20) return day;
        if (h == 20) return day + (evening - day) * f;
        if (h == 21) return evening;
        return evening + (night - evening) * f;   // 22 h
    }

    private void updateDim(boolean animate) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        float target = dimAt(c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE),
                dayDim, eveningDim, nightDim) / 100f;
        if (animate) shade.animate().alpha(target).setDuration(3000).start();
        else shade.setAlpha(target);
    }

    void start() {
        wanted = true;
        updateDim(false);
        handler.removeCallbacks(dimTick);
        handler.postDelayed(dimTick, 60_000L);
        if (front.player == null && front.available()) startFront();
        handler.removeCallbacks(watch);
        handler.postDelayed(watch, 250);
    }

    void stop() {
        wanted = false;
        crossing = false;
        handler.removeCallbacks(dimTick);
        handler.removeCallbacks(watch);
        top.view.animate().cancel();
        bottom.view.animate().cancel();
        bottom.release();
        top.release();
    }

    /** The clip to play next: the same one, or the next once its rotation time is up. */
    private int nextClip() {
        long now = SystemClock.elapsedRealtime();
        if (clipStarted == 0) {
            clipStarted = now;
        } else if (now - clipStarted >= rotationMs && clips.size() > 1) {
            index = (index + 1) % clips.size();
            clipStarted = now;
        }
        return clips.get(index);
    }

    private void startFront() {
        if (clips.isEmpty()) return;
        Slot other = front == top ? bottom : top;
        other.release();
        front.view.setAlpha(1f);
        other.view.setAlpha(front == bottom ? 0f : 1f);
        front.play(nextClip(), null, null);
    }

    /** Near the end of the clip on screen, start the other layer and fade between them. */
    private void checkLoop() {
        MediaPlayer p = front.player;
        if (crossing || p == null || !front.prepared) return;
        int dur, pos;
        try {
            dur = p.getDuration();
            pos = p.getCurrentPosition();
        } catch (Exception e) {
            return;
        }
        if (dur <= 0 || dur - pos > PREPARE_BEFORE_END_MS || dur < 2 * PREPARE_BEFORE_END_MS) return;
        Slot back = front == top ? bottom : top;
        if (!back.available()) return;
        crossing = true;
        back.play(nextClip(), () -> crossFade(back), () -> crossing = false);
    }

    private void crossFade(Slot incoming) {
        if (!wanted) return;
        Slot outgoing = front;
        long fade = DownDrawer.ms(FADE_MS);
        Runnable done = () -> {
            outgoing.release();
            front = incoming;
            crossing = false;
        };
        if (incoming == top) {
            // The new clip is on the upper layer: fade it in over the old one.
            top.view.animate().cancel();
            top.view.setAlpha(0f);
            top.view.animate().alpha(1f).setDuration(fade).withEndAction(done).start();
        } else {
            // The new clip is underneath: show it and fade the old upper layer away.
            bottom.view.setAlpha(1f);
            top.view.animate().cancel();
            top.view.animate().alpha(0f).setDuration(fade).withEndAction(done).start();
        }
    }
}
