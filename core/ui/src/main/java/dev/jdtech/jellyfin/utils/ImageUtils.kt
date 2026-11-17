package dev.jdtech.jellyfin.utils

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
import com.vanniktech.blurhash.BlurHash
import java.io.File
import org.jellyfin.sdk.model.api.ImageType

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
        val baseUri =
            if (scheme == null) {
                Uri.fromFile(File(context.filesDir, path ?: ""))
            } else {
                this
            }

        if (baseUri.scheme?.startsWith("http") == true && widthDp != null && heightDp != null) {
            val targetWidthPx = with(density) { widthDp.toPx().toInt() }
            val targetHeightPx = with(density) { heightDp.toPx().toInt() }

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
