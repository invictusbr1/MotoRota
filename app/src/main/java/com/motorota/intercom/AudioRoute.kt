package com.motorota.intercom

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manda o áudio de voz (chamada e Jarvis ouvindo) para o intercomunicador do capacete
 * por Bluetooth, como numa ligação. Sem capacete conectado, usa o alto-falante do celular.
 */
class AudioRoute(ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var users = 0

    /** tem intercomunicador / fone Bluetooth com microfone conectado? */
    fun helmetConnected(): Boolean = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    fun acquire() {
        if (users++ > 0) return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devs = am.availableCommunicationDevices
                val target = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                    ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
                    ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (target != null) am.setCommunicationDevice(target)
            } else if (helmetConnected()) {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            } else {
                am.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            Log.w("MotoRota", "Intercom: rota de áudio: $e")
        }
    }

    @Suppress("DEPRECATION")
    fun release() {
        if (users == 0) return
        if (--users > 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w("MotoRota", "Intercom: soltar áudio: $e")
        }
    }
}
