package com.jum3a.livetranslate.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
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
import android.os.Handler
import android.os.Looper
import com.jum3a.livetranslate.settings.MicSource

class MicConfig(
    val source: MicSource,
    /** Winner of the microphone test, tried first in AUTO mode. */
    val testedSource: MicSource?,
    /** 0 = automatic device choice. */
    val deviceId: Int,
    val phoneProcessing: Boolean,
)

class OpenedMic(
    val record: AudioRecord,
    val sampleRate: Int,
    val source: MicSource,
    /** Id of the device we asked for, 0 if Android picks. */
    val deviceId: Int,
    val description: String,
    private val effects: List<AudioEffect>,
) {
    fun release() {
        effects.forEach { runCatching { it.release() } }
        runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        runCatching { record.release() }
    }
}

object AudioCapture {
    /** Whisper works at 16 kHz mono; everything is converted to this rate. */
    const val SAMPLE_RATE = 16_000

    /** Tried in order. 16 kHz needs no conversion; the others cover phones/modes that refuse it. */
    private val RATES = intArrayOf(16_000, 48_000, 44_100, 32_000, 22_050)

    fun unprocessedSupported(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    /** Microphone modes to try, best first. Modes in [skip] already failed this session. */
    fun candidates(context: Context, config: MicConfig, skip: Set<MicSource> = emptySet()): List<MicSource> {
        val auto = buildList {
            config.testedSource?.takeIf { it != MicSource.AUTO }?.let { add(it) }
            if (unprocessedSupported(context)) add(MicSource.UNPROCESSED)
            add(MicSource.VOICE_RECOGNITION)
            add(MicSource.MIC)
            add(MicSource.CAMCORDER)
        }
        val ordered = if (config.source == MicSource.AUTO) auto else listOf(config.source) + auto
        return ordered.distinct().filterNot { it in skip }
    }

    /**
     * Opens the best working microphone for [config]. Falls back through modes and sample rates,
     * so it works on phones that support only some of them.
     */
    fun open(context: Context, config: MicConfig, skip: Set<MicSource> = emptySet()): OpenedMic {
        val device = preferredDevice(context, config.deviceId)
        for (src in candidates(context, config, skip)) {
            tryOpen(context, src, device, config.phoneProcessing)?.let { return it }
        }
        throw IllegalStateException("Couldn't open the microphone. Another app may be using it.")
    }

    /** Opens exactly [src] (any sample rate), or returns null if this phone can't. Used by the mic test. */
    fun openExact(context: Context, src: MicSource, deviceId: Int, phoneProcessing: Boolean): OpenedMic? =
        tryOpen(context, src, preferredDevice(context, deviceId), phoneProcessing)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before any recording starts.
    private fun tryOpen(context: Context, src: MicSource, device: AudioDeviceInfo?, phoneProcessing: Boolean): OpenedMic? {
        val androidSource = androidSource(src)
        for (rate in RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            // Large buffer (≥1 s) so a brief hiccup on the phone never drops audio.
            val bufferBytes = maxOf(minBuf * 4, rate * 2)
            val rec = try {
                AudioRecord(androidSource, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            } catch (_: Throwable) {
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                continue
            }
            var deviceName = "phone mic"
            var deviceId = 0
            if (device != null && rec.setPreferredDevice(device)) {
                deviceName = describe(device)
                deviceId = device.id
            }
            val effects = configureEffects(rec.audioSessionId, phoneProcessing)
            val rateNote = if (rate != SAMPLE_RATE) " · ${rate / 1000} kHz" else ""
            return OpenedMic(rec, rate, src, deviceId, "${src.short} · $deviceName$rateNote", effects)
        }
        return null
    }

    private fun androidSource(src: MicSource): Int = when (src) {
        MicSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        MicSource.VOICE_RECOGNITION, MicSource.AUTO -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        MicSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        MicSource.MIC -> MediaRecorder.AudioSource.MIC
    }

    /**
     * The device to record from. A specific choice wins if it's still connected; otherwise a
     * plugged-in USB microphone (lavalier receivers, conference mics) is used automatically.
     */
    fun preferredDevice(context: Context, deviceId: Int): AudioDeviceInfo? {
        val inputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (deviceId != 0) inputs.firstOrNull { it.id == deviceId }?.let { return it }
        return inputs.firstOrNull { it.type in USB_TYPES }
    }

    /** Id of the device [open] would use right now (0 = Android's default). */
    fun preferredDeviceId(context: Context, deviceId: Int): Int = preferredDevice(context, deviceId)?.id ?: 0

    /**
     * Phone-side effects are OFF by default: we do our own gain, and Android's noise suppressor
     * tends to eat distant, reverberant loudspeaker speech.
     */
    private fun configureEffects(session: Int, phoneProcessing: Boolean): List<AudioEffect> {
        val list = mutableListOf<AudioEffect>()
        if (AutomaticGainControl.isAvailable()) {
            runCatching { AutomaticGainControl.create(session) }.getOrNull()?.let {
                runCatching { it.enabled = phoneProcessing }
                list += it
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(session) }.getOrNull()?.let {
                runCatching { it.enabled = phoneProcessing }
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

    /**
     * Inputs worth offering. Bluetooth headsets are left out on purpose: their mic is at the
     * listener's mouth, not near the speaker, and Bluetooth voice audio is low quality.
     */
    fun inputDevices(context: Context): List<Pair<Int, String>> =
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in USEFUL_TYPES }
            .map { it.id to describe(it) }

    fun describe(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset mic"
            in USB_TYPES -> d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
                ?.let { "USB mic ($it)" } ?: "USB mic"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "Line in"
            AudioDeviceInfo.TYPE_DOCK -> "Dock mic"
            else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Input ${d.type}"
        }
        // Some phones list each built-in mic separately ("bottom", "back").
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            d.address.orEmpty()
        } else {
            ""
        }
        return if (address.isNotBlank()) "$type ($address)" else type
    }

    /** Calls [onChange] when a microphone is plugged in or removed. Returns a function that stops watching. */
    fun watchDevices(context: Context, onChange: () -> Unit): () -> Unit {
        val am = context.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (added.any { it.isSource }) onChange()
            }

            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                if (removed.any { it.isSource }) onChange()
            }
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        return { runCatching { am.unregisterAudioDeviceCallback(cb) } }
    }

    private val USB_TYPES = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private val USEFUL_TYPES = USB_TYPES + setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_DOCK,
    )
}
