package com.tankbriga.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates procedural audio waves using AudioTrack.
 * Zero assets required. Keep APK small.
 */
object AudioSynthesizer {
    private const val SAMPLE_RATE = 44100
    private val scope = CoroutineScope(Dispatchers.Default)

    fun playExplosion(damage: Int = 20) {
        scope.launch {
            val durationMs = 400
            val numSamples = (durationMs / 1000.0 * SAMPLE_RATE).toInt()
            val samples = ShortArray(numSamples)
            
            // Pitch proporcional ao dano (mais dano = mais grave/lento)
            val pitchMod = (1.0 - (damage.coerceIn(0, 100) / 100.0) * 0.5).toFloat()
            var filterState = 0f
            
            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val noise = Random.nextFloat() * 2f - 1f
                
                // Passa-baixa simples
                filterState += (noise - filterState) * (0.1f * pitchMod)
                
                val amplitude = (1.0 - progress) * 28000
                samples[i] = (amplitude * filterState).toInt().toShort()
            }
            playSamples(samples)
        }
    }

    fun playShot() {
        scope.launch {
            val durationMs = 80
            val numSamples = (durationMs / 1000.0 * SAMPLE_RATE).toInt()
            val samples = ShortArray(numSamples)
            
            var filterState = 0f
            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val noise = Random.nextFloat() * 2f - 1f
                
                // Envelope ADSR muito curto
                val env = if (progress < 0.1) progress / 0.1 else (1.0 - (progress - 0.1) / 0.9)
                
                // Filtro passa-banda simples para dar um 'pop'
                filterState += (noise - filterState) * 0.5f
                
                samples[i] = (env * 25000 * filterState).toInt().toShort()
            }
            playSamples(samples)
        }
    }

    fun playTankHit() {
        scope.launch { playTone(440.0, 440.0, 60, "SINE", true) }
    }

    fun playDeath() {
        scope.launch {
            val durationMs = 800
            val numSamples = (durationMs / 1000.0 * SAMPLE_RATE).toInt()
            val samples = ShortArray(numSamples)
            
            var phase1 = 0.0
            var phase2 = 0.0
            var filterState = 0f
            
            for (i in 0 until numSamples) {
                val progress = i.toDouble() / numSamples
                val freq1 = 400.0 - progress * 300.0
                val freq2 = 300.0 - progress * 200.0
                
                val noise = Random.nextFloat() * 2f - 1f
                filterState += (noise - filterState) * 0.05f
                
                val sig = (sin(phase1) + sin(phase2) + filterState * 1.5) / 3.0
                
                val amplitude = (1.0 - progress) * 30000
                samples[i] = (amplitude * sig).toInt().toShort()
                
                phase1 += 2.0 * Math.PI * freq1 / SAMPLE_RATE
                phase2 += 2.0 * Math.PI * freq2 / SAMPLE_RATE
            }
            playSamples(samples)
        }
    }

    fun playTurnStart() {
        scope.launch { playTone(880.0, 1000.0, 100, waveType = "SINE", decay = false) }
    }

    fun playMiss() {
        scope.launch { playTone(220.0, 110.0, 500, waveType = "SAWTOOTH", decay = true) }
    }

    fun playTick() {
        scope.launch { playTone(2000.0, 1800.0, 20, waveType = "SINE", decay = true, volume = 0.3f) }
    }

    fun playCharge(power: Float) {
        scope.launch {
            val freq = 200.0 + (power * 6.0)
            playTone(freq, freq + 20.0, 50, waveType = "SINE", decay = false, volume = 0.4f)
        }
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
        playSamples(samples)
    }
    
    private fun playSamples(samples: ShortArray) {
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
            
            val releaseTime = (samples.size.toDouble() / SAMPLE_RATE * 1000.0).toLong() + 50L
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
