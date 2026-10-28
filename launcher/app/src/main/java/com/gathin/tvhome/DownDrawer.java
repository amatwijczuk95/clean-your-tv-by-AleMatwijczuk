package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Local addition (theme key "down_drawer"): Down from the dock, when no grid row sits under it, slides the dock up
 * and shows a quiet panel at the bottom: a Search pill (the TV's own universal search) and the dock apps in the
 * order they were last opened. Up or Back puts everything back. Nothing is built until it is opened.
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
        /** Open the App library (every installed app). */
        void openLibrary();
    }

    private final Activity activity;
    private final ViewGroup root;
    private final Host host;
    private final float dp;
    private LinearLayout panel;
    private LinearLayout row;
    private LinearLayout forecast;
    private LinearLayout columns;
    private TextView toggle;
    private boolean byDay;
    /** A key whose release still has to be swallowed after its press acted. */
    private int swallowUp;
    /** Apps opened within this long show under Recent. */
    private static final long RECENT_MS = 14L * 24 * 60 * 60 * 1000;
    private boolean open;
    private int returnTo;

    DownDrawer(Activity activity, ViewGroup root, Host host) {
        this.activity = activity;
        this.root = root;
        this.host = host;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() {
        return open;
    }

    /** Handles the keys that open and close the panel; everything else goes on as usual. */
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
        boolean inPanel = f != null && isInside(f);
        boolean inForecast = inPanel && toggle != null && f == toggle;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (!down) close(true);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && inPanel) {
            if (down) {
                swallowUp = code;
                if (!inForecast && forecast.getVisibility() == View.VISIBLE) toggle.requestFocus();
                else close(true);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && inForecast && row.getChildCount() > 0) row.getChildAt(0).requestFocus();
            return true;
        }
        if (inForecast && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) return true;
        if (inPanel && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // Move along the row here: Home's own grid navigation would otherwise pull focus back to the dock.
            if (down) {
                int i = indexInRow(f);
                int next = i + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                while (next >= 0 && next < row.getChildCount() && !row.getChildAt(next).isFocusable()) {
                    next += code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                }
                if (next >= 0 && next < row.getChildCount()) row.getChildAt(next).requestFocus();
            }
            return true;
        }
        if (!inPanel) {
            // Focus slipped out while the panel is up: bring it back rather than let OK open a dock app.
            if (down && row.getChildCount() > 0) row.getChildAt(0).requestFocus();
            return true;
        }
        return false;
    }

    private int indexInRow(View v) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (((View) p).getParent() == row) return row.indexOfChild((View) p);
        }
        return -1;
    }

    private boolean isInside(View v) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            if (p == panel) return true;
        }
        return false;
    }

    private void openPanel(int fromPosition) {
        returnTo = fromPosition;
        if (panel == null) build();
        fill();
        open = true;
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.post(() -> {
            if (!open) return;
            host.slideHome(-panel.getHeight() + 24 * dp, true);
            panel.animate().alpha(1f).setDuration(180).start();
            View first = row.getChildCount() > 0 ? row.getChildAt(0) : null;
            if (first != null) first.requestFocus();
        });
    }

    /** Puts Home back; animate is false when an app is opening or Home is leaving. */
    void close(boolean animate) {
        if (!open) return;
        open = false;
        panel.animate().cancel();
        if (animate) {
            panel.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                if (!open) panel.setVisibility(View.GONE);
            }).start();
        } else {
            panel.setAlpha(0f);
            panel.setVisibility(View.GONE);
        }
        host.slideHome(0f, false);
        host.focusDock(returnTo);
    }

    private void build() {
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xB3000000, 0xE6000000});
        panel.setBackground(shade);
        int side = Math.round(56 * dp);
        panel.setPadding(side, Math.round(26 * dp), side, Math.round(18 * dp));
        panel.setVisibility(View.GONE);

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
            fillForecast();
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
        flp.bottomMargin = Math.round(16 * dp);
        panel.addView(forecast, flp);

        TextView title = new TextView(activity);
        title.setText(R.string.down_drawer_title);
        title.setTextColor(0xA6FFFFFF);
        title.setTextSize(13f);
        title.setLetterSpacing(0.14f);
        title.setAllCaps(true);
        title.setPadding(Math.round(4 * dp), 0, 0, Math.round(12 * dp));
        panel.addView(title);

        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(Math.round(6 * dp), Math.round(6 * dp), Math.round(6 * dp), Math.round(6 * dp));
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.setClipChildren(false);
        panel.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(panel, lp);
    }

    private void fill() {
        fillForecast();
        row.removeAllViews();
        row.addView(pill(activity.getString(R.string.down_drawer_search), v -> openSearch()));
        Intent usb = usbIntent();
        if (usb != null) {
            row.addView(pill(activity.getString(R.string.down_drawer_usb), v -> {
                close(false);
                try {
                    activity.startActivity(usb);
                } catch (Exception ignored) {
                    // the player went away meanwhile
                }
            }));
        }
        row.addView(pill(activity.getString(R.string.down_drawer_all_apps), v -> {
            close(false);
            host.openLibrary();
        }));
        long now = System.currentTimeMillis();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < host.dockCount(); i++) {
            long t = LastOpened.get(activity, host.packageAt(i));
            if (t > 0 && now - t < RECENT_MS) order.add(i);
        }
        order.sort((a, b) -> Long.compare(LastOpened.get(activity, host.packageAt(b)),
                LastOpened.get(activity, host.packageAt(a))));
        for (int pos : order) row.addView(card(pos));
        if (order.isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText(R.string.down_drawer_empty);
            hint.setTextColor(0x80FFFFFF);
            hint.setTextSize(13f);
            hint.setGravity(Gravity.CENTER_VERTICAL);
            hint.setMinHeight(Math.round(72 * dp));
            hint.setPadding(Math.round(8 * dp), 0, 0, 0);
            row.addView(hint);
        }
    }

    private void fillForecast() {
        List<String[]> items = byDay ? WeatherLine.daily() : WeatherLine.hourly();
        if (WeatherLine.hourly().isEmpty() && WeatherLine.daily().isEmpty()) {
            forecast.setVisibility(View.GONE);
            return;
        }
        forecast.setVisibility(View.VISIBLE);
        toggle.setText(byDay ? R.string.forecast_by_day : R.string.forecast_by_hour);
        columns.removeAllViews();
        for (String[] it : items) {
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setMinimumWidth(Math.round((byDay ? 94 : 66) * dp));
            col.setPadding(Math.round(2 * dp), 0, Math.round(2 * dp), 0);
            col.addView(text(it[0], 12f, 0x99FFFFFF));
            String main = (it[1].isEmpty() ? "" : it[1] + " ") + it[2] + (byDay ? "  " : "");
            TextView big = text(main, byDay ? 15f : 16f, 0xF2FFFFFF);
            if (byDay) {
                android.text.SpannableString sp = new android.text.SpannableString(main + it[3]);
                sp.setSpan(new android.text.style.ForegroundColorSpan(0x80FFFFFF), main.length(), sp.length(), 0);
                big.setText(sp);
            }
            col.addView(big);
            String r = byDay ? it[4] : it[3];
            col.addView(text(r.isEmpty() ? " " : "☂︎ " + r, 11f, 0xCC8EC9FF));
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

    private View pill(String text, View.OnClickListener click) {
        TextView pill = new TextView(activity);
        pill.setText(text);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(16f);
        pill.setGravity(Gravity.CENTER);
        pill.setFocusable(true);
        pill.setFocusableInTouchMode(true);
        int w = Math.round(128 * dp), h = Math.round(72 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(Math.round(22 * dp));
        pill.setLayoutParams(lp);
        pill.setBackground(pillBackground(false));
        pill.setOnFocusChangeListener((v, has) -> {
            v.setBackground(pillBackground(has));
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
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

    private View card(int pos) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true);
        col.setFocusableInTouchMode(true);
        col.setClipChildren(false);
        int w = Math.round(128 * dp), h = Math.round(72 * dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Math.round(14 * dp));
        col.setLayoutParams(lp);

        FrameLayout frame = new FrameLayout(activity);
        ImageView art = new ImageView(activity);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable d = host.artworkAt(pos);
        if (d != null && d.getConstantState() != null) d = d.getConstantState().newDrawable().mutate();
        art.setImageDrawable(d);
        art.setBackgroundColor(0xFF1A1A1A);
        final float radius = 10 * dp;
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);
        frame.addView(art, new FrameLayout.LayoutParams(w, h));
        col.addView(frame, new LinearLayout.LayoutParams(w, h));

        TextView name = new TextView(activity);
        name.setText(host.labelAt(pos));
        name.setTextColor(0xE6FFFFFF);
        name.setTextSize(13f);
        name.setSingleLine(true);
        name.setPadding(Math.round(2 * dp), Math.round(8 * dp), 0, 0);
        col.addView(name);

        TextView when = new TextView(activity);
        long t = LastOpened.get(activity, host.packageAt(pos));
        when.setText(t > 0 ? LastOpened.describe(activity, t) : "");
        when.setTextColor(0x80FFFFFF);
        when.setTextSize(11f);
        when.setSingleLine(true);
        when.setPadding(Math.round(2 * dp), Math.round(2 * dp), 0, 0);
        col.addView(when);

        col.setOnFocusChangeListener((v, has) -> {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(radius);
            ring.setStroke(Math.round(2.5f * dp), 0xF2FFFFFF);
            frame.setForeground(has ? ring : null);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
        });
        col.setOnClickListener(v -> {
            close(false);
            host.launchAt(pos);
        });
        return col;
    }

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
        android.content.pm.PackageManager pm = activity.getPackageManager();
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
