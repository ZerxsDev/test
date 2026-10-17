package com.company.children.receivers;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.company.children.services.SocketService;

/**
 * Device Admin untuk fitur force-lock & perlindungan uninstall.
 * Nama kelas: receivers/DeviceAdminReceiverX.java (di-rename dari
 * DeviceAdminReceiver agar tidak bentrok dengan class android).
 */
public class DeviceAdminReceiverX extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "Device Admin children aktif", Toast.LENGTH_SHORT).show();
        SocketService.start(context);
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // peringatan bila anak mencoba mematikan device admin
        return "Mematikan izin ini akan melumpuhkan parental control dari orang tua.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Toast.makeText(context, "Device Admin dimatikan", Toast.LENGTH_SHORT).show();
    }
}
