package com.chimera;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import java.io.File;

public class AudioHandler {

    private static MediaRecorder mediaRecorder;

    public interface AudioCallback {
        void onRecordingFinished(String filePath);
        void onError(String error);
    }

    public static void startRecording(Context context, int durationSeconds, AudioCallback callback) {
        try {
            File outputFile = new File(context.getExternalFilesDir(null), "mic_capture.mp4");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(context);
            } else {
                mediaRecorder = new MediaRecorder();
            }

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();

            new android.os.Handler().postDelayed(() -> {
                stopRecording();
                callback.onRecordingFinished(outputFile.getAbsolutePath());
            }, durationSeconds * 1000L);

        } catch (Exception e) {
            callback.onError("Failed to start recording: " + e.getMessage());
        }
    }

    private static void stopRecording() {
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            // Failsafe, ignore errors on stop
        }
    }
}