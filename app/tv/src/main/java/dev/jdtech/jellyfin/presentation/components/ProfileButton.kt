package dev.jdtech.jellyfin.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.core.presentation.components.UserAvatar
import dev.jdtech.jellyfin.core.presentation.dummy.dummyUser
import dev.jdtech.jellyfin.models.User
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import org.jellyfin.sdk.model.api.ImageType

/**
 * Circular user profile icon button for TV header navigation.
 *
 * @param user User model representing currently active Jellyfin user profile.
 * @param onClick Callback executed when button is clicked.
 * @param modifier Optional modifier applied to surface container. Defaults to [Modifier].
 */
@Composable
fun ProfileButton(user: User?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val baseUrl =
        if (LocalInspectionMode.current) "" else JellyfinApi.getInstance(context).api.baseUrl
    Surface(
        onClick = { onClick() },
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
            ),
        border =
            ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White), shape = CircleShape),
                focusedBorder = Border(BorderStroke(4.dp, Color.White), shape = CircleShape),
            ),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape, focusedShape = CircleShape),
        modifier = modifier.width(32.dp).aspectRatio(1f),
    ) {
        UserAvatar(
            name = user?.name ?: "",
            imageUrl =
                user?.let {
                    "$baseUrl/users/${user.id}/Images/${ImageType.PRIMARY}?maxWidth=64&maxHeight=64&quality=80"
                },
            size = 32.dp,
        )
    }
}

@Preview(device = "id:tv_1080p")
@Composable
private fun ProfileButtonPreview() {
    FindroidTheme { ProfileButton(user = dummyUser, onClick = {}) }
}
