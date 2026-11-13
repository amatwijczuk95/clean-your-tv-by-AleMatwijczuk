package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local addition (theme key "down_drawer"): Down from the dock, when no grid row sits under it, slides the dock up
 * and shows a quiet sheet at the bottom: the forecast card, a row of actions (USB, All apps, sleep timer) and, just
 * peeking under them, "All apps". Down once more expands the sheet over the whole screen into every launchable app,
 * in small sections (Watch, Music, Games, More) of rounded tiles; Up from the first row folds it back. Up or Back
 * from the top puts Home back. Nothing is built until it is opened.
 */
final class DownDrawer {
    interface Host {
        /** The dock position focused now, or -1 when focus is not on a dock tile with nothing below it. */
        int dockFocusWithNothingBelow();
        int dockCount();
        String packageAt(int position);
        String labelAt(int position);
        Drawable artworkAt(int position);
        void launchAt(int position);
        /** Hide the glass and slide the dock and shelf up by dy pixels (0 puts them back). */
        void slideHome(float dy, boolean open);
        /** Focus the dock tile at this position and put the glass back on it. */
        void focusDock(int position);
        /** Fade the dock out while the full app list covers the screen, and back in. */
        void dockVisible(boolean visible);
        /** Open the App library (every installed app). */
        void openLibrary();
        boolean showSearch();
        boolean sleepTimer();
        /** The sleep timer changed: refresh its line on Home. */
        void sleepChanged();
        boolean mediaActive();
        boolean mediaPlaying();
        void mediaPlayPause();
        void mediaNext();
        void mediaPrevious();
    }

    private static final int COLUMNS = 6;

    private final Activity activity;
    private final ViewGroup root;
    private final Host host;
    private final float dp;
    private LinearLayout panel;
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout row;
    private LinearLayout forecast;
    private LinearLayout columns;
    private TextView toggle;
    private TextView appsTitle;
    private LinearLayout apps;
    /** The app tiles, row by row across every section, for Up and Down. */
    private final List<List<View>> tileRows = new ArrayList<>();
    private boolean byDay;
    private boolean expanded;
    private ValueAnimator heightAnim;
    /** A key whose release still has to be swallowed after its press acted. */
    private int swallowUp;
    private boolean open;
    private int returnTo;

    DownDrawer(Activity activity, ViewGroup root, Host host) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    /** Durations for this panel, kept the same however fast the TV's animator scale is set (0.5x here). */
    static long ms(long base) {
        float s = ValueAnimator.getDurationScale();
        return s > 0f && s < 1f ? Math.round(base / s) : base;
    }

    private static final android.view.animation.Interpolator EASE_OUT = new android.view.animation.DecelerateInterpolator(2f);
    private static final android.view.animation.Interpolator EASE_IN = new android.view.animation.AccelerateInterpolator(1.5f);

    boolean isOpen() {
        return open;
    }

    // ---- keys ---------------------------------------------------------------------------------------------------

