package com.chimera;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.content.ContextCompat;

/**
 * Battery whitelist + auto-revoke + OEM detection.
 * Android 15/16: isIgnoringBatteryOptimizations() is still the stock check,
 * but OEM killers need separate handling via OemHelper.
 */
public class BatteryOptimizationHelper {

    public static boolean isWhitelisted(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            return pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) { return false; }
    }

    public static void requestWhitelist(Context ctx) {
        try {
            if (isWhitelisted(ctx)) return;
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Must check resolve to avoid crash on some OEMs
            if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                ctx.startActivity(intent);
            } else {
                // Fallback to list
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(fallback);
            }
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(fallback);
            } catch (Exception e2) { ErrorLogger.logError(ctx, "BatteryWhitelist", e2); }
        }
    }

    public static void ensureAutoRevokeWhitelisted(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Prevent Android 11+ auto-reset permissions after 3 months unused
                // This requires API 30+ and is best-effort
                if (ctx.getPackageManager().isAutoRevokeWhitelisted()) return;
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + ctx.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // Can't directly whitelist, prompt user via settings - accessibility can auto-click if needed
                // For now just log, MainActivity will handle via intent
            }
        } catch (Exception e) { ErrorLogger.logError(ctx, "AutoRevoke", e); }
    }
}
