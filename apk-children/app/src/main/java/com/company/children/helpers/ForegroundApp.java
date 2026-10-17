package com.company.children.helpers;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.text.TextUtils;

/**
 * Mendeteksi package aplikasi yang sedang tampil di foreground.
 * Memakai UsageStatsManager (butuh izin PACKAGE_USAGE_STATS).
 */
public class ForegroundApp {

    private static String lastPackage = "";

    /** Return package name aplikasi foreground, atau "" bila tidak terdeteksi. */
    public static String get(Context context) {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return lastPackage;

            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 10_000L, now + 1_000L);
            UsageEvents.Event event = new UsageEvents.Event();
            String found = null;
            long bestTs = 0;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || event.getEventType() == 1) { // MOVED_TO_FOREGROUND (API lama)
                    if (event.getTimeStamp() >= bestTs) {
                        bestTs = event.getTimeStamp();
                        found = event.getPackageName();
                    }
                }
            }
            if (!TextUtils.isEmpty(found)) lastPackage = found;
            // jangan laporkan paket kita sendiri sebagai "aplikasi anak"
            if (context.getPackageName().equals(lastPackage)) return "";
            return lastPackage;
        } catch (Throwable t) {
            return lastPackage;
        }
    }
}
