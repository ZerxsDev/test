package com.company.children.helpers;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Simpan sesi & kontrol perangkat (dipakai MainActivity, SocketService, BlockerActivity).
 * Kunci layar & daftar aplikasi blokir disimpan permanen agar tetap berlaku
 * walaupun HP anak restart / aplikasi ditutup.
 */
public class Prefs {
    private static final String FILE = "children_prefs";

    public static final String KEY_DEVICE_ID   = "device_id";
    public static final String KEY_PAIRED      = "paired";       // boolean : sudah ditautkan via web
    public static final String KEY_CODE        = "pair_code";    // 6 digit dari server
    public static final String KEY_LOCK_ACTIVE = "lock_active";  // mode kunci layar ON/OFF
    public static final String KEY_LOCK_PASS   = "lock_password";
    public static final String KEY_BLOCK_ALL   = "block_all";    // mode blokir aplikasi ON/OFF
    public static final String KEY_BLOCK_LIST  = "blocked_apps"; // dipisah koma

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** ID unik perangkat — stabil per instalasi & per hardware. */
    public static String getDeviceId(Context c) {
        String id = sp(c).getString(KEY_DEVICE_ID, null);
        if (TextUtils.isEmpty(id)) {
            id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (TextUtils.isEmpty(id)) id = "child-" + System.currentTimeMillis();
            sp(c).edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static void setPaired(Context c, boolean paired, String code) {
        sp(c).edit()
                .putBoolean(KEY_PAIRED, paired)
                .putString(KEY_CODE, code == null ? "" : code)
                .apply();
    }

    public static boolean isPaired(Context c)   { return sp(c).getBoolean(KEY_PAIRED, false); }
    public static String getCode(Context c)     { return sp(c).getString(KEY_CODE, ""); }

    /** Simpan seluruh kontrol yang dikirim orang tua dari web. */
    public static void saveControl(Context c, boolean lockActive, String password,
                                   boolean blockAll, String blockedCsv) {
        sp(c).edit()
                .putBoolean(KEY_LOCK_ACTIVE, lockActive)
                .putString(KEY_LOCK_PASS, password == null ? "" : password)
                .putBoolean(KEY_BLOCK_ALL, blockAll)
                .putString(KEY_BLOCK_LIST, blockedCsv == null ? "" : blockedCsv)
                .apply();
    }

    public static boolean isLockActive(Context c)  { return sp(c).getBoolean(KEY_LOCK_ACTIVE, false); }
    public static String getLockPassword(Context c){ return sp(c).getString(KEY_LOCK_PASS, ""); }
    public static boolean isBlockAllOn(Context c)  { return sp(c).getBoolean(KEY_BLOCK_ALL, false); }

    public static String[] getBlockedList(Context c) {
        String s = sp(c).getString(KEY_BLOCK_LIST, "");
        if (TextUtils.isEmpty(s)) return new String[0];
        return s.split(",");
    }

    public static boolean isBlockedPackage(Context c, String pkg) {
        if (pkg == null) return false;
        if (!isBlockAllOn(c)) return false;
        for (String p : getBlockedList(c)) {
            if (p.trim().equalsIgnoreCase(pkg)) return true;
        }
        return false;
    }
}
