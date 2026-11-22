package com.jum3a.livetranslate.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.jum3a.livetranslate.settings.MicSource

class MicConfig(
    val source: MicSource,
    val deviceId: Int,
    val phoneAgc: Boolean,
    val noiseSuppression: Boolean,
)

class OpenedMic(val record: AudioRecord, val description: String, private val effects: List<AudioEffect>) {
    fun release() {
        effects.forEach { runCatching { it.release() } }
        runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        runCatching { record.release() }
    }
}

object AudioCapture {
    /** Whisper works natively at 16 kHz mono, so we record at exactly that rate. */
    const val SAMPLE_RATE = 16_000

    fun unprocessedSupported(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    @SuppressLint("MissingPermission") // The activity requests RECORD_AUDIO before the service starts.
    fun open(context: Context, config: MicConfig): OpenedMic {
        val am = context.getSystemService(AudioManager::class.java)
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) error("This phone can't record at 16 kHz.")
        // Large buffer (≥1 s) so a brief hiccup on the phone never drops audio.
        val bufferBytes = maxOf(minBuf * 4, SAMPLE_RATE * 2)

        var lastError: Throwable? = null
        for ((source, name) in sourceCandidates(context, config.source)) {
            val rec = try {
                AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (t: Throwable) {
                lastError = t
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                continue
            }
            var deviceName = "phone mic"
            if (config.deviceId != 0) {
                am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.id == config.deviceId }
                    ?.let { dev -> if (rec.setPreferredDevice(dev)) deviceName = describe(dev) }
            }
            val effects = configureEffects(rec.audioSessionId, config)
            return OpenedMic(rec, "$name · $deviceName", effects)
        }
        throw IllegalStateException("Couldn't open the microphone. Another app may be using it.", lastError)
    }

    private fun sourceCandidates(context: Context, source: MicSource): List<Pair<Int, String>> {
        val raw = MediaRecorder.AudioSource.UNPROCESSED to "Raw mic"
        val speech = MediaRecorder.AudioSource.VOICE_RECOGNITION to "Speech mic"
        val far = MediaRecorder.AudioSource.CAMCORDER to "Far-field mic"
        val std = MediaRecorder.AudioSource.MIC to "Standard mic"
        val first = when (source) {
            MicSource.AUTO -> if (unprocessedSupported(context)) raw else speech
            MicSource.UNPROCESSED -> raw
            MicSource.VOICE_RECOGNITION -> speech
            MicSource.CAMCORDER -> far
            MicSource.MIC -> std
        }
        return listOf(first, speech, std).distinct()
    }

    /**
     * Phone-side effects are OFF by default: we do our own gain, and Android's noise suppressor
     * tends to eat distant, reverberant loudspeaker speech. The user can enable them in Settings.
     */
    private fun configureEffects(session: Int, config: MicConfig): List<AudioEffect> {
        val list = mutableListOf<AudioEffect>()
        if (AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(session) }.getOrNull()?.let {
                runCatching { it.enabled = config.phoneAgc }
                list += it
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(session) }.getOrNull()?.let {
                runCatching { it.enabled = config.noiseSuppression }
                list += it
            }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(session) }.getOrNull()?.let {
                runCatching { it.enabled = false }
                list += it
            }
        }
        return list
    }

    fun inputDevices(context: Context): List<Pair<Int, String>> =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter {
                it.type != AudioDeviceInfo.TYPE_TELEPHONY &&
                    it.type != AudioDeviceInfo.TYPE_FM_TUNER &&
                    it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX
            }
            .map { it.id to describe(it) }

    fun describe(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset mic"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth mic"
            else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Input ${d.type}"
        }
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) d.address.orEmpty() else ""
        return if (address.isNotBlank()) "$type ($address)" else type
    }
}
