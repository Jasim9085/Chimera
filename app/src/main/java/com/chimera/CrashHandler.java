package com.chimera;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            logCrashToServer(throwable);
        } catch (Exception e) {
            // Failsafe if remote logging fails
        } finally {
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
            System.exit(2);
        }
    }

    private void logCrashToServer(Throwable throwable) {
        try {
            String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();

            JSONObject postData = new JSONObject();
            postData.put("deviceName", deviceModel);
            postData.put("deviceId", androidId);
            postData.put("stackTrace", stackTrace);

            URL url = new URL("https://your-netlify-app.netlify.app/.netlify/functions/log-crash");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData.toString().getBytes(StandardCharsets.UTF_8));
            }
            conn.getInputStream().close();
            conn.disconnect();
        } catch (Exception e) {
            // This is a best-effort attempt, so we don't handle the failure
        }
    }
}