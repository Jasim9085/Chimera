package com.chimera;

import android.content.Context;
import android.content.SharedPreferences;

public class ConfigLoader {
    private static String botToken = null;
    private static long adminId = 0;

    public static void load(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("chimera_prefs", Context.MODE_PRIVATE);
            botToken = prefs.getString("bot_token", null);
            adminId = prefs.getLong("admin_id", 0);
        } catch (Exception e) {
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