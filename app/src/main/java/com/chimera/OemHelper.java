package com.chimera;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.Arrays;
import java.util.List;

/**
 * OEM-specific autostart / battery killer whitelisting.
 * Based on dontkillmyapp.com + community intents.
 * Best-effort, wraps all in try/catch, tries ordered list per OEM.
 * Returns true if an activity was launched.
 */
public class OemHelper {

    public static boolean openOemSettings(Context ctx) {
        String man = Build.MANUFACTURER.toLowerCase();
        String brand = Build.BRAND.toLowerCase();
        List<Intent> candidates;

        if (man.contains("xiaomi") && !Build.MODEL.toLowerCase().contains("mi a")) {
            candidates = Arrays.asList(
                intent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                intent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                intent("com.miui.securitycenter", "com.miui.permcenter.powercenter.PowerCenterActivity")
            );
        } else if (man.contains("huawei") || man.contains("honor") || brand.contains("huawei") || brand.contains("honor")) {
            candidates = Arrays.asList(
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                intent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                intent("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            );
        } else if (man.contains("oppo") || man.contains("realme")) {
            candidates = Arrays.asList(
                intent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                intent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                intent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                intent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")
            );
        } else if (man.contains("vivo")) {
            candidates = Arrays.asList(
                intent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                intent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                intent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            );
        } else if (man.contains("samsung")) {
            candidates = Arrays.asList(
                intent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                intent("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            );
        } else if (man.contains("oneplus")) {
            candidates = Arrays.asList(
                intent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            );
        } else if (man.contains("asus")) {
            candidates = Arrays.asList(
                intent("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
                intent("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
            );
        } else if (man.contains("nokia")) {
            candidates = Arrays.asList(
                intent("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")
            );
        } else if (man.contains("letv")) {
            candidates = Arrays.asList(
                intent("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
            );
        } else {
            candidates = Arrays.asList();
        }

        for (Intent in : candidates) {
            try {
                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (in.resolveActivity(ctx.getPackageManager()) != null) {
                    ctx.startActivity(in);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        // Fallback to generic battery optimization settings
        try {
            Intent fallback = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(fallback);
            return true;
        } catch (Exception e) { ErrorLogger.logError(ctx, "OemHelper_fallback", e); }
        try {
            Intent appDetails = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            appDetails.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
            appDetails.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(appDetails);
            return true;
        } catch (Exception e) { ErrorLogger.logError(ctx, "OemHelper_appDetails", e); }
        return false;
    }

    private static Intent intent(String pkg, String cls) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, cls));
        return i;
    }

    public static String getManufacturerLabel() {
        return Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
    }
}
