package com.company.children.services;

// =====================================================================
//  UBah KONFIGURASI SERVER DI SINI bila URL ngrok berubah:
//  build.gradle -> defaultConfig -> buildConfigField "SERVER_URL"
//  (LHOST = https://friday-succulent-marigold.ngrok-free.dev , LPORT = 8000)
// =====================================================================

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.company.children.BuildConfig;
import com.company.children.R;
import com.company.children.activities.BlockerActivity;
import com.company.children.activities.MainActivity;
import com.company.children.helpers.ForegroundApp;
import com.company.children.helpers.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Service inti aplikasi children:
 *  1. Socket.io ke server PARENTAL (LHOST ngrok, LPORT 8000) — kirim child:hello,
 *     terima parent:control (kunci layar / blokir aplikasi).
 *  2. Watchdog tiap 1 detik: cek aplikasi foreground; bila mode kunci layar ON atau
 *     aplikasi foreground ada di daftar blokir -> startActivity BlockerActivity
 *     (overlay全屏 menutupi layar, tidak bisa ditutup tanpa password/buka dari web).
 *  3. Laporkan battery & aplikasi aktif ke web (child:status tiap 15 detik).
 */
public class SocketService extends Service {

    public static final String ACTION_STOP = "com.company.children.STOP";

    private static volatile boolean running = false;
    private Socket socket;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean lastLockActive = false;

