package dev.jdtech.jellyfin.player.local.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Custom [AudioProcessor] providing transparent, phase-coherent dialogue enhancement.
 *
 * For stereo audio (2 channels), boosts center-panned speech formants (Mid channel) by +4 dB while
 * gently taming side ambience by -2 dB. This avoids comb filtering and OEM phase cancellation.
 *
 * For surround sound (5.1 / 7.1), boosts the dedicated Center channel (channel index 2) by +6 dB
 * (2.0x) and attenuates boomy subwoofer LFE (channel index 3) by -2.5 dB (0.75x) to prevent
 * explosions from masking vocal intelligibility.
 */
@OptIn(UnstableApi::class)
class DialogueAudioProcessor : BaseAudioProcessor() {

    @Volatile var isEnabled: Boolean = false

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        if (
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean {
        super.isActive()
        return true
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)

        if (!isEnabled) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding

        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                when (channelCount) {
                    2 -> {
                        // Stereo: Mid/Side processing
                        while (inputBuffer.remaining() >= 4) {
                            val left = inputBuffer.short.toFloat()
                            val right = inputBuffer.short.toFloat()
                            val mid = (left + right) * 0.5f
                            val side = (left - right) * 0.5f

                            // Boost speech formants (+4 dB), gently tame stereo ambience (-2 dB)
                            val boostedMid = mid * 1.6f
                            val tamedSide = side * 0.8f

                            val outLeft =
                                (boostedMid + tamedSide).coerceIn(-32768f, 32767f).toInt().toShort()
                            val outRight =
                                (boostedMid - tamedSide).coerceIn(-32768f, 32767f).toInt().toShort()

                            outputBuffer.putShort(outLeft)
                            outputBuffer.putShort(outRight)
                        }
                    }

                    6 -> {
                        // 5.1 Surround: L (0), R (1), Center (2), LFE (3), Ls (4), Rs (5)
                        while (inputBuffer.remaining() >= 12) {
                            val left = inputBuffer.short
                            val right = inputBuffer.short
                            val center = inputBuffer.short.toFloat()
                            val lfe = inputBuffer.short.toFloat()
                            val ls = inputBuffer.short
                            val rs = inputBuffer.short

                            // Center channel contains 100% of dialogue: boost by +6 dB (2.0x)
                            val boostedCenter =
                                (center * 2.0f).coerceIn(-32768f, 32767f).toInt().toShort()
                            // Tame subwoofer rumble (0.75x) to eliminate booming explosions masking
                            // voices
                            val tamedLfe = (lfe * 0.75f).coerceIn(-32768f, 32767f).toInt().toShort()

                            outputBuffer.putShort(left)
                            outputBuffer.putShort(right)
                            outputBuffer.putShort(boostedCenter)
                            outputBuffer.putShort(tamedLfe)
                            outputBuffer.putShort(ls)
                            outputBuffer.putShort(rs)
                        }
                    }

                    8 -> {
                        // 7.1 Surround: L, R, C, LFE, Ls, Rs, Rls, Rrs
                        while (inputBuffer.remaining() >= 16) {
                            val left = inputBuffer.short
                            val right = inputBuffer.short
                            val center = inputBuffer.short.toFloat()
                            val lfe = inputBuffer.short.toFloat()
                            val ls = inputBuffer.short
                            val rs = inputBuffer.short
                            val rls = inputBuffer.short
                            val rrs = inputBuffer.short

                            val boostedCenter =
                                (center * 2.0f).coerceIn(-32768f, 32767f).toInt().toShort()
                            val tamedLfe = (lfe * 0.75f).coerceIn(-32768f, 32767f).toInt().toShort()

                            outputBuffer.putShort(left)
                            outputBuffer.putShort(right)
                            outputBuffer.putShort(boostedCenter)
                            outputBuffer.putShort(tamedLfe)
                            outputBuffer.putShort(ls)
                            outputBuffer.putShort(rs)
                            outputBuffer.putShort(rls)
                            outputBuffer.putShort(rrs)
                        }
                    }

                    else -> {
                        outputBuffer.put(inputBuffer)
                    }
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                when (channelCount) {
                    2 -> {
                        while (inputBuffer.remaining() >= 8) {
                            val left = inputBuffer.float
                            val right = inputBuffer.float
                            val mid = (left + right) * 0.5f
                            val side = (left - right) * 0.5f

                            val boostedMid = mid * 1.6f
                            val tamedSide = side * 0.8f

                            outputBuffer.putFloat((boostedMid + tamedSide).coerceIn(-1.0f, 1.0f))
                            outputBuffer.putFloat((boostedMid - tamedSide).coerceIn(-1.0f, 1.0f))
                        }
                    }

                    6 -> {
                        while (inputBuffer.remaining() >= 24) {
                            val left = inputBuffer.float
                            val right = inputBuffer.float
                            val center = inputBuffer.float
                            val lfe = inputBuffer.float
                            val ls = inputBuffer.float
                            val rs = inputBuffer.float

                            outputBuffer.putFloat(left)
                            outputBuffer.putFloat(right)
                            outputBuffer.putFloat((center * 2.0f).coerceIn(-1.0f, 1.0f))
                            outputBuffer.putFloat(lfe * 0.75f)
                            outputBuffer.putFloat(ls)
                            outputBuffer.putFloat(rs)
                        }
                    }

                    8 -> {
                        while (inputBuffer.remaining() >= 32) {
                            val left = inputBuffer.float
                            val right = inputBuffer.float
                            val center = inputBuffer.float
                            val lfe = inputBuffer.float
                            val ls = inputBuffer.float
                            val rs = inputBuffer.float
                            val rls = inputBuffer.float
                            val rrs = inputBuffer.float

                            outputBuffer.putFloat(left)
                            outputBuffer.putFloat(right)
                            outputBuffer.putFloat((center * 2.0f).coerceIn(-1.0f, 1.0f))
                            outputBuffer.putFloat(lfe * 0.75f)
                            outputBuffer.putFloat(ls)
                            outputBuffer.putFloat(rs)
                            outputBuffer.putFloat(rls)
                            outputBuffer.putFloat(rrs)
                        }
                    }

                    else -> {
                        outputBuffer.put(inputBuffer)
                    }
                }
            }
            else -> {
                outputBuffer.put(inputBuffer)
            }
        }

        outputBuffer.flip()
    }
}
