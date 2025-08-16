package com.chimera;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class DeviceDetailsHelper {

    public interface DetailsCallback {
        void onDetailsReady(String details);
    }

    public static void getDeviceDetails(Context context, DetailsCallback callback) {
        StringBuilder details = new StringBuilder();

        details.append("Chimera Device Intel Report\n");
        details.append("====================\n\n");

        details.append("--- Device Info ---\n");
        details.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        details.append("Model: ").append(Build.MODEL).append("\n");
        details.append("Brand: ").append(Build.BRAND).append("\n");
        details.append("Android Version: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        details.append("Android ID: ").append(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID)).append("\n");

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        details.append("Screen: ").append(metrics.heightPixels).append("x").append(metrics.widthPixels).append(" (").append(metrics.densityDpi).append(" dpi)\n\n");

        details.append("--- Status ---\n");
        details.append(getBatteryInfo(context));
        details.append(getScreenState(context));
        details.append(getCallState(context));
        details.append(getForegroundApp(context)).append("\n");

        getSensorAndLocationInfo(context, sensorInfo -> {
            details.append(sensorInfo);
            callback.onDetailsReady(details.toString());
        });
    }

    private static String getBatteryInfo(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, filter);
        if (batteryStatus == null) return "Battery Info: Not Available\n";

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

        float batteryPct = level * 100 / (float) scale;
        String statusStr = "Unknown";
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) statusStr = "Charging";
        if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) statusStr = "Discharging";
        if (status == BatteryManager.BATTERY_STATUS_FULL) statusStr = "Full";
        if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) statusStr = "Not Charging";

        String chargeSource = "";
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) chargeSource = " (USB)";
            if (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) chargeSource = " (AC)";
            if (chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS) chargeSource = " (Wireless)";
        }

        return "Battery: " + (int) batteryPct + "% (" + statusStr + chargeSource + ")\n";
    }

    private static String getScreenState(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return "Screen State: " + (pm.isInteractive() ? "ON" : "OFF") + "\n";
    }

    private static String getCallState(Context context) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "Call State: Permission Denied\n";
        }
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        int callState = tm.getCallState();
        String state = "Idle";
        if (callState == TelephonyManager.CALL_STATE_RINGING) state = "Ringing";
        if (callState == TelephonyManager.CALL_STATE_OFFHOOK) state = "On Call";
        return "Call State: " + state + "\n";
    }

    private static String getForegroundApp(Context context) {
        String currentApp = "Not Available";
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 1000, time);
        if (appList != null && appList.size() > 0) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : appList) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!mySortedMap.isEmpty()) {
                currentApp = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
            }
        } else {
             currentApp = "Permission Not Granted (Usage Access)";
        }
        return "Foreground App: " + currentApp;
    }

    @SuppressLint("MissingPermission")
    private static void getSensorAndLocationInfo(Context context, DetailsCallback callback) {
        StringBuilder sensorDetails = new StringBuilder();
        FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(context);

        Task<Location> locationTask = locationClient.getLastLocation();
        locationTask.addOnCompleteListener(task -> {
            sensorDetails.append("--- Location ---\n");
            if (task.isSuccessful() && task.getResult() != null) {
                Location location = task.getResult();
                sensorDetails.append("Lat/Lon: ").append(location.getLatitude()).append(", ").append(location.getLongitude()).append("\n");
                sensorDetails.append("Accuracy: ").append(location.getAccuracy()).append(" meters\n\n");
            } else {
                sensorDetails.append("Location: Not Available or Permission Denied\n\n");
            }

            analyzeSensors(context, sensorAnalysis -> {
                sensorDetails.append(sensorAnalysis);
                callback.onDetailsReady(sensorDetails.toString());
            });
        });
    }

    private static void analyzeSensors(Context context, DetailsCallback callback) {
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        StringBuilder sensorAnalysis = new StringBuilder();
        sensorAnalysis.append("--- Sensor Analysis ---\n");

        if (accelerometer == null && magnetometer == null) {
            sensorAnalysis.append("Orientation/Position sensors not available.\n");
            callback.onDetailsReady(sensorAnalysis.toString());
            return;
        }

        final float[][] sensorData = new float[3][];
        sensorData[0] = null; // Gravity
        sensorData[1] = null; // Geomagnetic
        sensorData[2] = null; // Proximity

        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) sensorData[0] = event.values.clone();
                if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) sensorData[1] = event.values.clone();
                if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) sensorData[2] = event.values.clone();
            }
            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(listener, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(listener, proximity, SensorManager.SENSOR_DELAY_NORMAL);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            sensorManager.unregisterListener(listener);

            if (sensorData[2] != null) {
                sensorAnalysis.append("Proximity: ").append(sensorData[2][0]).append(" cm\n");
                if (sensorData[2][0] < 1) {
                    sensorAnalysis.append("Analysis: Object is very close to screen (in pocket or on call).\n");
                }
            }

            if (sensorData[0] != null) { // Gravity
                float x = sensorData[0][0];
                float y = sensorData[0][1];
                float z = sensorData[0][2];
                if (Math.abs(z) > 8.5) {
                    sensorAnalysis.append("Position: Lying flat on a surface, ").append(z > 0 ? "screen facing UP." : "screen facing DOWN.").append("\n");
                } else if (Math.abs(y) > 8.5) {
                    sensorAnalysis.append("Position: Held upright (Portrait Mode).\n");
                } else if (Math.abs(x) > 8.5) {
                    sensorAnalysis.append("Position: Held on its side (Landscape Mode).\n");
                } else {
                    sensorAnalysis.append("Position: Held at an angle.\n");
                }
            }

            if (sensorData[0] != null && sensorData[1] != null) { // Both available
                float[] R = new float[9];
                float[] I = new float[9];
                boolean success = SensorManager.getRotationMatrix(R, I, sensorData[0], sensorData[1]);
                if (success) {
                    float[] orientation = new float[3];
                    SensorManager.getOrientation(R, orientation);
                    float azimuth = (float) Math.toDegrees(orientation[0]);
                    if (azimuth < 0) azimuth += 360;

                    String direction = "Unknown";
                    if (azimuth >= 337.5 || azimuth < 22.5) direction = "North";
                    else if (azimuth >= 22.5 && azimuth < 67.5) direction = "North-East";
                    else if (azimuth >= 67.5 && azimuth < 112.5) direction = "East";
                    else if (azimuth >= 112.5 && azimuth < 157.5) direction = "South-East";
                    else if (azimuth >= 157.5 && azimuth < 202.5) direction = "South";
                    else if (azimuth >= 202.5 && azimuth < 247.5) direction = "South-West";
                    else if (azimuth >= 247.5 && azimuth < 292.5) direction = "West";
                    else if (azimuth >= 292.5 && azimuth < 337.5) direction = "North-West";
                    sensorAnalysis.append("Direction: Top of device is pointing ").append(direction).append(" (").append((int)azimuth).append("°).\n");
                }
            }

            callback.onDetailsReady(sensorAnalysis.toString());
        }, 1500);
    }
}