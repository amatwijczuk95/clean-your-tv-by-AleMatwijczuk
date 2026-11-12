package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Local addition (theme key "sleep_timer"): puts the TV to sleep after 30, 60, 90 or 120 minutes, chosen in the
 * Down panel. The end time is kept, so the countdown shows on Home; at the end an alarm asks HomeKeyService (the
 * remote helper, an accessibility service) for the system's sleep action. Nothing runs between those two moments.
 */
final class SleepTimer {
    static final int[] OPTIONS = {0, 30, 60, 90, 120};
    private static final String PREFS = "sleep_timer", END = "end";

    private SleepTimer() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Minutes left, rounded up; 0 when no timer is set (or it already ran). */
    static int minutesLeft(Context c) {
        long left = prefs(c).getLong(END, 0) - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /** The next choice after the current one: off → 30 → 60 → 90 → 120 → off. */
    static int next(Context c) {
        int now = minutesLeft(c);
        for (int o : OPTIONS) if (o > now) return o;
        return 0;
    }

    static void set(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Receiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (minutes <= 0) {
            prefs(c).edit().remove(END).apply();
            if (am != null) am.cancel(pi);
            return;
        }
        long end = System.currentTimeMillis() + minutes * 60_000L;
        prefs(c).edit().putLong(END, end).apply();
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
    }

    /** The alarm: clear the timer and put the TV to sleep. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            prefs(c).edit().remove(END).apply();
            HomeKeyService.sleepNow();
        }
    }
}
