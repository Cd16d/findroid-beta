package dev.jdtech.jellyfin.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.get
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.vanniktech.blurhash.BlurHash
import dev.jdtech.jellyfin.models.FindroidItem
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber

/**
 * Processes and optimizes an image URI for display:
 * - If local (no scheme), it resolves the path relative to the app's internal files' directory.
 * - If remote (http/https), it appends resizing query parameters based on the target dimensions.
 */
@Composable
fun Uri?.toOptimizedImageUri(
    widthDp: Dp? = null,
    heightDp: Dp? = null,
    quality: Int = 80,
    imageType: ImageType? = null,
): Uri? {
    if (this == null) return null
    val context = LocalContext.current
    val density = LocalDensity.current

    return remember(this, widthDp, heightDp, quality, imageType) {
        val targetWidthPx = widthDp?.let { with(density) { it.toPx().toInt() } }
        val targetHeightPx = heightDp?.let { with(density) { it.toPx().toInt() } }
        toOptimizedImageUri(
            context = context,
            targetWidthPx = targetWidthPx,
            targetHeightPx = targetHeightPx,
            quality = quality,
            imageType = imageType,
        )
    }
}

/**
 * Non-composable variant of [toOptimizedImageUri] using explicit pixel target dimensions so both
 * Compose UI and background prefetchers generate identical cache keys.
 */
fun Uri?.toOptimizedImageUri(
    context: Context,
    targetWidthPx: Int?,
    targetHeightPx: Int?,
    quality: Int = 80,
    imageType: ImageType? = null,
): Uri? {
    if (this == null) return null
    val baseUri =
        if (scheme == null) {
            Uri.fromFile(File(context.filesDir, path ?: ""))
        } else {
            this
        }

    return if (
        baseUri.scheme?.startsWith("http") == true &&
            targetWidthPx != null &&
            targetHeightPx != null &&
            targetWidthPx > 0 &&
            targetHeightPx > 0
    ) {
        if (imageType == ImageType.LOGO) {
            baseUri
                .buildUpon()
                .appendQueryParameter("fillWidth", targetWidthPx.toString())
                .appendQueryParameter("fillHeight", targetHeightPx.toString())
                .appendQueryParameter("quality", quality.toString())
                .build()
        } else {
            baseUri
                .buildUpon()
                .appendQueryParameter("maxWidth", targetWidthPx.toString())
                .appendQueryParameter("maxHeight", targetHeightPx.toString())
                .appendQueryParameter("quality", quality.toString())
                .build()
        }
    } else {
        baseUri
    }
}

private fun Context.isTelevision(): Boolean = runCatching {
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
    .getOrDefault(false)

/**
 * Computes deterministic 16:9 backdrop dimensions in pixels matching the actual Home carousel card
 * size on the current device (phone, tablet, or Android TV), quantized to 64px buckets so UI
 * rendering and background prefetching hit the exact same URL and disk cache entry.
 */
fun computeCarouselBackdropSizePx(context: Context): Pair<Int, Int> {
    val displayMetrics = context.resources.displayMetrics
    val density = displayMetrics.density.coerceAtLeast(1f)
    val screenWidthDp = displayMetrics.widthPixels / density
    val screenHeightDp = displayMetrics.heightPixels / density

    return if (context.isTelevision()) {
        val heroHeightDp = (screenHeightDp - 200f).coerceAtLeast(380f)
        val heroWidthDp = heroHeightDp * 16f / 9f
        val rawWidthPx = (heroWidthDp * density).toInt()
        val targetWidthPx = (((rawWidthPx + 63) / 64) * 64).coerceIn(640, 1920)
        targetWidthPx to (targetWidthPx * 9) / 16
    } else {
        val hasNavRail = screenWidthDp >= 840f || screenHeightDp < 480f
        val horizontalChromeDp = (if (hasNavRail) 80f else 0f) + 48f
        val availableWidthDp = (screenWidthDp - horizontalChromeDp).coerceAtLeast(1f)
        val nPages =
            when {
                availableWidthDp >= 840f -> 3
                availableWidthDp >= 600f -> 2
                else -> 1
            }
        val pageSpacingDp = 16f
        val cardWidthDp =
            ((availableWidthDp - (nPages - 1) * pageSpacingDp) / nPages).coerceAtLeast(1f)
        val rawWidthPx = (cardWidthDp * density).toInt()
        val targetWidthPx = (((rawWidthPx + 63) / 64) * 64).coerceIn(320, 1280)
        targetWidthPx to (targetWidthPx * 9) / 16
    }
}

/** Resolves the card-sized backdrop URI used by Home carousel items and background prefetching. */
fun Uri?.toCarouselBackdropUri(context: Context, quality: Int = 80): Uri? {
    val (widthPx, heightPx) = computeCarouselBackdropSizePx(context)
    return toOptimizedImageUri(
        context = context,
        targetWidthPx = widthPx,
        targetHeightPx = heightPx,
        quality = quality,
    )
}

/**
 * Pre-populates Coil's disk cache (with memory cache disabled) for the buffered next-session
 * carousel [items] using the exact same optimized URIs rendered by the carousel UI.
 */
suspend fun prefetchCarouselImages(
    context: Context,
    items: List<FindroidItem>,
    includeLogos: Boolean = false,
) {
    if (items.isEmpty()) return
    val isTv = context.isTelevision()
    val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    val logoWidthPx = (400f * density).toInt()
    val logoHeightPx = (120f * density).toInt()

    val uris = buildList {
        for (item in items) {
            val backdrop =
                if (isTv) {
                    item.images.backdrop
                        ?: item.images.showBackdrop
                        ?: item.images.primary
                        ?: item.images.showPrimary
                } else {
                    item.images.backdrop
                }
            backdrop?.uri.toCarouselBackdropUri(context)?.let { add(it) }

            if (isTv && includeLogos) {
                val logo = item.images.logo ?: item.images.showLogo
                logo
                    ?.uri
                    .toOptimizedImageUri(
                        context = context,
                        targetWidthPx = logoWidthPx,
                        targetHeightPx = logoHeightPx,
                        imageType = ImageType.LOGO,
                    )
                    ?.let { add(it) }
            }
        }
    }

    if (uris.isEmpty()) return
    val imageLoader = SingletonImageLoader.get(context)
    for (uri in uris) {
        currentCoroutineContext().ensureActive()
        try {
            val request =
                ImageRequest.Builder(context)
                    .data(uri)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            imageLoader.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "Failed to prefetch carousel image %s", uri)
        }
    }
}