    /** Handles the keys that open, move inside and close the sheet; everything else goes on as usual. */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && code == swallowUp) {
            swallowUp = 0;
            return true;
        }
        if (!open) {
            if (code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            int pos = host.dockFocusWithNothingBelow();
            if (pos < 0) return false;
            if (down && e.getRepeatCount() == 0) openPanel(pos);
            return true;
        }
        View f = activity.getCurrentFocus();
        boolean inPanel = f != null && isInside(f, panel);
        boolean inForecast = inPanel && f == toggle;
        boolean inRow = inPanel && isInside(f, row);
        int[] tile = inPanel ? tileAt(f) : null;

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (!down) {
                if (expanded) collapse(true);
                else close(true);
            }
            return true;
        }
        if (!inPanel) {
            // Focus slipped out while the sheet is up: bring it back rather than let OK open a dock app.
            if (down) focusRowStart();
            return true;
        }
        boolean isUp = code == KeyEvent.KEYCODE_DPAD_UP, isDown = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean isLeft = code == KeyEvent.KEYCODE_DPAD_LEFT, isRight = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!(isUp || isDown || isLeft || isRight)) return false;
        if (!down) return true;
        if (tile != null) {
            int r = tile[0], c = tile[1];
            if (isLeft && c > 0) tileRows.get(r).get(c - 1).requestFocus();
            else if (isRight && c + 1 < tileRows.get(r).size()) tileRows.get(r).get(c + 1).requestFocus();
            else if (isDown && r + 1 < tileRows.size()) focusTile(r + 1, c);
            else if (isUp && r > 0) focusTile(r - 1, c);
            else if (isUp) collapse(true);
            return true;
        }
        if (inForecast) {
            if (isUp) {
                swallowUp = code;
                close(true);
            } else if (isDown) {
                focusRowStart();
            }
            return true;
        }
        if (inRow) {
            if (isUp) {
                swallowUp = code;
                if (forecast.getVisibility() == View.VISIBLE) toggle.requestFocus();
                else close(true);
            } else if (isDown) {
                expand();
            } else {
                int i = indexIn(row, f);
                int next = i + (isRight ? 1 : -1);
                while (next >= 0 && next < row.getChildCount() && !row.getChildAt(next).isFocusable()) {
                    next += isRight ? 1 : -1;
                }
                if (next >= 0 && next < row.getChildCount()) row.getChildAt(next).requestFocus();
            }
            return true;
        }
        return true;
    }

    private void focusRowStart() {
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    private void focusTile(int r, int c) {
        List<View> line = tileRows.get(r);
        line.get(Math.min(c, line.size() - 1)).requestFocus();
    }

    private int[] tileAt(View v) {
        for (int r = 0; r < tileRows.size(); r++) {
            List<View> line = tileRows.get(r);
            for (int c = 0; c < line.size(); c++) if (line.get(c) == v) return new int[]{r, c};
        }
        return null;
    }

    private static int indexIn(ViewGroup parent, View v) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (((View) p).getParent() == parent) return parent.indexOfChild((View) p);
        }
        return -1;
    }

    private static boolean isInside(View v, View container) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == container) return true;
        }
        return false;
    }

    // ---- open, close, expand ----------------------------------------------------------------------------------

    private void openPanel(int fromPosition) {
        returnTo = fromPosition;
        if (panel == null) build();
        fill();
        open = true;
        expanded = false;
        panel.setBackground(sheetBackground(false));
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationY(48 * dp);
        setScrollHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.post(() -> {
            if (!open) return;
            // Folded: the sheet shows the forecast and the actions, with the "All apps" title peeking under them.
            setScrollHeight(appsTitle.getBottom() + Math.round(6 * dp));
            scroll.scrollTo(0, 0);
            panel.post(() -> {
                if (!open) return;
                host.slideHome(-panel.getHeight() + 24 * dp, true);
                panel.animate().alpha(1f).translationY(0f).setInterpolator(EASE_OUT).setDuration(ms(320)).start();
                focusRowStart();
            });
        });
    }

    /** Down from the actions: the sheet rises over the whole screen into every app. */
    private void expand() {
        if (expanded || tileRows.isEmpty()) return;
        expanded = true;
        host.dockVisible(false);
        panel.setBackground(sheetBackground(true));
        int target = root.getHeight() - Math.round(140 * dp) - panel.getPaddingTop() - panel.getPaddingBottom();
        animateScrollHeight(scroll.getHeight(), Math.max(target, scroll.getHeight()));
        focusTile(0, 0);
    }

    /** Up from the first row of apps (or Back): fold the sheet back to the actions. */
    private void collapse(boolean focusRow) {
        if (!expanded) return;
        expanded = false;
        host.dockVisible(true);
        panel.setBackground(sheetBackground(false));
        if (focusRow) focusRowStart();
        scroll.smoothScrollTo(0, 0);
        animateScrollHeight(scroll.getHeight(), appsTitle.getBottom() + Math.round(6 * dp));
    }

    private void animateScrollHeight(int from, int to) {
        if (heightAnim != null) heightAnim.cancel();
        heightAnim = ValueAnimator.ofInt(from, to);
        heightAnim.setDuration(ms(360));
        heightAnim.setInterpolator(EASE_OUT);
        heightAnim.addUpdateListener(a -> setScrollHeight((Integer) a.getAnimatedValue()));
        heightAnim.start();
    }

    private void setScrollHeight(int h) {
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = h;
        scroll.setLayoutParams(lp);
    }

    /** Puts Home back; animate is false when an app is opening or Home is leaving. */
    void close(boolean animate) {
        if (!open) return;
        open = false;
        if (heightAnim != null) heightAnim.cancel();
        if (expanded) {
            expanded = false;
            host.dockVisible(true);
        }
        panel.animate().cancel();
        if (animate) {
            panel.animate().alpha(0f).translationY(32 * dp).setInterpolator(EASE_IN).setDuration(ms(200)).withEndAction(() -> {
                if (!open) panel.setVisibility(View.GONE);
            }).start();
        } else {
            panel.setAlpha(0f);
            panel.setVisibility(View.GONE);
        }
        host.slideHome(0f, false);
        host.focusDock(returnTo);
    }

    private Drawable sheetBackground(boolean full) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, full
                ? new int[]{0xCC05070C, 0xEB05070C, 0xF205070C}
                : new int[]{0x00000000, 0xB3000000, 0xE6000000});
    }

    // ---- build ----------------------------------------------------------------------------------------------------

    private void build() {
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        int side = Math.round(56 * dp);
        panel.setPadding(side, Math.round(26 * dp), side, Math.round(10 * dp));
        panel.setVisibility(View.GONE);
        panel.setClipChildren(false);

        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setSmoothScrollingEnabled(true);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, Math.round(6 * dp), 0, Math.round(24 * dp));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        forecast = new LinearLayout(activity);
        forecast.setOrientation(LinearLayout.HORIZONTAL);
        forecast.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(16 * dp);
        card.setColor(0x1AFFFFFF);
        card.setStroke(Math.round(1 * dp), 0x26FFFFFF);
        forecast.setBackground(card);
        forecast.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(18 * dp), Math.round(10 * dp));
        toggle = new TextView(activity);
        toggle.setFocusable(true);
        toggle.setFocusableInTouchMode(true);
        toggle.setTextColor(0xD9FFFFFF);
        toggle.setTextSize(13f);
        toggle.setGravity(Gravity.CENTER);
        toggle.setMinWidth(Math.round(120 * dp));
        toggle.setPadding(Math.round(14 * dp), Math.round(10 * dp), Math.round(14 * dp), Math.round(10 * dp));
        toggle.setBackground(pillBackground(false));
        toggle.setOnFocusChangeListener((v, has) -> v.setBackground(pillBackground(has)));
        toggle.setOnClickListener(v -> {
            byDay = !byDay;
            float shift = 14 * dp * (byDay ? 1 : -1);
            columns.animate().cancel();
            columns.animate().alpha(0f).translationX(-shift).setInterpolator(EASE_IN).setDuration(ms(130))
                    .withEndAction(() -> {
                        fillForecast();
                        columns.setTranslationX(shift);
                        columns.animate().alpha(1f).translationX(0f).setInterpolator(EASE_OUT).setDuration(ms(220)).start();
                    }).start();
        });
        forecast.addView(toggle);
        columns = new LinearLayout(activity);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(Math.round(18 * dp));
        forecast.addView(columns, clp);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.bottomMargin = Math.round(18 * dp);
        content.addView(forecast, flp);

        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(Math.round(6 * dp), Math.round(6 * dp), Math.round(6 * dp), Math.round(6 * dp));
        content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        appsTitle = sectionTitle(activity.getString(R.string.down_drawer_all_apps));
        android.graphics.drawable.Drawable chev = activity.getDrawable(R.drawable.ic_tv_chevron);
        if (chev != null) {
            chev = chev.mutate();
            chev.setTint(0x99FFFFFF);
            int px = Math.round(12 * dp);
            chev.setBounds(0, 0, px, px);
            appsTitle.setCompoundDrawablesRelative(null, null, chev, null);
            appsTitle.setCompoundDrawablePadding(Math.round(6 * dp));
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Math.round(20 * dp);
        content.addView(appsTitle, tlp);

        apps = new LinearLayout(activity);
        apps.setOrientation(LinearLayout.VERTICAL);
        apps.setClipChildren(false);
        apps.setClipToPadding(false);
        content.addView(apps, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(panel, lp);
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(0xA6FFFFFF);
        t.setTextSize(13f);
        t.setLetterSpacing(0.14f);
        t.setAllCaps(true);
        t.setPadding(Math.round(4 * dp), 0, 0, Math.round(12 * dp));
        return t;
    }

    private void fill() {
        fillForecast();
        row.removeAllViews();
        if (host.showSearch()) {
            row.addView(iconPill(R.drawable.ic_tv_search, activity.getString(R.string.down_drawer_search), v -> openSearch(), 128));
        }
        Intent usb = usbIntent();
        if (usb != null) {
            row.addView(iconPill(R.drawable.ic_tv_usb, activity.getString(R.string.down_drawer_usb), v -> {
                close(false);
                try {
                    activity.startActivity(usb);
                } catch (Exception ignored) {
                    // the player went away meanwhile
                }
            }, 112));
        }
        row.addView(iconPill(R.drawable.ic_tv_apps, activity.getString(R.string.down_drawer_all_apps), v -> expand(), 168));
        if (host.sleepTimer()) {
            TextView sleep = iconPill(R.drawable.ic_tv_sleep, sleepLabel(SleepTimer.minutesLeft(activity)), null, 176);
            sleep.setOnClickListener(v -> {
                int m = SleepTimer.next(activity);
                SleepTimer.set(activity, m);
                ((TextView) v).setText(sleepLabel(m));
                host.sleepChanged();
            });
            row.addView(sleep);
        }
        fillApps();
    }

    // ---- every app, in sections -------------------------------------------------------------------------------------

    private static final Set<String> WATCH = new HashSet<>(java.util.Arrays.asList(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv", "org.smarttube.stable",
            "com.directv.dtvlatam", "com.streamflixreborn.streamflix", "com.disney.disneyplus", "com.wbd.stream",
            "com.google.android.exoplayer2.mundoinfinito", "com.mediatek.wwtv.mediaplayer", "com.mediatek.wwtv.tvcenter"));
    private static final Set<String> MUSIC = new HashSet<>(java.util.Arrays.asList(
            "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.deezer.android.tv"));

    private int sectionOf(ApplicationInfo info) {
        if (WATCH.contains(info.packageName)) return 0;
        if (MUSIC.contains(info.packageName)) return 1;
        switch (info.category) {
            case ApplicationInfo.CATEGORY_VIDEO:
            case ApplicationInfo.CATEGORY_IMAGE:
                return 0;
            case ApplicationInfo.CATEGORY_AUDIO:
                return 1;
            case ApplicationInfo.CATEGORY_GAME:
                return 2;
            default:
                return 3;
        }
    }

    private void fillApps() {
        apps.removeAllViews();
        tileRows.clear();
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        Set<String> hidden = AppLibraryDialog.hiddenFromLibrary;
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || hidden.contains(pkg) || unique.containsKey(pkg)) continue;
                unique.put(pkg, ri);
            }
        }
        List<List<ResolveInfo>> sections = new ArrayList<>();
        for (int i = 0; i < 4; i++) sections.add(new ArrayList<>());
        Map<String, String> labels = new LinkedHashMap<>();
        for (ResolveInfo ri : unique.values()) {
            labels.put(ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm)));
            sections.get(sectionOf(ri.activityInfo.applicationInfo)).add(ri);
        }
        Collator collator = Collator.getInstance();
        int[] names = {R.string.apps_watch, R.string.apps_music, R.string.apps_games, R.string.apps_more};
        boolean first = true;
        for (int s = 0; s < 4; s++) {
            List<ResolveInfo> list = sections.get(s);
            if (list.isEmpty()) continue;
            list.sort((a, b) -> collator.compare(labels.get(a.activityInfo.packageName), labels.get(b.activityInfo.packageName)));
            TextView title = sectionTitle(activity.getString(names[s]));
            title.setTextColor(0x80FFFFFF);
            title.setTextSize(12f);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.topMargin = Math.round((first ? 2 : 18) * dp);
            apps.addView(title, tl);
            first = false;
            LinearLayout line = null;
            List<View> lineTiles = null;
            for (int i = 0; i < list.size(); i++) {
                if (i % COLUMNS == 0) {
                    line = new LinearLayout(activity);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setClipChildren(false);
                    line.setClipToPadding(false);
                    line.setPadding(Math.round(6 * dp), Math.round(4 * dp), 0, Math.round(10 * dp));
                    apps.addView(line);
                    lineTiles = new ArrayList<>();
                    tileRows.add(lineTiles);
                }
                ResolveInfo ri = list.get(i);
                View tile = appTile(ri, labels.get(ri.activityInfo.packageName), pm);
                line.addView(tile);
                lineTiles.add(tile);
            }
        }
    }

    /** A rounded full-bleed tile (the app's TV banner, else its icon on glass) with its name under it. */
    private View appTile(ResolveInfo ri, String label, PackageManager pm) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true);
        col.setFocusableInTouchMode(true);
        col.setClipChildren(false);
        int w = Math.round(124 * dp), h = Math.round(70 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(14 * dp));
        col.setLayoutParams(lp);

        FrameLayout frame = new FrameLayout(activity);
        final float radius = 14 * dp;
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);
        frame.setElevation(4 * dp);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
        ImageView art = new ImageView(activity);
        if (banner != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageDrawable(banner);
            art.setBackgroundColor(0xFF1C1C1E);
            frame.addView(art, new FrameLayout.LayoutParams(w, h));
        } else {
            GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0x40FFFFFF, 0x14FFFFFF});
            frame.setBackground(glass);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setImageDrawable(ri.loadIcon(pm));
            int s = Math.round(40 * dp);
            frame.addView(art, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        col.addView(frame, new LinearLayout.LayoutParams(w, h));

        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextColor(0x99FFFFFF);
        name.setTextSize(12f);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setPadding(Math.round(2 * dp), Math.round(8 * dp), Math.round(2 * dp), 0);
        col.addView(name, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.setOnFocusChangeListener((v, has) -> {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(radius);
            ring.setStroke(Math.round(2f * dp), 0xE6FFFFFF);
            frame.setForeground(has ? ring : null);
            frame.setElevation((has ? 14 : 4) * dp);
            name.setTextColor(has ? 0xFFFFFFFF : 0x99FFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationY(has ? -3 * dp : 0f)
                    .setInterpolator(EASE_OUT).setDuration(ms(180)).start();
        });
        String pkg = ri.activityInfo.packageName;
        col.setOnClickListener(v -> {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return;
            close(false);
            try {
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                LastOpened.record(activity, pkg);
            } catch (Exception ignored) {
                // gone meanwhile
            }
        });
        return col;
    }

    // ---- forecast -------------------------------------------------------------------------------------------------

    private void fillForecast() {
        List<String[]> items = byDay ? WeatherLine.daily() : WeatherLine.hourly();
        if (WeatherLine.hourly().isEmpty() && WeatherLine.daily().isEmpty()) {
            forecast.setVisibility(View.GONE);
            return;
        }
        forecast.setVisibility(View.VISIBLE);
        toggle.setText(byDay ? R.string.forecast_by_day : R.string.forecast_by_hour);
        android.graphics.drawable.Drawable chev = activity.getDrawable(R.drawable.ic_tv_chevron);
        if (chev != null) {
            chev = chev.mutate();
            chev.setTint(0xD9FFFFFF);
            int px = Math.round(13 * dp);
            chev.setBounds(0, 0, px, px);
            toggle.setCompoundDrawablesRelative(null, null, chev, null);
            toggle.setCompoundDrawablePadding(Math.round(6 * dp));
        }
        columns.removeAllViews();
        for (String[] it : items) {
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setMinimumWidth(Math.round((byDay ? 94 : 66) * dp));
            col.setPadding(Math.round(2 * dp), 0, Math.round(2 * dp), 0);
            col.addView(text(it[0], 12f, 0x99FFFFFF));
            String main = it[2] + (byDay ? "  " : "");
            TextView big = text(main, byDay ? 15f : 16f, 0xF2FFFFFF);
            WeatherLine.setIcon(big, WeatherLine.iconFor(it[1]) == 0 ? -1 : Integer.parseInt(it[1]), 0xF2FFFFFF, 16);
            if (byDay) {
                android.text.SpannableString sp = new android.text.SpannableString(main + it[3]);
                sp.setSpan(new android.text.style.ForegroundColorSpan(0x80FFFFFF), main.length(), sp.length(), 0);
                big.setText(sp);
            }
            col.addView(big);
            String r = byDay ? it[4] : it[3];
            TextView rain = text(r.isEmpty() ? " " : r, 11f, 0xCC8EC9FF);
            if (!r.isEmpty()) {
                android.graphics.drawable.Drawable drop = activity.getDrawable(R.drawable.ic_wx_drop);
                if (drop != null) {
                    drop = drop.mutate();
                    drop.setTint(0xCC8EC9FF);
                    int px = Math.round(10 * dp);
                    drop.setBounds(0, 0, px, px);
                    rain.setCompoundDrawablesRelative(drop, null, null, null);
                    rain.setCompoundDrawablePadding(Math.round(3 * dp));
                }
            }
            col.addView(rain);
            columns.addView(col);
        }
    }

    private TextView text(String t, float size, int color) {
        TextView v = new TextView(activity);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(0, Math.round(1 * dp), 0, Math.round(1 * dp));
        return v;
    }

    // ---- pills ------------------------------------------------------------------------------------------------------

    /** A pill with a drawn icon (and optional text) instead of an emoji glyph. */
    private TextView iconPill(int icon, String text, View.OnClickListener click, int widthDp) {
        TextView p = (TextView) pill(text, click, widthDp);
        setIcon(p, icon);
        if (text.isEmpty()) {
            int side = Math.round((widthDp - 22) / 2f * dp);
            p.setPadding(side, 0, side, 0);
        } else {
            p.setCompoundDrawablePadding(Math.round(8 * dp));
            p.setPadding(Math.round(16 * dp), 0, Math.round(16 * dp), 0);
        }
        return p;
    }

    private void setIcon(TextView v, int icon) {
        android.graphics.drawable.Drawable d = activity.getDrawable(icon);
        if (d != null) d.setTint(0xF2FFFFFF);
        v.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
    }

    private String sleepLabel(int minutes) {
        return minutes <= 0 ? activity.getString(R.string.sleep_off) : activity.getString(R.string.sleep_in, minutes);
    }

    private View pill(String text, View.OnClickListener click, int widthDp) {
        TextView pill = new TextView(activity);
        pill.setText(text);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(16f);
        pill.setGravity(Gravity.CENTER);
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        int w = Math.round(widthDp * dp), h = Math.round(64 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(Math.round(18 * dp));
        pill.setLayoutParams(lp);
        pill.setBackground(pillBackground(false));
        pill.setOnFocusChangeListener((v, has) -> {
            v.setBackground(pillBackground(has));
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setInterpolator(EASE_OUT).setDuration(ms(180)).start();
        });
        pill.setOnClickListener(click);
        return pill;
    }

    private Drawable pillBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(14 * dp);
        d.setColor(focused ? 0x40FFFFFF : 0x24FFFFFF);
        d.setStroke(Math.round((focused ? 2 : 1) * dp), focused ? 0xE6FFFFFF : 0x33FFFFFF);
        return d;
    }

    // ---- USB and search ---------------------------------------------------------------------------------------------

    /** A way to open a plugged-in USB drive, or null when none is mounted: the TV's media player, else Files. */
    private Intent usbIntent() {
        android.os.storage.StorageManager sm = activity.getSystemService(android.os.storage.StorageManager.class);
        boolean mounted = false;
        if (sm != null) {
            for (android.os.storage.StorageVolume v : sm.getStorageVolumes()) {
                if ((v.isRemovable() || !v.isPrimary()) && android.os.Environment.MEDIA_MOUNTED.equals(v.getState())) mounted = true;
            }
        }
        if (!mounted) {
            // Some MediaTek builds mount USB drives as public volumes that StorageManager does not list to apps.
            java.io.File[] roots = new java.io.File("/storage").listFiles();
            if (roots != null) {
                for (java.io.File f : roots) {
                    String n = f.getName();
                    if (!n.equals("emulated") && !n.equals("self") && f.isDirectory()) mounted = true;
                }
            }
        }
        if (!mounted) return null;
        PackageManager pm = activity.getPackageManager();
        for (String pkg : new String[]{"com.mediatek.wwtv.mediaplayer", "com.android.documentsui"}) {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    private void openSearch() {
        Intent[] tries = {
                new Intent("android.search.action.GLOBAL_SEARCH"),
                new Intent(Intent.ACTION_ASSIST),
                new Intent(Intent.ACTION_SEARCH_LONG_PRESS)};
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(i);
                return;
            } catch (Exception ignored) {
                // next
            }
        }
    }
}
