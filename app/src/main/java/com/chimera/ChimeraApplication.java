package com.chimera;

import android.app.Application;

public class ChimeraApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Set our custom handler as the default one for the entire application
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
    }
}
