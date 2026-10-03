package com.company.parental;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Jalankan service kontrol setelah perangkat dinyalakan. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                Intent s = new Intent(context, ParentalService.class);
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(s);
                } else {
                    context.startService(s);
                }
            } catch (Exception ignored) { }
        }
    }
}
