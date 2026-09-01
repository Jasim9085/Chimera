package com.chimera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent.getAction();
            if (action == null) return;
            if (action.equals(Intent.ACTION_BOOT_COMPLETED)
                    || action.equals(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                    || action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)
                    || action.equals("android.intent.action.PACKAGE_REPLACED")
                    || action.equals("android.intent.action.QUICKBOOT_POWERON")
                    || action.equals("com.htc.intent.action.QUICKBOOT_POWERON")) {

                // Android 15: BOOT_COMPLETED cannot start dataSync/camera etc directly -> use WorkManager + JobScheduler + FCM path
                // So we delegate to ResurrectionHelper which uses allowed remoteMessaging/specialUse via WorkManager
                ResurrectionHelper.resurrect(context);
                // Also register via Firestore/FCM (replaces Netlify)
                try { FCMHandlerService.registerDevice(context); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "BootReceiver", e);
        }
    }
}
