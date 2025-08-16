package com.chimera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent.getAction() != null && intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
                FCMHandlerService.registerDevice(context);
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "BootReceiver", e);
        }
    }
}