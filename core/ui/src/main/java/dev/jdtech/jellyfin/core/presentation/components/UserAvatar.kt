package dev.jdtech.jellyfin.core.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.jdtech.jellyfin.core.R as CoreR

/** Harmonized Material 3 palette for user avatars based on name hash. */
private val AvatarPalette =
    listOf(
        Color(0xFFD32F2F), // Red
        Color(0xFFC2185B), // Pink
        Color(0xFF7B1FA2), // Purple
        Color(0xFF512DA8), // Deep Purple
        Color(0xFF303F9F), // Indigo
        Color(0xFF1976D2), // Blue
        Color(0xFF0288D1), // Light Blue
        Color(0xFF0097A7), // Cyan
        Color(0xFF00796B), // Teal
        Color(0xFF388E3C), // Green
        Color(0xFF689F38), // Light Green
        Color(0xFFE64A19), // Deep Orange
        Color(0xFF5D4037), // Brown
        Color(0xFF455A64), // Blue Grey
    )

/** Computes a deterministic background color for an avatar based on the user's name. */
fun getAvatarColor(name: String): Color {
    val cleanName = name.trim().lowercase()
    if (cleanName.isEmpty()) return Color(0xFF757575)
    val index = (cleanName.hashCode() and Int.MAX_VALUE) % AvatarPalette.size
    return AvatarPalette[index]
}

/**
 * Unified user avatar component displaying either a profile picture or a fallback badge with the
 * capitalized initial letter of the user's name on a deterministic Material 3 color background. If
 * the user name is empty, it falls back to the standard user vector icon.
 */
@Composable
fun UserAvatar(
    name: String,
    modifier: Modifier = Modifier,
    imageUrl: Any? = null,
    size: Dp = 40.dp,
    shape: Shape = CircleShape,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val cleanName = name.trim()
    val initial = cleanName.firstOrNull()?.uppercaseChar()
    val hasValidImage =
        when (imageUrl) {
            null -> false
            is String -> imageUrl.isNotBlank()
            else -> true
        }

    var isImageSuccess by remember(imageUrl) { mutableStateOf(false) }
    var isImageError by remember(imageUrl) { mutableStateOf(false) }

    val backgroundColor =
        if (isImageSuccess && !isImageError) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else if (initial != null) {
            getAvatarColor(cleanName)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

    @Composable
    fun AvatarContent() {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (!isImageSuccess || isImageError) {
                if (initial != null) {
                    Text(
                        text = initial.toString(),
                        style =
                            TextStyle(
                                fontSize = (size.value * 0.44f).coerceAtLeast(10f).sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                            ),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_user),
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size((size * 0.5f).coerceAtLeast(12.dp)),
                    )
                }
            }

            if (hasValidImage && !isImageError) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    onSuccess = { isImageSuccess = true },
                    onError = { isImageError = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(size),
            shape = shape,
            color = backgroundColor,
        ) {
            AvatarContent()
        }
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = shape,
            color = backgroundColor,
        ) {
            AvatarContent()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UserAvatarLetterPreview() {
    MaterialTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(name = "Alice", size = 24.dp)
            UserAvatar(name = "Bob", size = 32.dp)
            UserAvatar(name = "Charlie", size = 40.dp)
            UserAvatar(name = "David", size = 56.dp)
            UserAvatar(name = "Eve", size = 96.dp)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UserAvatarFallbackIconPreview() {
    MaterialTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(name = "", size = 40.dp)
            UserAvatar(name = "   ", size = 40.dp)
        }
    }
}
