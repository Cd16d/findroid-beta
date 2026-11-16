package dev.jdtech.jellyfin.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Resolves this [UiText] into a [String] within a Composable scope. */
@Composable
fun UiText.asString(): String {
    return when (this) {
        is UiText.DynamicString -> value
        is UiText.StringResource -> stringResource(resId, *args)
    }
}
