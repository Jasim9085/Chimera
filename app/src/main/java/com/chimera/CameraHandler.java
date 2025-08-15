package com.chimera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.core.app.ActivityCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraHandler {

    public interface CameraCallback {
        void onPictureTaken(String filePath);
        void onError(String error);
    }

    public static void takePicture(Context context, String desiredCameraId, CameraCallback callback) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Camera permission not granted.");
            return;
        }

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length == 0) {
                callback.onError("No cameras found.");
                return;
            }

            String cameraId = cameraIds[0]; // Default to back camera
            if ("CAM1".equals(desiredCameraId) && cameraIds.length > 1) {
                cameraId = cameraIds[1]; // Use second camera if present (typically front)
            } else if ("CAM2".equals(desiredCameraId)) {
                cameraId = cameraIds[0]; // Explicitly back camera
            }

            HandlerThread handlerThread = new HandlerThread("CameraBackground");
            handlerThread.start();
            Handler backgroundHandler = new Handler(handlerThread.getLooper());

            ImageReader imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    // Set the listener for when the image is ready
                    imageReader.setOnImageAvailableListener(reader -> {
                        Image image = null;
                        try {
                            image = reader.acquireLatestImage();
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);

                            File outputFile = new File(context.getExternalFilesDir(null), "cam_capture.jpg");
                            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                                output.write(bytes);
                            }
                            callback.onPictureTaken(outputFile.getAbsolutePath());
                        } catch (Exception e) {
                            callback.onError(e.getMessage());
                        } finally {
                            if (image != null) image.close();
                            // FIXED: Close the camera device after taking the picture
                            camera.close();
                            handlerThread.quitSafely();
                        }
                    }, backgroundHandler);

                    // Proceed to create the capture session
                    try {
                        CaptureRequest.Builder captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        captureBuilder.addTarget(imageReader.getSurface());
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90);

                        camera.createCaptureSession(Collections.singletonList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                try {
                                    session.capture(captureBuilder.build(), null, backgroundHandler);
                                } catch (CameraAccessException e) {
                                    callback.onError(e.getMessage());
                                    camera.close();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                callback.onError("Camera configuration failed.");
                                camera.close();
                            }
                        }, backgroundHandler);
                    } catch (CameraAccessException e) {
                        callback.onError(e.getMessage());
                        camera.close();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    handlerThread.quitSafely();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    callback.onError("Camera device error: " + error);
                    camera.close();
                    handlerThread.quitSafely();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }
}