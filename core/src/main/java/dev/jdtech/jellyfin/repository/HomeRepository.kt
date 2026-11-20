package dev.jdtech.jellyfin.repository

import dev.jdtech.jellyfin.models.HomeItem
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class HomeData(
    val suggestions: HomeItem.Suggestions? = null,
    val resume: HomeItem.Section? = null,
    val nextUp: HomeItem.Section? = null,
    val views: List<HomeItem.ViewItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = suggestions == null && resume == null && nextUp == null && views.isEmpty()
}

interface HomeRepository {
    fun observeHomeData(serverId: String, userId: UUID): Flow<HomeData>

    suspend fun rotateSuggestionsBuffer(serverId: String, userId: UUID): Boolean

    suspend fun refreshHome(
        serverId: String,
        userId: UUID,
        refreshSuggestions: Boolean = true,
    ): Result<Unit>

    suspend fun updateOptimisticPlayback(
        userId: UUID,
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    )
}
