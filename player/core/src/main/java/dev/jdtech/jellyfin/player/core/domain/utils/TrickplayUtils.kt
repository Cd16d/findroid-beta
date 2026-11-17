package dev.jdtech.jellyfin.player.core.domain.utils

import dev.jdtech.jellyfin.player.core.domain.models.PlayerItem
import dev.jdtech.jellyfin.player.core.domain.models.Trickplay
import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Initializes trickplay thumbnail metadata for scrubbing previews and starts background prefetching
 * of all sprite sheets into memory.
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

    val tilesPerSheet = trickplayInfo.tileWidth * trickplayInfo.tileHeight
    if (tilesPerSheet <= 0) return null
    val sheetCount = ceil(trickplayInfo.thumbnailCount.toDouble() / tilesPerSheet).toInt()
    if (sheetCount !in 1..500) return null

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
