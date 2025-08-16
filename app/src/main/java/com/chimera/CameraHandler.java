package com.chimera;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
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

            String cameraId = null;
            for (String id : cameraIds) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (lensFacing == null) continue;

                if ("CAM1".equals(desiredCameraId) && lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    break;
                } else if ("CAM2".equals(desiredCameraId) && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }

            if (cameraId == null) {
                callback.onError("Could not find a matching camera for " + desiredCameraId);
                return;
            }

            final String finalCameraId = cameraId;
            HandlerThread handlerThread = new HandlerThread("CameraBackground");
            handlerThread.start();
            Handler backgroundHandler = new Handler(handlerThread.getLooper());

            ImageReader imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    try {
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
                                camera.close();
                                handlerThread.quitSafely();
                            }
                        }, backgroundHandler);

                        CaptureRequest.Builder captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        captureBuilder.addTarget(imageReader.getSurface());
                        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

                        // --- START OF THE FIX ---
                        // Get characteristics of the specific camera being used
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(finalCameraId);
                        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        
                        // Set the JPEG orientation dynamically based on the sensor's physical orientation.
                        // This corrects the upside-down issue on the front camera.
                        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
                        // --- END OF THE FIX ---

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