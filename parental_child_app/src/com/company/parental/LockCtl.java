package com.company.parental;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * Kontrol kunci layar memakai DevicePolicyManager (API 23+).
 * - lockNow(): langsung matikan layar (butuh device admin aktif)
 * - unlockScreen(): nyalakan kembali + keyguard dismiss (tanpa bypass PIN user)
 */
public final class LockCtl {

    private static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    public static boolean isAdminActive(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(admin(c));
    }

    /** Kunci layar sekarang. Return true bila berhasil. */
    public static boolean lock(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !isAdminActive(c)) return false;
        try {
            // API 28+: bisa paksa dengan credential; fallback ke lockNow biasa.
            dpm.lockNow();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Nyalakan layar lalu minta keyguard dismiss (API 28+, hanya sukses bila tidak ada PIN user). */
    public static void unlock(Context c) {
        try {
            // nyalakan layar dulu
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                @SuppressWarnings("deprecation")
                android.os.PowerManager.WakeLock wl =
                        pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                        | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                                        | android.os.PowerManager.ON_AFTER_RELEASE,
                                "parental:wake");
                wl.acquire(3000);
                wl.release();
            }
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()
                    && android.os.Build.VERSION.SDK_INT >= 28) {
                km.requestDismissKeyguard((android.app.Activity) null, null);
            }
        } catch (Exception ignored) { }
    }
}
