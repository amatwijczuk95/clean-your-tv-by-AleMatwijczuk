package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local addition (theme keys weather_lat / weather_lon): the outdoor temperature as one quiet line under the date.
 * Asks Open-Meteo (no key, no account) only while Home is in front, at most every 30 minutes; the coordinates are
 * all that is sent. With no answer the line stays hidden.
 */
final class WeatherLine {
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    private final TextView view;
    private final double lat, lon;
    private final boolean icon;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Last answer, kept across Home being rebuilt (settings, theme, screensaver) so the line never pops in late. */
    private static String lastText;
    private static long fetchedAt;
    /** The next hours, as "08 h", symbol, "16°", for the Down panel; empty until the first answer. */
    private static volatile java.util.List<String[]> hourly = java.util.Collections.emptyList();

    static java.util.List<String[]> hourly() {
        return hourly;
    }

    /** The next days, as day name, symbol, "28°", "16°", "40%" (rain chance, "" when none). */
    private static volatile java.util.List<String[]> daily = java.util.Collections.emptyList();

    static java.util.List<String[]> daily() {
        return daily;
    }

    private static String rain(org.json.JSONArray a, int k) {
        if (a == null || a.isNull(k)) return "";
        int p = a.optInt(k, 0);
        return p >= 10 ? p + "%" : "";
    }
    private boolean resumed;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    WeatherLine(TextView view, double lat, double lon, boolean icon) {
        this.view = view;
        this.lat = lat;
        this.lon = lon;
        this.icon = icon;
        // Hold the line's space from the first layout: a line that appears later pushes the dock down under a
        // focus highlight that was already placed.
        if (lastText != null) {
            view.setText(lastText);
            view.setVisibility(View.VISIBLE);
        } else {
            view.setText(" ");
            view.setVisibility(View.INVISIBLE);
        }
    }

    void resume() {
        resumed = true;
        handler.removeCallbacks(tick);
        long age = SystemClock.elapsedRealtime() - fetchedAt;
        if (fetchedAt == 0 || age >= REFRESH_MS) {
            handler.post(tick);
        } else {
            handler.postDelayed(tick, REFRESH_MS - age);
        }
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(tick);
    }

    private void fetch() {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=8&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=7&timezone=auto",
                lat, lon);
        NET.execute(() -> {
            String text = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                    long temp = Math.round(cur.getDouble("temperature_2m"));
                    String sym = icon ? symbol(cur.optInt("weather_code", -1)) : "";
                    text = (sym.isEmpty() ? "" : sym + "  ") + temp + "°";
                    JSONObject h = new JSONObject(out.toString("UTF-8")).optJSONObject("hourly");
                    if (h != null) {
                        org.json.JSONArray times = h.getJSONArray("time");
                        org.json.JSONArray temps = h.getJSONArray("temperature_2m");
                        org.json.JSONArray codes = h.getJSONArray("weather_code");
                        org.json.JSONArray rains = h.optJSONArray("precipitation_probability");
                        java.util.List<String[]> next = new java.util.ArrayList<>();
                        for (int k = 1; k < times.length() && next.size() < 6; k++) {   // 0 is the current hour
                            String t = times.getString(k);   // 2026-10-27T08:00, local time
                            String hour = t.length() >= 13 ? t.substring(11, 13) + " h" : t;
                            next.add(new String[]{hour, symbol(codes.optInt(k, -1)), Math.round(temps.getDouble(k)) + "°", rain(rains, k)});
                        }
                        hourly = next;
                    }
                    JSONObject d = new JSONObject(out.toString("UTF-8")).optJSONObject("daily");
                    if (d != null) {
                        org.json.JSONArray days = d.getJSONArray("time");
                        org.json.JSONArray hi = d.getJSONArray("temperature_2m_max");
                        org.json.JSONArray lo = d.getJSONArray("temperature_2m_min");
                        org.json.JSONArray dc = d.getJSONArray("weather_code");
                        org.json.JSONArray dr = d.optJSONArray("precipitation_probability_max");
                        java.util.List<String[]> next = new java.util.ArrayList<>();
                        for (int k = 0; k < days.length(); k++) {
                            String name;
                            if (k == 0) {
                                name = view.getContext().getString(R.string.forecast_today);
                            } else {
                                name = java.time.LocalDate.parse(days.getString(k)).getDayOfWeek()
                                        .getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).replace(".", "");
                                name = name.substring(0, 1).toUpperCase(Locale.getDefault()) + name.substring(1);
                            }
                            next.add(new String[]{name, symbol(dc.optInt(k, -1)), Math.round(hi.getDouble(k)) + "°",
                                    Math.round(lo.getDouble(k)) + "°", rain(dr, k)});
                        }
                        daily = next;
                    }
                }
            } catch (Exception ignored) {
                // Offline or a bad answer: keep whatever is shown, or nothing.
            } finally {
                if (c != null) c.disconnect();
            }
            final String shown = text;
            handler.post(() -> {
                if (shown == null) return;
                fetchedAt = SystemClock.elapsedRealtime();
                lastText = shown;
                view.setText(shown);
                view.setVisibility(View.VISIBLE);
            });
        });
    }

    /** WMO weather code to a small text symbol (monochrome where the font allows). */
    static String symbol(int code) {
        if (code < 0) return "";
        if (code <= 1) return "☀︎";
        if (code == 2) return "⛅︎";
        if (code == 3) return "☁︎";
        if (code == 45 || code == 48) return "≋";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄︎";
        if (code >= 95) return "⚡︎";
        if (code >= 51) return "☂︎";
        return "";
    }
}
