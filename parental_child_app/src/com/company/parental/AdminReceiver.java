package com.company.parental;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Device Admin untuk fitur "kunci layar anak".
 * forceLock() hanya legal jika admin sudah aktif — inilah jalur nyata tanpa root.
 */
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "Parental: Device Admin aktif", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "Parental: Device Admin nonaktif", Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Jika dinonaktifkan, orang tua tidak bisa mengunci layar perangkat ini.";
    }
}
