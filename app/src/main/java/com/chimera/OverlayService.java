package com.chimera;

import android.app.Service;
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
import android.widget.LinearLayout;

public class OverlayService extends Service {

    private WindowManager windowManager;
    private ImageView overlayView;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() { super.onCreate(); windowManager = (WindowManager) getSystemService(WINDOW_SERVICE); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_SHOW_IMAGE".equals(intent.getAction())) {
            String imagePath = intent.getStringExtra("imagePath");
            int duration = intent.getIntExtra("duration", 10);
            int scale = intent.getIntExtra("scale", 100);
            showImage(imagePath, duration, scale);
        }
        return START_NOT_STICKY;
    }

    private void showImage(String imagePath, int duration, int scale) {
        if (overlayView != null) windowManager.removeView(overlayView);
        
        Bitmap originalBitmap = BitmapFactory.decodeFile(imagePath);
        if (originalBitmap == null) return;
        
        int width = (int) (originalBitmap.getWidth() * (scale / 100.0));
        int height = (int) (originalBitmap.getHeight() * (scale / 100.0));
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true);

        overlayView = new ImageView(this);
        overlayView.setImageBitmap(scaledBitmap);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        
        windowManager.addView(overlayView, params);
        new Handler().postDelayed(this::removeOverlay, duration * 1000L);
    }

    private void removeOverlay() {
        if (overlayView != null && overlayView.getWindowToken() != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        stopSelf();
    }

    @Override public void onDestroy() { super.onDestroy(); removeOverlay(); }
}