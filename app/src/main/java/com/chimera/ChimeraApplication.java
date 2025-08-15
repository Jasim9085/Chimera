package com.chimera;

import android.app.Application;

public class ChimeraApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ConfigLoader.load(this);
        } catch (Exception e) {}
        try {
            CrashHandler handler = new CrashHandler(this);
            Thread.setDefaultUncaughtExceptionHandler(handler);
        } catch (Exception e) {}
    }
}