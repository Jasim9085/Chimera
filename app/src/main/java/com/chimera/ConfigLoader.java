package com.chimera;

import android.content.Context;
import java.io.InputStream;
import org.json.JSONObject;

public class ConfigLoader {
    private static String botToken = null;
    private static long adminId = 0;

    public static void load(Context context) {
        try {
            InputStream is = context.getAssets().open("tg_config.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, "UTF-8");
            JSONObject obj = new JSONObject(json);
            botToken = obj.getString("bot_token");
            adminId = obj.getLong("admin_id");
        } catch (Exception e) {
            // This is a critical error, log it.
            ErrorLogger.logError(context, "ConfigLoader", e);
        }
    }

    public static String getBotToken() {
        return botToken;
    }

    public static long getAdminId() {
        return adminId;
    }
}