/** Creates a [BitmapPainter] from a BlurHash string. */
fun String?.toBlurHashPainter(
    width: Int = 64,
    height: Int = 36,
    punch: Float = 1.25f,
): BitmapPainter? {
    if (!this.isNullOrEmpty()) {
        val bitmap =
            BlurHash.decode(
                blurHash = this,
                width = width,
                height = height,
                punch = punch,
            )
        return bitmap?.asImageBitmap()?.let { BitmapPainter(it) }
    } else {
        return null
    }
}

/**
 * Extracts a dominant, vibrant palette color from a BlurHash string, filtering out muddy
 * dark/gray/brown tones and boosting vibrancy. Returns null if invalid or failed.
 */
fun String?.toBlurHashColor(): Color? {
    if (this.isNullOrEmpty()) return null
    return try {
        // Decode an 8x8 bitmap (64 pixels) for accurate spatial color sampling
        val bitmap = BlurHash.decode(blurHash = this, width = 8, height = 8) ?: return null

        val hsv = FloatArray(3)
        var bestColor: Color? = null
        var highestScore = -1f

        var fallbackTotalRed = 0L
        var fallbackTotalGreen = 0L
        var fallbackTotalBlue = 0L

        for (x in 0 until 8) {
            for (y in 0 until 8) {
                val pixel = bitmap[x, y]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                fallbackTotalRed += r
                fallbackTotalGreen += g
                fallbackTotalBlue += b

                android.graphics.Color.colorToHSV(pixel, hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]

                // Filter out extremes: pure blacks/whites and completely desaturated grays
                if (value <= 0.08f || value >= 0.96f || saturation < 0.16f) {
                    continue
                }

                // Filter out muddy browns (Hue 10°..38°) unless saturation is exceptionally high
                if (hue in 10f..38f && saturation < 0.75f) {
                    continue
                }

                // Score candidate based on saturation and brightness vibrancy
                val score = saturation * 1.5f + value * 0.8f
                if (score > highestScore) {
                    highestScore = score
                    // Balanced vibrant color
                    val targetSaturation = (saturation * 1.15f).coerceIn(0.40f, 0.80f)
                    val targetValue = value.coerceIn(0.50f, 0.85f)
                    val vibrantHsv = floatArrayOf(hue, targetSaturation, targetValue)
                    val vibrantInt = android.graphics.Color.HSVToColor(vibrantHsv)
                    bestColor = Color(vibrantInt)
                }
            }
        }
        bitmap.recycle()

        bestColor
            ?: run {
                // Graceful fallback to average if all pixels were filtered out (e.g. monochrome)
                val avgR = (fallbackTotalRed / 64).toInt()
                val avgG = (fallbackTotalGreen / 64).toInt()
                val avgB = (fallbackTotalBlue / 64).toInt()
                Color(red = avgR, green = avgG, blue = avgB)
            }
    } catch (_: Exception) {
        null
    }
}
