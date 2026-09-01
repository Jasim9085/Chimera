package com.chimera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ConnectivityChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            // Smart gating: only resurrect if network usable
            NetworkStateMonitor.init(context);
            if (NetworkStateMonitor.canAttemptHeartbeat(context)) {
                ResurrectionHelper.resurrect(context);
            }
        } catch (Exception e) { ErrorLogger.logError(context, "ConnectivityChangeReceiver", e); }
    }
}
