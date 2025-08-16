package com.chimera;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private ImageView overlayView;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_SHOW_IMAGE".equals(intent.getAction())) {
            String imagePath = intent.getStringExtra("imagePath");
            showImage(imagePath);
        }
        return START_STICKY;
    }

    private void showImage(String imagePath) {
        if (overlayView != null) {
            windowManager.removeView(overlayView);
        }
        overlayView = new ImageView(this);
        Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
        overlayView.setImageBitmap(bitmap);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.CENTER;
        windowManager.addView(overlayView, params);

        new Handler().postDelayed(this::removeOverlay, 10000);
    }

    private void removeOverlay() {
        if (overlayView != null && overlayView.getWindowToken() != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeOverlay();
    }
}