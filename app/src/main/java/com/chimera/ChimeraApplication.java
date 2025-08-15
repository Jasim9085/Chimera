package com.chimera;

import android.app.Application;

public class ChimeraApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
    }
}
