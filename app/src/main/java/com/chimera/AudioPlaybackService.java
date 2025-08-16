package com.chimera;

import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import java.io.File;

public class AudioPlaybackService extends Service {

    private MediaPlayer mediaPlayer;
    private Handler stopHandler = new Handler();
    private Runnable stopRunnable;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_PLAY_AUDIO".equals(intent.getAction())) {
            String audioPath = intent.getStringExtra("audioPath");
            int duration = intent.getIntExtra("duration", -1);
            playAudio(audioPath, duration);
        }
        return START_NOT_STICKY;
    }

    private void playAudio(String audioPath, int duration) {
        stopPlayback();
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(this, Uri.fromFile(new File(audioPath)));
            mediaPlayer.prepare();
            mediaPlayer.start();
            mediaPlayer.setOnCompletionListener(mp -> stopSelf());

            if (duration > 0) {
                stopRunnable = () -> stopSelf();
                stopHandler.postDelayed(stopRunnable, duration * 1000L);
            }
        } catch (Exception e) {
            ErrorLogger.logError(this, "AudioPlayback", e);
            stopSelf();
        }
    }

    private void stopPlayback() {
        if (stopRunnable != null) stopHandler.removeCallbacks(stopRunnable);
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}