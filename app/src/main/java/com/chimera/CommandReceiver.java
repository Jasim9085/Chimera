package com.chimera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class CommandReceiver extends BroadcastReceiver {
    private static final String TAG = "CommandReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            Log.d(TAG, "Command broadcast received. Forcing CoreService to start.");
            // This is a reliable way to start a service from the background,
            // even if the app process was killed.
            intent.setClass(context, CoreService.class);
            context.startService(intent);
        }
    }
}
