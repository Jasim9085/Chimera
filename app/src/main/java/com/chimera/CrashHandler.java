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
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            pw.flush();
            String crash = sw.toString();
            FileOutputStream fos = context.openFileOutput("crash.txt", Context.MODE_PRIVATE);
            fos.write(crash.getBytes());
            fos.close();
        } catch (Exception e) {}
        try {
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        } catch (Exception ex) {}
    }
}