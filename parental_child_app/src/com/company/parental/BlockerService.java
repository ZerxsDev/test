package com.company.parental;

import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Set;

/**
 * Pemblokir aplikasi NYATA (tanpa root):
 *  - Deteksi aplikasi foreground via UsageStatsManager (API 23+).
 *  - Bila paket ada di daftar blokir -> tampilkan overlay fullscreen + pindah ke home.
 *  - Overlay memakai SYSTEM_ALERT_WINDOW (bisa diminta dari Settings).
 */
public class BlockerService extends Service {

    private static final long CHECK_MS = 500;
    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View overlay;
    private boolean showing = false;

    private final Runnable check = new Runnable() {
        @Override public void run() {
            try { evaluate(); } catch (Exception ignored) { }
            h.postDelayed(this, CHECK_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!h.hasMessages(0)) h.post(check);
        return START_STICKY;
    }

    private void evaluate() {
        String fg = currentForegroundPackage();
        Set<String> blocked = Store.getBlocked(this);
        boolean isBlocked = fg != null && blocked.contains(fg);

        // jangan pernah blokir launcher / sistem / aplikasi ini sendiri
        if (getPackageName().equals(fg)) isBlocked = false;

        if (isBlocked && !showing) showOverlay(fg);
        else if (!isBlocked && showing) hideOverlay();
    }

    private void showOverlay(String pkg) {
        if (wm == null) return;
        overlay = new TextView(this);
        ((TextView) overlay).setText("Aplikasi diblokir orang tua ⛔\n\n" + pkg);
        ((TextView) overlay).setTextColor(0xFFFFFFFF);
        ((TextView) overlay).setTextSize(20);
        ((TextView) overlay).setGravity(Gravity.CENTER);
        ((TextView) overlay).setBackgroundColor(0xFF101418);

        @SuppressWarnings("deprecation")
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(overlay, lp);
            showing = true;
            // usir aplikasi terblokir ke home
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) am.killBackgroundProcesses(pkg);
            } catch (Exception ignored) { }
        } catch (Exception e) { showing = false; }
    }

    private void hideOverlay() {
        if (overlay != null && wm != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null;
        showing = false;
    }

    /** Ambil paket foreground terbaru lewat UsageStatsManager (butuh izin Usage Access). */
    private String currentForegroundPackage() {
        try {
            android.app.usage.UsageStatsManager usm =
                    (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = usm.queryEvents(now - 10000, now + 1000);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Exception ex) {
            return null; // izin usage access belum diberikan
        }
    }

    /** Cek apakah izin overlay sudah diberikan (dipakai MainActivity). */
    public static boolean canDraw(Context c) {
        if (Build.VERSION.SDK_INT >= 23) return Settings.canDrawOverlays(c);
        return true;
    }

    public static Intent overlaySettingsIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.getPackageName()));
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(check);
        hideOverlay();
        super.onDestroy();
    }
}
