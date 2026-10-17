package com.company.children.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.company.children.services.SocketService;

/** Setelah HP anak boot ulang, service koneksi ke web parental dijalankan lagi. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SocketService.start(context);
        }
    }
}
