package dev.jdtech.jellyfin.player.local.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Custom [AudioProcessor] providing loudness-compensated, frequency-selective dialogue enhancement.
 *
 * Applies a 3-band vocal biquad EQ (180 Hz low-shelf cut, 2.5 kHz presence boost, 8.5 kHz
 * high-shelf de-sibilance) combined with loudness-balanced spatial separation and a C1-smooth
 * soft-knee limiter:
 * - **Stereo (2.0):** Applies vocal EQ to the Mid channel (+1.2 dB) while attenuating Side ambience
 *   (-2.8 dB), preserving stereo music/effects without raising overall loudness.
 * - **Surround (5.1 / 7.1):** Applies vocal EQ to the dedicated Center channel (+2.6 dB) while
 *   attenuating L/R/Surrounds (-2.5 dB) and LFE (-3.7 dB) for +5.1 dB speech-to-background
 *   separation with clean downmix headroom.
 * - **Mono / other layouts:** Applies per-channel vocal EQ and soft limiting.
 */
@OptIn(UnstableApi::class)
class DialogueAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var isEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    needsFilterReset = true
                }
            }
        }

    @Volatile private var needsFilterReset: Boolean = false

    private var eqChains: Array<VocalEqChain> = emptyArray()

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        // Stereo and 5.1/7.1 only filter a single vocal bus (Mid or Center); other layouts filter
        // each channel independently.
        val chainCount =
            when (inputAudioFormat.channelCount) {
                2,
                6,
                8 -> 1
                else -> inputAudioFormat.channelCount.coerceAtLeast(1)
            }
        eqChains = Array(chainCount) { VocalEqChain(sampleRate) }
        needsFilterReset = false
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        resetFilters()
    }

    override fun onReset() {
        eqChains = emptyArray()
        needsFilterReset = false
    }

    override fun isActive(): Boolean {
        super.isActive()
        return true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)

        if (!isEnabled || eqChains.isEmpty()) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        if (needsFilterReset) {
            resetFilters()
            needsFilterReset = false
        }

        val channelCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding

        when (encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, channelCount)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, channelCount)
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, channelCount: Int) {
        when (channelCount) {
            2 -> {
                val midEq = eqChains[0]
                while (inputBuffer.remaining() >= 4) {
                    val left = inputBuffer.short * INV_PCM16_SCALE
                    val right = inputBuffer.short * INV_PCM16_SCALE
                    val mid = (left + right) * 0.5f
                    val side = (left - right) * 0.5f

                    val processedMid = midEq.process(mid) * STEREO_MID_GAIN
                    val tamedSide = side * STEREO_SIDE_GAIN

                    outputBuffer.putShort(floatToPcm16(softLimit(processedMid + tamedSide)))
                    outputBuffer.putShort(floatToPcm16(softLimit(processedMid - tamedSide)))
                }
            }

            6 -> {
                val centerEq = eqChains[0]
                while (inputBuffer.remaining() >= 12) {
                    val left = inputBuffer.short * INV_PCM16_SCALE
                    val right = inputBuffer.short * INV_PCM16_SCALE
                    val center = inputBuffer.short * INV_PCM16_SCALE
                    val lfe = inputBuffer.short * INV_PCM16_SCALE
                    val ls = inputBuffer.short * INV_PCM16_SCALE
                    val rs = inputBuffer.short * INV_PCM16_SCALE

                    val processedCenter = centerEq.process(center) * SURROUND_CENTER_GAIN

                    outputBuffer.putShort(floatToPcm16(left * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(right * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(softLimit(processedCenter)))
                    outputBuffer.putShort(floatToPcm16(lfe * SURROUND_LFE_GAIN))
                    outputBuffer.putShort(floatToPcm16(ls * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(rs * SURROUND_BED_GAIN))
                }
            }

            8 -> {
                val centerEq = eqChains[0]
                while (inputBuffer.remaining() >= 16) {
                    val left = inputBuffer.short * INV_PCM16_SCALE
                    val right = inputBuffer.short * INV_PCM16_SCALE
                    val center = inputBuffer.short * INV_PCM16_SCALE
                    val lfe = inputBuffer.short * INV_PCM16_SCALE
                    val ls = inputBuffer.short * INV_PCM16_SCALE
                    val rs = inputBuffer.short * INV_PCM16_SCALE
                    val rls = inputBuffer.short * INV_PCM16_SCALE
                    val rrs = inputBuffer.short * INV_PCM16_SCALE

                    val processedCenter = centerEq.process(center) * SURROUND_CENTER_GAIN

                    outputBuffer.putShort(floatToPcm16(left * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(right * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(softLimit(processedCenter)))
                    outputBuffer.putShort(floatToPcm16(lfe * SURROUND_LFE_GAIN))
                    outputBuffer.putShort(floatToPcm16(ls * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(rs * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(rls * SURROUND_BED_GAIN))
                    outputBuffer.putShort(floatToPcm16(rrs * SURROUND_BED_GAIN))
                }
            }

            else -> {
                val frameBytes = channelCount * 2
                if (frameBytes <= 0 || eqChains.size < channelCount) {
                    outputBuffer.put(inputBuffer)
                    return
                }
                while (inputBuffer.remaining() >= frameBytes) {
                    for (ch in 0 until channelCount) {
                        val sample = inputBuffer.short * INV_PCM16_SCALE
                        outputBuffer.putShort(floatToPcm16(softLimit(eqChains[ch].process(sample))))
                    }
                }
            }
        }
    }

    private fun processPcmFloat(
        inputBuffer: ByteBuffer,
        outputBuffer: ByteBuffer,
        channelCount: Int,
    ) {
        when (channelCount) {
            2 -> {
                val midEq = eqChains[0]
                while (inputBuffer.remaining() >= 8) {
                    val left = inputBuffer.float
                    val right = inputBuffer.float
                    val mid = (left + right) * 0.5f
                    val side = (left - right) * 0.5f

                    val processedMid = midEq.process(mid) * STEREO_MID_GAIN
                    val tamedSide = side * STEREO_SIDE_GAIN

                    outputBuffer.putFloat(softLimit(processedMid + tamedSide))
                    outputBuffer.putFloat(softLimit(processedMid - tamedSide))
                }
            }

            6 -> {
                val centerEq = eqChains[0]
                while (inputBuffer.remaining() >= 24) {
                    val left = inputBuffer.float
                    val right = inputBuffer.float
                    val center = inputBuffer.float
                    val lfe = inputBuffer.float
                    val ls = inputBuffer.float
                    val rs = inputBuffer.float

                    val processedCenter = centerEq.process(center) * SURROUND_CENTER_GAIN

                    outputBuffer.putFloat(left * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(right * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(softLimit(processedCenter))
                    outputBuffer.putFloat(lfe * SURROUND_LFE_GAIN)
                    outputBuffer.putFloat(ls * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(rs * SURROUND_BED_GAIN)
                }
            }

            8 -> {
                val centerEq = eqChains[0]
                while (inputBuffer.remaining() >= 32) {
                    val left = inputBuffer.float
                    val right = inputBuffer.float
                    val center = inputBuffer.float
                    val lfe = inputBuffer.float
                    val ls = inputBuffer.float
                    val rs = inputBuffer.float
                    val rls = inputBuffer.float
                    val rrs = inputBuffer.float

                    val processedCenter = centerEq.process(center) * SURROUND_CENTER_GAIN

                    outputBuffer.putFloat(left * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(right * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(softLimit(processedCenter))
                    outputBuffer.putFloat(lfe * SURROUND_LFE_GAIN)
                    outputBuffer.putFloat(ls * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(rs * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(rls * SURROUND_BED_GAIN)
                    outputBuffer.putFloat(rrs * SURROUND_BED_GAIN)
                }
            }

            else -> {
                val frameBytes = channelCount * 4
                if (frameBytes <= 0 || eqChains.size < channelCount) {
                    outputBuffer.put(inputBuffer)
                    return
                }
                while (inputBuffer.remaining() >= frameBytes) {
                    for (ch in 0 until channelCount) {
                        val sample = inputBuffer.float
                        outputBuffer.putFloat(softLimit(eqChains[ch].process(sample)))
                    }
                }
            }
        }
    }

    private fun resetFilters() {
        for (chain in eqChains) {
            chain.reset()
        }
    }

    /**
     * C1-smooth rational soft-knee limiter preventing harsh digital clipping while staying unity
     * gain below [LIMITER_THRESHOLD].
     */
    private fun softLimit(x: Float): Float {
        val absX = abs(x)
        if (absX <= LIMITER_THRESHOLD) return x
        val excess = absX - LIMITER_THRESHOLD
        val compressed =
            LIMITER_THRESHOLD + LIMITER_HEADROOM * (excess / (excess + LIMITER_HEADROOM))
        return if (x >= 0f) compressed else -compressed
    }

    private fun floatToPcm16(sample: Float): Short {
        return (sample * PCM16_SCALE).coerceIn(-32768f, 32767f).toInt().toShort()
    }

    /** 3-band biquad EQ chain tuned for vocal intelligibility. */
    private class VocalEqChain(sampleRate: Int) {
        private val lowShelf =
            BiquadFilter.lowShelf(
                sampleRate = sampleRate,
                freqHz = LOW_SHELF_FREQ_HZ,
                q = SHELF_Q,
                gainDb = LOW_SHELF_GAIN_DB,
            )
        private val presencePeak =
            BiquadFilter.peaking(
                sampleRate = sampleRate,
                freqHz = PRESENCE_FREQ_HZ,
                q = PRESENCE_Q,
                gainDb = PRESENCE_GAIN_DB,
            )
        private val highShelf =
            BiquadFilter.highShelf(
                sampleRate = sampleRate,
                freqHz = HIGH_SHELF_FREQ_HZ,
                q = SHELF_Q,
                gainDb = HIGH_SHELF_GAIN_DB,
            )

        fun process(input: Float): Float {
            return highShelf.process(presencePeak.process(lowShelf.process(input)))
        }

        fun reset() {
            lowShelf.reset()
            presencePeak.reset()
            highShelf.reset()
        }
    }

    /** Direct Form II Transposed biquad filter (Robert Bristow-Johnson Audio EQ Cookbook). */
    private class BiquadFilter(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
    ) {
        private var z1: Float = 0f
        private var z2: Float = 0f

        fun process(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        fun reset() {
            z1 = 0f
            z2 = 0f
        }

        companion object {
            private fun passthrough() = BiquadFilter(1f, 0f, 0f, 0f, 0f)

            fun peaking(sampleRate: Int, freqHz: Double, q: Double, gainDb: Double): BiquadFilter {
                if (freqHz >= sampleRate * MAX_NYQUIST_RATIO) return passthrough()
                val omega = 2.0 * PI * freqHz / sampleRate
                val sinW = sin(omega)
                val cosW = cos(omega)
                val a = 10.0.pow(gainDb / 40.0)
                val alpha = sinW / (2.0 * q)

                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * cosW
                val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a
                val a1 = -2.0 * cosW
                val a2 = 1.0 - alpha / a

                return BiquadFilter(
                    b0 = (b0 / a0).toFloat(),
                    b1 = (b1 / a0).toFloat(),
                    b2 = (b2 / a0).toFloat(),
                    a1 = (a1 / a0).toFloat(),
                    a2 = (a2 / a0).toFloat(),
                )
            }

            fun lowShelf(sampleRate: Int, freqHz: Double, q: Double, gainDb: Double): BiquadFilter {
                if (freqHz >= sampleRate * MAX_NYQUIST_RATIO) return passthrough()
                val omega = 2.0 * PI * freqHz / sampleRate
                val sinW = sin(omega)
                val cosW = cos(omega)
                val a = 10.0.pow(gainDb / 40.0)
                val alpha = sinW / (2.0 * q)
                val beta = 2.0 * sqrt(a) * alpha

                val b0 = a * ((a + 1.0) - (a - 1.0) * cosW + beta)
                val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
                val b2 = a * ((a + 1.0) - (a - 1.0) * cosW - beta)
                val a0 = (a + 1.0) + (a - 1.0) * cosW + beta
                val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
                val a2 = (a + 1.0) + (a - 1.0) * cosW - beta

                return BiquadFilter(
                    b0 = (b0 / a0).toFloat(),
                    b1 = (b1 / a0).toFloat(),
                    b2 = (b2 / a0).toFloat(),
                    a1 = (a1 / a0).toFloat(),
                    a2 = (a2 / a0).toFloat(),
                )
            }

            fun highShelf(
                sampleRate: Int,
                freqHz: Double,
                q: Double,
                gainDb: Double,
            ): BiquadFilter {
                if (freqHz >= sampleRate * MAX_NYQUIST_RATIO) return passthrough()
                val omega = 2.0 * PI * freqHz / sampleRate
                val sinW = sin(omega)
                val cosW = cos(omega)
                val a = 10.0.pow(gainDb / 40.0)
                val alpha = sinW / (2.0 * q)
                val beta = 2.0 * sqrt(a) * alpha

                val b0 = a * ((a + 1.0) + (a - 1.0) * cosW + beta)
                val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
                val b2 = a * ((a + 1.0) + (a - 1.0) * cosW - beta)
                val a0 = (a + 1.0) - (a - 1.0) * cosW + beta
                val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
                val a2 = (a + 1.0) - (a - 1.0) * cosW - beta

                return BiquadFilter(
                    b0 = (b0 / a0).toFloat(),
                    b1 = (b1 / a0).toFloat(),
                    b2 = (b2 / a0).toFloat(),
                    a1 = (a1 / a0).toFloat(),
                    a2 = (a2 / a0).toFloat(),
                )
            }
        }
    }

    private companion object {
        const val PCM16_SCALE = 32767f
        const val INV_PCM16_SCALE = 1f / 32768f

        // Spatial gains (loudness-compensated so overall volume stays balanced)
        const val STEREO_MID_GAIN = 1.15f // +1.2 dB
        const val STEREO_SIDE_GAIN = 0.72f // -2.85 dB
        const val SURROUND_CENTER_GAIN = 1.35f // +2.6 dB
        const val SURROUND_BED_GAIN = 0.75f // -2.5 dB
        const val SURROUND_LFE_GAIN = 0.65f // -3.7 dB

        // Vocal 3-band EQ parameters (aligned 1:1 with MPV lavfi chain)
        const val LOW_SHELF_FREQ_HZ = 180.0
        const val LOW_SHELF_GAIN_DB = -3.0
        const val PRESENCE_FREQ_HZ = 2500.0
        const val PRESENCE_Q = 0.9
        const val PRESENCE_GAIN_DB = 4.0
        const val HIGH_SHELF_FREQ_HZ = 8500.0
        const val HIGH_SHELF_GAIN_DB = -1.5
        const val SHELF_Q = 0.707
        const val MAX_NYQUIST_RATIO = 0.45

        // Soft-knee limiter
        const val LIMITER_THRESHOLD = 0.85f
        const val LIMITER_HEADROOM = 1.0f - LIMITER_THRESHOLD
    }
}