    /* ------------------------- API untuk UI ------------------------- */
    public static void start(Context c) {
        Intent i = new Intent(c, SocketService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, SocketService.class));
    }

    public static boolean isRunning() { return running; }

    /* --------------------------- Lifecycle -------------------------- */
    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startForeground(101, buildNotification("children terhubung…"));
        connectSocket();
        handler.post(watchdog);
        handler.post(statusTicker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (socket == null || !socket.connected()) connectSocket();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(statusTicker);
        if (socket != null) socket.disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* -------------------------- Socket.io --------------------------- */
    private void connectSocket() {
        try {
            IO.Options opts = new IO.Options();
            opts.reconnection = true;
            opts.transports = new String[]{"websocket"};
            socket = IO.socket(BuildConfig.SERVER_URL, opts);

            socket.on(Socket.EVENT_CONNECT, args -> hello());

            // Orang tua mengirim kontrol dari web parental
            socket.on("parent:control", args -> {
                try {
                    JSONObject ctrl = (JSONObject) args[0];
                    JSONObject lock = ctrl.optJSONObject("screenLock");
                    boolean active = lock != null && lock.optBoolean("active", false);
                    String pass = lock != null ? lock.optString("password", "") : "";
                    boolean blockAll = ctrl.optBoolean("lockAll", false);

                    JSONArray apps = ctrl.optJSONArray("blockedApps");
                    List<String> list = new ArrayList<>();
                    if (apps != null)
                        for (int i = 0; i < apps.length(); i++) list.add(apps.getString(i));
                    String csv = TextUtils.join(",", list);

                    Prefs.saveControl(this, active, pass, blockAll, csv);

                    // kunci layar ON -> langsung tampilkan overlay
                    if (active && !lastLockActive) showBlocker(true, "");
                    if (!active && lastLockActive) {
                        // orang tua membuka kunci dari web -> tutup overlay
                        Intent close = new Intent(this, BlockerActivity.class)
                                .setAction(BlockerActivity.ACTION_DISMISS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(close);
                    }
                    lastLockActive = active;
                    updateNotification(active ? "LAYAR TERKUNCI oleh orang tua" : "children terhubung ✔");
                } catch (Exception ignored) { }
            });

            socket.on("parent:paired", args -> {
                try {
                    String code = ((JSONObject) args[0]).optString("code", Prefs.getCode(SocketService.this));
                    Intent i = new Intent(MainActivity.ACTION_PAIRED);
                    i.putExtra(MainActivity.EXTRA_CODE, code);
                    sendBroadcast(i);
                } catch (Exception ignored) { }
            });

            socket.on("parent:ping", (args, ack) -> {
                try {
                    JSONObject resp = new JSONObject();
                    resp.put("pong", true);
                    resp.put("deviceId", Prefs.getDeviceId(SocketService.this));
                    resp.put("battery", batteryLevel());
                    resp.put("lockActive", Prefs.isLockActive(SocketService.this));
                    if (ack != null && ack.length > 0 && ack[0] instanceof io.socket.client.Ack) {
                        ((io.socket.client.Ack) ack[0]).call(resp);
                    }
                } catch (Exception ignored) { }
            });

            socket.connect();
        } catch (Exception e) {
            handler.postDelayed(this::connectSocket, 4000);
        }
    }

    /** Daftar ke server sebagai perangkat anak; server membalas pairing code 6 digit. */
    private void hello() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("deviceId", Prefs.getDeviceId(this));
            payload.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            payload.put("androidVersion", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            payload.put("battery", batteryLevel());

            socket.emit("child:hello", payload, new io.socket.client.Ack() {
                @Override
                public void call(Object... ackArgs) {
                    try {
                        JSONObject obj = (JSONObject) ackArgs[0];
                        final String code = obj.getString("code");
                        JSONObject ctrl = obj.optJSONObject("control");
                        handler.post(() -> {
                            if (Prefs.isPaired(getApplicationContext())) {
                                // sesi lama tersimpan -> tetap dashboard, kode diperbarui
                                Prefs.setPaired(getApplicationContext(), true, code);
                            }
                            broadcastCode(code);
                            applyInitialControl(ctrl);
                        });
                    } catch (Exception ignored) { }
                }
            });
        } catch (Exception ignored) { }
    }

    private void applyInitialControl(JSONObject ctrl) {
        if (ctrl == null) return;
        JSONObject lock = ctrl.optJSONObject("screenLock");
        boolean active = lock != null && lock.optBoolean("active", false);
        String pass = lock != null ? lock.optString("password", "") : "";
        JSONArray apps = ctrl.optJSONArray("blockedApps");
        List<String> list = new ArrayList<>();
        if (apps != null) for (int i = 0; i < apps.length(); i++) list.add(apps.optString(i));
        Prefs.saveControl(this, active, pass, ctrl.optBoolean("lockAll", false), TextUtils.join(",", list));
        lastLockActive = active;
        if (active) showBlocker(true, "");
    }

    private void broadcastCode(String code) {
        Intent i = new Intent(MainActivity.ACTION_CODE);
        i.putExtra(MainActivity.EXTRA_CODE, code);
        sendBroadcast(i);
    }

    /* --------------------------- Watchdog --------------------------- */
    /** Tiap 1 detik periksa foreground app vs aturan kontrol orang tua. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                boolean lockOn = Prefs.isLockActive(SocketService.this);
                if (lockOn && !lastLockActive) { lastLockActive = true; }
                if (lockOn) {
                    showBlocker(true, "");
                } else {
                    if (!lastLockActive && Prefs.isBlockAllOn(SocketService.this)) {
                        String fg = ForegroundApp.get(SocketService.this);
                        if (Prefs.isBlockedPackage(SocketService.this, fg)) {
                            showBlocker(false, fg);
                        }
                    }
                    lastLockActive = false;
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 1000);
        }
    };

    private void showBlocker(boolean lockMode, String blockedPkg) {
        Intent i = new Intent(this, BlockerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(BlockerActivity.EXTRA_LOCK_MODE, lockMode);
        i.putExtra(BlockerActivity.EXTRA_BLOCKED_PKG, blockedPkg == null ? "" : blockedPkg);
        startActivity(i);
    }

    /* ------------------------ Status ticker ------------------------- */
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            try {
                if (socket != null && socket.connected()) {
                    JSONObject j = new JSONObject();
                    j.put("battery", batteryLevel());
                    j.put("currentApp", ForegroundApp.get(SocketService.this));
                    socket.emit("child:status", j);
                }
            } catch (Exception ignored) { }
            handler.postDelayed(this, 15000);
        }
    };

    private int batteryLevel() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
        } catch (Exception e) { return -1; }
    }

    /* ------------------------- Notifikasi --------------------------- */
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    "children_main", "children service", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : 0);
        return new NotificationCompat.Builder(this, "children_main")
                .setContentTitle("children")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(101, buildNotification(text));
    }
}
