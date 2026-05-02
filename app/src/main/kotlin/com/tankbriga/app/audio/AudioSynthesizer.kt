package com.tankbriga.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sin

/**
 * Generates procedural audio waves using AudioTrack.
 * Zero assets required. Keep APK small.
 */
object AudioSynthesizer {
    private const val SAMPLE_RATE = 44100

    /** Plays a procedural "Whomp" sound for general explosions. */
    fun playExplosion() {
        playTone(100.0, 40.0, 350, waveType = "SINE", decay = true)
    }

    /** Plays a metallic "Click" for shots. */
    fun playShot() {
        playTone(500.0, 300.0, 60, waveType = "SQUARE", decay = true)
    }

    /** Plays a high-pitched "Ping" for hits on other tanks. */
    fun playTankHit() {
        playTone(1200.0, 800.0, 150, waveType = "SINE", decay = true)
    }

    /** Plays a dramatic descending tone for player death. */
    fun playDeath() {
        playTone(400.0, 50.0, 800, waveType = "SAWTOOTH", decay = true)
    }

    /** Plays a high-pitched notification for turn start. */
    fun playTurnStart() {
        playTone(880.0, 1000.0, 100, waveType = "SINE", decay = false)
    }

    /** Plays a gritty, audible "Fail" sound for misses. */
    fun playMiss() {
        playTone(220.0, 110.0, 500, waveType = "SAWTOOTH", decay = true)
    }

    /** Plays a subtle 'Tick' for UI/Angle adjustments. */
    fun playTick() {
        playTone(2000.0, 1800.0, 20, waveType = "SINE", decay = true, volume = 0.3f)
    }

    /** Plays a rising pitch tone for charging power. */
    fun playCharge(power: Float) {
        // Map 0-100 power to 200Hz - 800Hz
        val freq = 200.0 + (power * 6.0)
        playTone(freq, freq + 20.0, 50, waveType = "SINE", decay = false, volume = 0.4f)
    }

    private fun playTone(
        startFreq: Double, 
        endFreq: Double, 
        durationMs: Int, 
        waveType: String = "SINE", 
        decay: Boolean = true,
        volume: Float = 1.0f
    ) {
        val numSamples = (durationMs / 1000.0 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val samples = ShortArray(numSamples)
        
        var phase = 0.0
        for (i in 0 until numSamples) {
            val progress = i.toDouble() / numSamples
            val freq = startFreq + (endFreq - startFreq) * progress
            
            val signal = when(waveType) {
                "SQUARE" -> if (sin(phase) > 0) 1.0 else -1.0
                "SAWTOOTH" -> {
                    val p = (phase % (2.0 * Math.PI)) / (2.0 * Math.PI)
                    2.0 * p - 1.0
                }
                else -> sin(phase) // SINE
            }
            
            val amplitude = if (decay) (1.0 - progress) * 28000 * volume else 22000.0 * volume
            samples[i] = (amplitude * signal).toInt().toShort()
            
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE
        }

        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(samples, 0, samples.size)
            audioTrack.play()
            
            val releaseTime = durationMs + 50L
            Thread {
                try {
                    Thread.sleep(releaseTime)
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {}
    }
}
