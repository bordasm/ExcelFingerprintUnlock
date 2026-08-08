package hu.bordasm.excelautofillprobe;

import android.content.Context;
import android.content.SharedPreferences;

final class ProbeState {
    static final long ARM_WINDOW_MILLIS = 2 * 60 * 1000L;
    private static final String PREFS = "probe-state";
    private static final String KEY_ARMED_UNTIL = "armed-until";

    private ProbeState() {
    }

    static synchronized void arm(Context context) {
        long until = System.currentTimeMillis() + ARM_WINDOW_MILLIS;
        preferences(context).edit().putLong(KEY_ARMED_UNTIL, until).apply();
        ProbeLog.append(context, "PROBE_ARMED windowSeconds=120");
    }

    static synchronized void disarm(Context context) {
        preferences(context).edit().remove(KEY_ARMED_UNTIL).apply();
    }

    static synchronized boolean isArmed(Context context) {
        return remainingMillis(context) > 0;
    }

    static synchronized long remainingMillis(Context context) {
        long remaining = preferences(context).getLong(KEY_ARMED_UNTIL, 0L)
                - System.currentTimeMillis();
        if (remaining <= 0 || remaining > ARM_WINDOW_MILLIS) {
            return 0;
        }
        return remaining;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
