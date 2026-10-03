package com.company.parental;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Service latar depan yang:
 *  1. Polling node "commands" di Firebase Realtime DB (REST) tiap beberapa detik.
 *  2. Mengeksekusi perintah orang tua: lockScreen / unlockScreen / blockApps / setPassword.
 *  3. Publikasi ulang "state" perangkat agar web panel melihat status & daftar aplikasi.
 */
public class ParentalService extends Service {

    public static final String ACTION_START = "com.company.parental.START";
    private static final long POLL_MS = 4000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running = false;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            tick();
            h.postDelayed(this, POLL_MS);
        }
    };

    /** Ambil daftar aplikasi launchable (nama + paket) sebagai JSON array string. */
    static String collectApps(Context c) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            i.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            java.util.List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(i, 0);
            java.util.Map<String, String> names = new java.util.HashMap<>();
            for (android.content.pm.ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName())) continue;
                CharSequence ls = ri.loadLabel(pm);
                names.put(pkg, ls == null ? pkg : ls.toString());
            }
            JSONArray arr = new JSONArray();
            java.util.List<String> keys = new java.util.ArrayList<>(names.keySet());
            java.util.Collections.sort(keys);
            for (String pkg : keys) {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("name", names.get(pkg));
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        if (!running) {
            running = true;
            h.post(poll);
        }
        // pastikan service blocker berjalan juga
        try { startService(new Intent(this, BlockerService.class)); } catch (Exception ignored) { }
        return START_STICKY;
    }

    private void tick() {
        // 1) baca perintah dari firebase
        FireClient.get("commands", new FireClient.JsonCb() {
            @Override public void run(JSONObject cmds) {
                if (cmds == null) { publishState(); return; }
                try { handleCommands(cmds); } catch (Exception e) { publishState(); }
            }
        });
    }

    private void handleCommands(JSONObject cmds) throws Exception {
        long cmdTs = cmds.optLong("ts", 0);

        // permintaan refresh daftar aplikasi dari web panel (bisa kapan saja, tanpa ts baru)
        if (cmds.optBoolean("refreshApps", false)) {
            JSONArray appsArr = new JSONArray(collectApps(this));
            JSONObject wrap = new JSONObject();
            wrap.put("apps", appsArr);
            wrap.put("updatedAt", System.currentTimeMillis());
            FireClient.put("apps", wrap, null);
            FireClient.put("commands", new JSONObject(cmds.toString())
                    .put("refreshApps", false), null);
        }

        long ackTs = readAckTs();

        if (cmdTs > 0 && cmdTs != ackTs) {
            // password kunci layar (dikirim via HTML panel)
            if (cmds.has("password")) {
                Store.setPassword(this, cmds.getString("password"));
            }

            // ON/OFF kunci layar
            if (cmds.has("lockEnabled")) {
                boolean on = cmds.getBoolean("lockEnabled");
                Store.setLockEnabled(this, on);
                if (on) LockCtl.lock(this); else LockCtl.unlock(this);
            }

            // eksekusi aksi langsung
            String action = cmds.optString("action", "");
            if ("lock".equals(action)) {
                Store.setLockEnabled(this, true);
                LockCtl.lock(this);
            } else if ("unlock".equals(action)) {
                Store.setLockEnabled(this, false);
                LockCtl.unlock(this);
            }

            // blokir aplikasi ON/OFF via html
            if (cmds.has("blockedPackages")) {
                Set<String> pkgs = new HashSet<>();
                JSONArray a = cmds.getJSONArray("blockedPackages");
                for (int i = 0; i < a.length(); i++) pkgs.add(a.getString(i));
                Store.setBlocked(this, pkgs);
            }

            // tulis ack + state terbaru
            JSONObject ack = new JSONObject();
            ack.put("ts", cmdTs);
            ack.put("appliedAt", System.currentTimeMillis());
            ParentalService.saveAck(this, cmdTs);
            FireClient.put("commands_ack", ack, new FireClient.Cb() {
                @Override public void run(Boolean ok) { publishState(); }
            });
        } else {
            publishState();
        }
    }

    private long readAckTs() {
        // dibaca sinkron sederhana lewat sharedpref lokal untuk menghindari race
        return Store.sp(this).getLong("last_ack_ts", 0);
    }

    private void publishState() {
        try {
            JSONObject payload = Store.toPayload(this);
            FireClient.put("state", payload, new FireClient.Cb() {
                @Override public void run(Boolean ok) { /* retry pada tick berikutnya bila gagal */ }
            });
        } catch (Exception ignored) { }
    }

    /** Simpan ts ack secara lokal agar tidak memproses command yang sama dua kali. */
    public static void saveAck(Context c, long ts) {
        Store.sp(c).edit().putLong("last_ack_ts", ts).apply();
    }

    private void startForegroundCompat() {
        String CH = "parental_service";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Parental Control",
                    NotificationManager.IMPORTANCE_LOW);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("parental")
                .setContentText("Monitoring kontrol orang tua aktif")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pi)
                .build();
        try { startForeground(101, n); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        h.removeCallbacks(poll);
        super.onDestroy();
    }
}
