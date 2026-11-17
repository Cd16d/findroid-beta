package dev.jdtech.jellyfin.player.core.domain.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Coordinates for clipping a single preview frame from a trickplay sprite sheet. */
data class TrickplayFrame(
    val sheetIndex: Int,
    val srcX: Int,
    val srcY: Int,
    val srcWidth: Int,
    val srcHeight: Int,
)

/**
 * Manages trickplay seek preview thumbnails by caching whole sprite sheets and clipping individual
 * frames via GPU Canvas clipping without slicing hundreds of separate Bitmaps.
 */
class Trickplay(
    val itemId: UUID,
    val info: TrickplayInfo,
    val mediaSourceId: String? = null,
    private val repository: JellyfinRepository? = null,
    private val cacheDir: File? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val width: Int = info.width
    val height: Int = info.height
    val interval: Int = info.interval
    val intervalMs: Long =
        if (info.interval >= 1000) info.interval.toLong()
        else (info.interval * 1000L).coerceAtLeast(1000L)
    val tileColumns: Int = info.tileWidth.coerceAtLeast(1)
    val tileRows: Int = info.tileHeight.coerceAtLeast(1)
    val tilesPerSheet: Int = (tileColumns * tileRows).coerceAtLeast(1)

    // Handle server bug where ThumbnailCount represents total sheets rather than total thumbnail
    // cells
    private val isSheetCountBug: Boolean =
        info.thumbnailCount in 1..60 && (info.thumbnailCount < tilesPerSheet)

    val sheetCount: Int =
        if (isSheetCountBug) info.thumbnailCount
        else ceil(info.thumbnailCount.toDouble() / tilesPerSheet).toInt()

    val thumbnailCount: Int =
        if (isSheetCountBug) info.thumbnailCount * tilesPerSheet else info.thumbnailCount

    val hasThumbnails: Boolean = sheetCount > 0 && width > 0 && height > 0

    private val trickplayDir: File? = cacheDir?.let {
        val dirName = if (mediaSourceId.isNullOrEmpty()) "$itemId" else "${itemId}_$mediaSourceId"
        File(it, "trickplay/$dirName")
    }

    private val sheetBitmaps = ConcurrentHashMap<Int, Bitmap>()
    private val activeLoads = ConcurrentHashMap<Int, Deferred<Bitmap?>>()
    private var downloadJob: Job? = null

    init {
        if (
            coroutineScope != null && repository != null && trickplayDir != null && sheetCount > 0
        ) {
            trickplayDir.mkdirs()
            downloadJob = coroutineScope.launch(dispatcher) { preloadAllSheets(repository) }
        }
    }

    /** Preloads all sprite sheets asynchronously into the disk and in-memory cache. */
    private suspend fun preloadAllSheets(repo: JellyfinRepository) {
        val dir = trickplayDir ?: return
        for (i in 0 until sheetCount) {
            if (!coroutineScopeActive()) break
            if (sheetBitmaps.containsKey(i)) continue

            val file = File(dir, "$i")
            if (isValidImageFile(file)) {
                val bitmap = decodeBitmap(file)
                if (bitmap != null) {
                    sheetBitmaps[i] = bitmap
                    continue
                }
                file.delete()
            }

            var downloaded = false
            var attempts = 0
            while (!downloaded && attempts < 3 && coroutineScopeActive()) {
                attempts++
                try {
                    val data = repo.getTrickplayData(itemId, width, i)
                    if (data != null && isValidImageData(data) && coroutineScopeActive()) {
                        writeAtomically(file, data)
                        val bitmap = decodeBitmap(data)
                        if (bitmap != null) {
                            sheetBitmaps[i] = bitmap
                            downloaded = true
                        }
                    } else if (data == null) {
                        break
                    }
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "Failed to download trickplay sheet $i for item $itemId (attempt $attempts)",
                    )
                }
                if (!downloaded && attempts < 3 && coroutineScopeActive()) {
                    delay(100L * attempts)
                }
            }
        }
    }

    private fun coroutineScopeActive(): Boolean = coroutineScope?.isActive ?: false

    /** Synchronously returns the cached sheet bitmap if currently resident in memory. */
    fun getCachedSheet(sheetIndex: Int): Bitmap? {
        val bitmap = sheetBitmaps[sheetIndex]
        return if (bitmap != null && !bitmap.isRecycled) bitmap else null
    }

    /**
     * Asynchronously retrieves the sprite sheet at [sheetIndex], prioritizing on-demand loading
     * from disk or network if not already in memory.
     */
    suspend fun getSheet(sheetIndex: Int): Bitmap? =
        withContext(dispatcher) {
            if (sheetIndex !in 0 until sheetCount) return@withContext null
            getCachedSheet(sheetIndex)?.let {
                return@withContext it
            }

            val scope = coroutineScope ?: this
            val deferred =
                activeLoads.computeIfAbsent(sheetIndex) {
                    scope.async(dispatcher) { loadSheetInternal(sheetIndex) }
                }
            try {
                deferred.await()
            } finally {
                activeLoads.remove(sheetIndex)
            }
        }

    private suspend fun loadSheetInternal(sheetIndex: Int): Bitmap? {
        getCachedSheet(sheetIndex)?.let {
            return it
        }

        val file = trickplayDir?.let { File(it, "$sheetIndex") }
        if (file != null && file.exists()) {
            if (isValidImageFile(file)) {
                val bitmap = decodeBitmap(file)
                if (bitmap != null) {
                    sheetBitmaps[sheetIndex] = bitmap
                    return bitmap
                }
            }
            file.delete()
        }

        if (repository != null) {
            var attempts = 0
            while (attempts < 2) {
                attempts++
                try {
                    val data = repository.getTrickplayData(itemId, width, sheetIndex)
                    if (data != null && isValidImageData(data)) {
                        if (file != null) writeAtomically(file, data)
                        val bitmap = decodeBitmap(data)
                        if (bitmap != null) {
                            sheetBitmaps[sheetIndex] = bitmap
                            return bitmap
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(
                        e,
                        "Error loading trickplay sheet $sheetIndex for item $itemId (attempt $attempts)",
                    )
                }
            }
        }

        return null
    }

    private fun writeAtomically(targetFile: File, data: ByteArray) {
        val parent = targetFile.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        val tmpFile = File(parent, "${targetFile.name}.${UUID.randomUUID()}.tmp")
        try {
            tmpFile.writeBytes(data)
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to write trickplay file atomically: ${targetFile.name}")
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    private fun isValidImageData(data: ByteArray): Boolean {
        if (data.size < 128) return false
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()
        val isWebp =
            data.size >= 12 &&
                data[0] == 'R'.code.toByte() &&
                data[1] == 'I'.code.toByte() &&
                data[2] == 'F'.code.toByte() &&
                data[3] == 'F'.code.toByte()
        val isPng = data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte()
        if (!isJpeg && !isWebp && !isPng) return false

        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < 128L) return false
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (_: Throwable) {
            false
        }
    }

    private fun decodeBitmap(source: Any): Bitmap? =
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            when (source) {
                is File -> BitmapFactory.decodeFile(source.absolutePath, opts)
                is ByteArray -> BitmapFactory.decodeByteArray(source, 0, source.size, opts)
                else -> null
            }
        } catch (e: Throwable) {
            Timber.w(e, "Failed to decode trickplay sheet")
            null
        }

    /**
     * Calculates the sheet index and source sub-rectangle for rendering the frame at [positionMs].
     */
    fun getFrameInfo(positionMs: Long): TrickplayFrame? {
        if (intervalMs <= 0L || width <= 0 || height <= 0 || thumbnailCount <= 0) return null
        val frameIndex =
            (positionMs.coerceAtLeast(0L) / intervalMs).toInt().coerceIn(0, thumbnailCount - 1)
        val sheetIndex = frameIndex / tilesPerSheet
        val tileIndex = frameIndex % tilesPerSheet
        val col = tileIndex % tileColumns
        val row = tileIndex / tileColumns
        return TrickplayFrame(
            sheetIndex = sheetIndex,
            srcX = col * width,
            srcY = row * height,
            srcWidth = width,
            srcHeight = height,
        )
    }

    /** No-op position updater maintained for interface compatibility. */
    fun updatePosition(positionMs: Long) {}

    /** Releases in-memory bitmaps and cancels in-flight background loading jobs. */
    fun release() {
        downloadJob?.cancel()
        downloadJob = null
        activeLoads.values.forEach { it.cancel() }
        activeLoads.clear()
        sheetBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        sheetBitmaps.clear()
    }
}
