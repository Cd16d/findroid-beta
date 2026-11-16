package dev.jdtech.jellyfin.player.local.presentation

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream

/**
 * Offsets the playback timestamp fed to ExoPlayer's subtitle [Renderer] so that subtitles are
 * shifted earlier or later without restarting playback or rebuffering.
 */
@OptIn(UnstableApi::class)
class SubtitleOffsetRenderer(
    baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val itemDurationUsProvider: () -> Long,
) : ForwardingRenderer(baseRenderer) {

    private var stream: SubtitleReplaySampleStream? = null
    private var streamOffsetUs = 0L
    private var positionUs = C.TIME_UNSET
    private var appliedDelayUs = 0L

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        val replayStream = SubtitleReplaySampleStream(stream)
        this.stream = replayStream
        streamOffsetUs = offsetUs
        this.positionUs = C.TIME_UNSET
        appliedDelayUs = subtitleDelayUsProvider()
        super.enable(
            configuration,
            formats,
            replayStream,
            adjust(positionUs, appliedDelayUs),
            joining,
            mayRenderStartOfStream,
            startPositionUs,
            offsetUs,
            mediaPeriodId,
        )
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        val replayStream = SubtitleReplaySampleStream(stream)
        this.stream = replayStream
        streamOffsetUs = offsetUs
        super.replaceStream(formats, replayStream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun getStream(): SampleStream? = stream?.upstream ?: super.getStream()

    override fun disable() {
        super.disable()
        stream = null
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        this.positionUs = positionUs
        val delayUs = subtitleDelayUsProvider()
        if (delayUs != appliedDelayUs) {
            appliedDelayUs = delayUs
            val streamFinal = isCurrentStreamFinal
            rewindStream(adjust(positionUs, delayUs))
            super.resetPosition(adjust(positionUs, delayUs), true)
            if (streamFinal) super.setCurrentStreamFinal()
        }
        super.render(adjust(positionUs, delayUs), elapsedRealtimeUs)
        var extraRenders = 0
        while (stream?.hasPendingReplay() == true && extraRenders < MAX_REPLAY_RENDERS) {
            super.render(adjust(positionUs, delayUs), elapsedRealtimeUs)
            extraRenders++
        }
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        this.positionUs = positionUs
        appliedDelayUs = subtitleDelayUsProvider()
        if (appliedDelayUs != 0L) rewindStream(adjust(positionUs, appliedDelayUs))
        super.resetPosition(adjust(positionUs, appliedDelayUs), sampleStreamIsResetToKeyFrame)
    }

    override fun isEnded(): Boolean {
        if (super.isEnded()) return true
        if (subtitleDelayUsProvider() <= 0 || positionUs == C.TIME_UNSET) return false
        val durationUs = itemDurationUsProvider()
        return durationUs != C.TIME_UNSET && positionUs - streamOffsetUs >= durationUs
    }

    override fun supportsResetPositionWithoutKeyFrameReset(positionUs: Long): Boolean =
        super.supportsResetPositionWithoutKeyFrameReset(
            adjust(positionUs, subtitleDelayUsProvider()),
        )

    private fun rewindStream(rendererPositionUs: Long) {
        stream?.rewind(rendererPositionUs - streamOffsetUs - REPLAY_LOOKBACK_US)
    }

    private fun adjust(positionUs: Long, delayUs: Long): Long =
        (positionUs - delayUs).coerceAtLeast(0L)

    private companion object {
        const val REPLAY_LOOKBACK_US = 30_000_000L
        const val MAX_REPLAY_RENDERS = 64
    }
}
