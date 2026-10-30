package com.example.ai

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log

/**
 * FridayDeviceController: A to Z Hardware and System Controller for Boss Anand.
 *
 * Automates:
 * - Flashlight (Torch) toggle
 * - Volume manipulation (Max, Mute, Percentage)
 * - Quick Settings access (Wi-Fi, Bluetooth)
 * - Camera trigger
 */
object FridayDeviceController {

    private const val TAG = "DeviceController"
    private var isTorchOn = false

    /**
     * Controls device flashlight (torch).
     */
    fun setFlashlight(context: Context, enable: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            isTorchOn = enable
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting flashlight: ${e.message}")
            false
        }
    }

    fun toggleFlashlight(context: Context): Boolean {
        return setFlashlight(context, !isTorchOn)
    }

    /**
     * Controls audio media volume.
     * @param level: 0 (mute), 50 (half), 100 (full)
     */
    fun setVolume(context: Context, levelPercent: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = ((levelPercent.coerceIn(0, 100) / 100f) * maxVolume).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume: ${e.message}")
            false
        }
    }

    /**
     * Launches Camera app to capture photo.
     */
    fun openCamera(context: Context): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            val generalCamera = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            try {
                context.startActivity(generalCamera)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Opens system Wi-Fi settings.
     */
    fun openWifiSettings(context: Context) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Opens system Bluetooth settings.
     */
    fun openBluetoothSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
