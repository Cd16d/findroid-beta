package dev.jdtech.jellyfin.player.core.domain.utils

import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Initializes trickplay thumbnail metadata for scrubbing previews and starts a bounded sliding
 * cache window.
 */
suspend fun fetchTrickplay(
    repository: JellyfinRepository,
    item: PlayerItem,
    cacheDir: File? = null,
    coroutineScope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Trickplay? {
    val trickplayInfo = item.trickplayInfo ?: return null
    if (
        trickplayInfo.thumbnailCount <= 0 || trickplayInfo.width <= 0 || trickplayInfo.height <= 0
    ) {
        return null
    }
    if (trickplayInfo.tileWidth <= 0 || trickplayInfo.tileHeight <= 0) {
        return null
    }

    val tilesPerSheet =
        (trickplayInfo.tileWidth.toLong() * trickplayInfo.tileHeight.toLong())
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    val sheetCount =
        (trickplayInfo.thumbnailCount / tilesPerSheet) +
            if (trickplayInfo.thumbnailCount % tilesPerSheet == 0) 0 else 1
    if (sheetCount <= 0) return null

    return Trickplay(
        itemId = item.itemId,
        info = trickplayInfo,
        mediaSourceId = item.mediaSourceId,
        repository = repository,
        cacheDir = cacheDir,
        coroutineScope = coroutineScope,
        dispatcher = dispatcher,
    )
}
