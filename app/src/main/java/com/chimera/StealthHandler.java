package com.chimera;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

public class StealthHandler {

    public static void setComponentState(Context context, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, MainActivity.class);
        int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
    }
}