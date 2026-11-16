package dev.jdtech.jellyfin.player.local.presentation

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import java.util.ArrayDeque
import java.util.TreeMap

/**
 * Wraps ExoPlayer's subtitle [SampleStream] to cache samples and replay them when subtitle delay
 * changes during active playback.
 */
@OptIn(UnstableApi::class)
class SubtitleReplaySampleStream(val upstream: SampleStream) : SampleStream {

    private data class Sample(
        val timeUs: Long,
        val flags: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sample) return false
            return timeUs == other.timeUs && flags == other.flags && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = timeUs.hashCode()
            result = 31 * result + flags
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    private val kept = TreeMap<Long, MutableList<Sample>>()
    private var keptBytes = 0L

    private val replay = ArrayDeque<Sample>()
    private var rewindFromUs: Long? = null

    private var owedFormat: Format? = null
    private val peekFormatHolder = FormatHolder()
    private val peekBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)

    fun rewind(fromUs: Long) {
        replay.clear()
        rewindFromUs = fromUs
    }

    fun hasPendingReplay(): Boolean = replay.isNotEmpty()

    override fun isReady(): Boolean =
        replay.isNotEmpty() || owedFormat != null || upstream.isReady()

    override fun maybeThrowError() = upstream.maybeThrowError()

    override fun skipData(positionUs: Long): Int {
        replay.clear()
        rewindFromUs = null
        return upstream.skipData(positionUs)
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int,
    ): Int {
        if (readFlags and SampleStream.FLAG_REQUIRE_FORMAT != 0 && owedFormat == null) {
            return upstream.readData(formatHolder, buffer, readFlags)
        }
        rewindFromUs?.let { fromUs ->
            if (!fillReplay(fromUs)) return C.RESULT_NOTHING_READ
            rewindFromUs = null
        }
        owedFormat?.let { format ->
            formatHolder.format = format
            if (readFlags and SampleStream.FLAG_PEEK == 0) owedFormat = null
            return C.RESULT_FORMAT_READ
        }
        replay.firstOrNull()?.let { sample ->
            buffer.timeUs = sample.timeUs
            buffer.setFlags(sample.flags)
            if (readFlags and SampleStream.FLAG_OMIT_SAMPLE_DATA == 0) {
                buffer.ensureSpaceForWrite(sample.data.size)
                buffer.data!!.put(sample.data)
            }
            if (readFlags and SampleStream.FLAG_PEEK == 0) replay.removeFirst()
            return C.RESULT_BUFFER_READ
        }
        val result = upstream.readData(formatHolder, buffer, readFlags)
        if (
            result == C.RESULT_BUFFER_READ &&
                !buffer.isEndOfStream &&
                readFlags and (SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA) == 0
        ) {
            keep(buffer)
        }
        return result
    }

    private fun fillReplay(fromUs: Long): Boolean {
        peekBuffer.clear()
        val nextUs =
            when (
                upstream.readData(
                    peekFormatHolder,
                    peekBuffer,
                    SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA,
                )
            ) {
                C.RESULT_BUFFER_READ ->
                    if (peekBuffer.isEndOfStream) Long.MAX_VALUE else peekBuffer.timeUs
                C.RESULT_FORMAT_READ -> {
                    owedFormat = peekFormatHolder.format
                    peekFormatHolder.clear()
                    return true
                }
                else -> return false
            }
        if (nextUs > fromUs) {
            kept.subMap(fromUs, true, nextUs, false).values.forEach { replay.addAll(it) }
        }
        return true
    }

    private fun keep(buffer: DecoderInputBuffer) {
        val data = buffer.data ?: return
        val size = data.position()
        if (size <= 0 || size > MAX_SAMPLE_BYTES) return
        val bytes = ByteArray(size)
        data.duplicate().apply { flip() }.get(bytes)
        val samples = kept.getOrPut(buffer.timeUs) { mutableListOf() }
        if (samples.any { it.data.contentEquals(bytes) }) return
        val flags =
            (if (buffer.isKeyFrame) C.BUFFER_FLAG_KEY_FRAME else 0) or
                (if (buffer.isFirstSample) C.BUFFER_FLAG_FIRST_SAMPLE else 0)
        samples.add(Sample(buffer.timeUs, flags, bytes))
        keptBytes += bytes.size
        trim(aroundUs = buffer.timeUs)
    }

    private fun trim(aroundUs: Long) {
        while (kept.isNotEmpty() && kept.firstKey() < aroundUs - KEEP_SPAN_US) {
            kept.pollFirstEntry()?.value?.forEach { keptBytes -= it.data.size }
        }
        while (kept.isNotEmpty() && kept.lastKey() > aroundUs + KEEP_SPAN_US) {
            kept.pollLastEntry()?.value?.forEach { keptBytes -= it.data.size }
        }
        while (kept.isNotEmpty() && keptBytes > MAX_KEPT_BYTES) {
            kept.pollFirstEntry()?.value?.forEach { keptBytes -= it.data.size }
        }
    }

    private companion object {
        const val KEEP_SPAN_US = 240_000_000L
        const val MAX_KEPT_BYTES = 8L * 1024 * 1024
        const val MAX_SAMPLE_BYTES = 256 * 1024
    }
}
