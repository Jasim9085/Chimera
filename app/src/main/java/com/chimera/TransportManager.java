package com.chimera;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/**
 * Military-grade smart orchastrator:
 * - Gates all transports via NetworkStateMonitor (no fallback if airplane/no validated net)
 * - Priority: 1 Firestore/RTDB+ FCM (primary direct) 2 WebRTC (direct P2P) 3 MQTT 4 Telegram secondary 5 Ntfy/Gist tertiary
 * - Client direct switch signaling via Firestore flag activeTransport / FCM action switch_transport
 */
public class TransportManager {
    private static List<Transport> transports = new ArrayList<>();
    private static volatile String activeTransport = "auto"; // auto, firestore, webrtc, mqtt, telegram, ntfy
    private static boolean initialized = false;

    public static void init(Context ctx) {
        if (initialized) return;
        initialized = true;
        try {
            NetworkStateMonitor.init(ctx);
            SharedPreferences prefs = ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
            activeTransport = prefs.getString("active_transport", "auto");
            // Ensure Firestore + Telegram present; WebRTC/MQTT lazy init to avoid heavy deps if not needed
            transports.clear();
            transports.add(new FirestoreTransport());
            transports.add(new TelegramTransport());
            transports.add(new MqttTransport());
            transports.add(new NtfyTransport());
            transports.add(new GistTransport());
            // WebRTC added dynamically when client signals
        } catch (Exception e) { ErrorLogger.logError(ctx, "TransportManager_init", e); }
    }

    public static void startAll(Context ctx) {
        init(ctx);
        // If user switched to dedicated client protocol, prioritize Firestore/WebRTC
        if ("client".equals(activeTransport) || "firestore".equals(activeTransport)) {
            // Ensure Firestore transport started
            for (Transport t : transports) {
                if (t instanceof FirestoreTransport) {
                    if (NetworkStateMonitor.canAttemptHeartbeat(ctx)) t.start(ctx);
                }
            }
            // WebRTC start if client online
            try { WebRtcTransport.getInstance().startIfNeeded(ctx); } catch (Exception ignored) {}
            // Telegram is secondary, still gate but not primary
            if (NetworkStateMonitor.canAttemptHeartbeat(ctx)) {
                for (Transport t : transports) if (t instanceof TelegramTransport) t.start(ctx);
            }
        } else {
            // Auto mode: smart fallback chain gated
            if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) {
                // No validated net - don't start heavy poll, but keep Firestore listener if already validated? Already gated.
                return;
            }
            for (Transport t : transports) {
                try {
                    if (t.isHealthy(ctx)) t.start(ctx);
                } catch (Exception e) { ErrorLogger.logError(ctx, "Transport_start_" + t.name(), e); }
            }
            try { WebRtcTransport.getInstance().startIfNeeded(ctx); } catch (Exception ignored) {}
        }
    }

    public static void stopAll() {
        for (Transport t : transports) try { t.stop(); } catch (Exception ignored) {}
        try { WebRtcTransport.getInstance().stop(); } catch (Exception ignored) {}
    }

    public static void sendToActive(String msg, Context ctx) {
        // Try active transport first, then fallback chain gated
        if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) {
            // Queue for later? For now drop but log - don't waste battery retrying
            ErrorLogger.logError(ctx, "sendToActive_no_net", new Exception(msg.substring(0, Math.min(100, msg.length()))));
            return;
        }
        // 1. If client mode, try Firestore/WebRTC first
        if ("client".equals(activeTransport) || "firestore".equals(activeTransport) || "webrtc".equals(activeTransport)) {
            for (Transport t : transports) if (t instanceof FirestoreTransport && t.isHealthy(ctx)) { t.send(msg, ctx); return; }
            if (WebRtcTransport.getInstance().isHealthy(ctx)) { WebRtcTransport.getInstance().send(msg, ctx); return; }
        }
        // 2. Fallback chain by health
        for (Transport t : transports) {
            try { if (t.isHealthy(ctx)) { t.send(msg, ctx); return; } } catch (Exception ignored) {}
        }
        // 3. Last resort telegram even if degraded
        try { new TelegramTransport().send(msg, ctx); } catch (Exception e) { ErrorLogger.logError(ctx, "sendToActive_last", e); }
    }

    public static void switchToClientProtocol(Context ctx) {
        activeTransport = "client";
        ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).edit().putString("active_transport", "client").apply();
        // Signal via Firestore state doc
        try { new FirestoreTransport().signalSwitch(ctx, "client"); } catch (Exception ignored) {}
        startAll(ctx);
    }

    public static void switchToAuto(Context ctx) {
        activeTransport = "auto";
        ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE).edit().putString("active_transport", "auto").apply();
    }

    public static String getActiveTransport() { return activeTransport; }
}
