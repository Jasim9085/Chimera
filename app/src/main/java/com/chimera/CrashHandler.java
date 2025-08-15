package com.chimera;

import android.content.Context;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

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
            ErrorLogger.logError(context, "CrashHandler (FATAL)", throwable);
        } catch (Exception e) {
            // Failsafe if logger itself fails
        }
        try {
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        } catch (Exception ex) {
            ErrorLogger.logError(context, "CrashHandler_DefaultHandler", ex);
        }
    }
}