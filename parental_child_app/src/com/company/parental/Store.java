package com.company.parental;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Penyimpanan state kontrol parental (password kunci layar & daftar aplikasi terblokir).
 * State ini digabung ke payload firebase agar sinkron dua arah dengan web panel.
 */
public final class Store {

    private static final String FILE = "parental_state";
    private static final String K_PASS   = "lock_password";
    private static final String K_LOCKON = "lock_enabled";     // kunci layar ON/OFF
    private static final String K_BLOCK  = "blocked_packages"; // JSON array pkgs

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- Password kunci layar (dikirim dari HTML via JS bridge) ----
    public static void setPassword(Context c, String pass) {
        sp(c).edit().putString(K_PASS, pass == null ? "" : pass).apply();
    }

    public static String getPassword(Context c) {
        return sp(c).getString(K_PASS, "");
    }

    // ---- Status kunci layar ON/OFF ----
    public static boolean isLockEnabled(Context c) {
        return sp(c).getBoolean(K_LOCKON, false);
    }

    public static void setLockEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_LOCKON, on).apply();
    }

    // ---- Daftar aplikasi terblokir ----
    public static Set<String> getBlocked(Context c) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(K_BLOCK, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    public static void setBlocked(Context c, Set<String> pkgs) {
        JSONArray a = new JSONArray();
        for (String p : pkgs) a.put(p);
        sp(c).edit().putString(K_BLOCK, a.toString()).apply();
    }

    public static boolean isBlocked(Context c, String pkg) {
        return getBlocked(c).contains(pkg);
    }

    /** Payload JSON lengkap yang dipublikasikan ke node perangkat di firebase. */
    public static JSONObject toPayload(Context c) throws Exception {
        JSONObject o = new JSONObject();
        JSONArray blocked = new JSONArray();
        for (String p : getBlocked(c)) blocked.put(p);
        o.put("model", Build_TAG());
        o.put("androidVersion", android.os.Build.VERSION.RELEASE);
        o.put("lockEnabled", isLockEnabled(c));
        o.put("blockedPackages", blocked);
        o.put("lastSeen", System.currentTimeMillis());
        return o;
    }

    private static String Build_TAG() {
        return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
    }

    private Store() { }
}
