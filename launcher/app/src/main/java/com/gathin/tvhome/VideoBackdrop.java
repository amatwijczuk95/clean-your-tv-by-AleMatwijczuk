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
 * Each clip loops until video_rotation_minutes have passed, then the next one (shuffled) starts. Only one
 * player exists at a time, and only while Home is in front: stop() releases it so no decoder is held while an
 * app runs. Between clips the last frame stays on screen until the next one is ready, so there is no flash.
 */
final class VideoBackdrop implements TextureView.SurfaceTextureListener {
    private final Context ctx;
    private final TextureView view;
    private final List<Integer> clips = new ArrayList<>();
    private final long rotationMs;
    private int index;
    private long clipStarted;
    private MediaPlayer player;
    private Surface surface;
    private boolean wanted;
    private final View shade;
    private final int dayDim, eveningDim, nightDim;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable dimTick = new Runnable() {
        @Override public void run() {
            updateDim(true);
            handler.postDelayed(this, 60_000L);
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
        view = new TextureView(ctx);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setSurfaceTextureListener(this);
        root.addView(view, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        this.dayDim = dimPercent;
        this.eveningDim = eveningDim;
        this.nightDim = nightDim;
        shade = new View(ctx);
        shade.setBackgroundColor(android.graphics.Color.BLACK);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(shade, 1, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
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
        if (player == null && view.isAvailable()) open(view.getSurfaceTexture());
    }

    void stop() {
        wanted = false;
        handler.removeCallbacks(dimTick);
        if (player != null) {
            player.release();
            player = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void open(SurfaceTexture texture) {
        if (clips.isEmpty()) return;
        try {
            surface = new Surface(texture);
            player = new MediaPlayer();
            player.setSurface(surface);
            player.setOnPreparedListener(mp -> {
                mp.setVolume(0f, 0f);
                mp.start();
            });
            player.setOnCompletionListener(mp -> {
                if (SystemClock.elapsedRealtime() - clipStarted >= rotationMs && clips.size() > 1) {
                    index = (index + 1) % clips.size();
                    load();
                } else {
                    mp.seekTo(0);
                    mp.start();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> { stop(); return true; });
            if (clipStarted == 0 || SystemClock.elapsedRealtime() - clipStarted >= rotationMs) {
                if (clipStarted != 0) index = (index + 1) % clips.size();
                clipStarted = 0;
            }
            load();
        } catch (Exception e) {
            stop();
        }
    }

    /** Points the player at the current clip; keeps clipStarted when resuming the same clip. */
    private void load() {
        try {
            player.reset();
            player.setSurface(surface);
            player.setDataSource(ctx, Uri.parse("android.resource://" + ctx.getPackageName() + "/" + clips.get(index)));
            if (clipStarted == 0 || SystemClock.elapsedRealtime() - clipStarted >= rotationMs) {
                clipStarted = SystemClock.elapsedRealtime();
            }
            player.prepareAsync();
        } catch (Exception e) {
            stop();
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        if (wanted && player == null) open(texture);
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        boolean keep = wanted;
        stop();
        wanted = keep;
        return true;
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) { }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
}
