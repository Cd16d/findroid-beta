package dev.jdtech.jellyfin.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import dev.jdtech.jellyfin.database.entity.HomeItemEntity
import dev.jdtech.jellyfin.database.entity.HomeSectionEntity
import dev.jdtech.jellyfin.database.entity.HomeSectionWithItems
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface HomeSectionDao {
    @Transaction
    @Query(
        "SELECT * FROM home_sections WHERE serverId = :serverId AND userId = :userId AND sectionId != 'suggestions_buffer' ORDER BY orderIndex ASC",
    )
    fun observeHomeSectionsWithItems(
        serverId: String,
        userId: UUID,
    ): Flow<List<HomeSectionWithItems>>

    @Query(
        "SELECT * FROM home_items WHERE serverId = :serverId AND userId = :userId AND sectionId = :sectionId ORDER BY orderIndex ASC",
    )
    suspend fun getItemsForSection(
        serverId: String,
        userId: UUID,
        sectionId: String,
    ): List<HomeItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<HomeSectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<HomeItemEntity>)

    @Query("DELETE FROM home_sections WHERE serverId = :serverId AND userId = :userId")
    suspend fun deleteHomeSectionsForUser(serverId: String, userId: UUID)

    @Query(
        "DELETE FROM home_sections WHERE serverId = :serverId AND userId = :userId AND sectionId = :sectionId",
    )
    suspend fun deleteSection(serverId: String, userId: UUID, sectionId: String)

    @Query(
        "DELETE FROM home_sections WHERE serverId = :serverId AND userId = :userId AND sectionId NOT IN ('suggestions', 'suggestions_buffer')",
    )
    suspend fun deleteNonSuggestionSections(serverId: String, userId: UUID)

    @Transaction
    suspend fun replaceAllHomeSections(
        serverId: String,
        userId: UUID,
        sections: List<HomeSectionEntity>,
        items: List<HomeItemEntity>,
        preserveSuggestions: Boolean = false,
    ) {
        if (preserveSuggestions) {
            deleteNonSuggestionSections(serverId, userId)
        } else {
            // Delete all except buffer
            deleteSection(serverId, userId, "suggestions")
            deleteNonSuggestionSections(serverId, userId)
        }
        if (sections.isNotEmpty()) {
            insertSections(sections)
            insertItems(items)
        }
    }

    @Transaction
    suspend fun saveSuggestionsBuffer(
        serverId: String,
        userId: UUID,
        items: List<HomeItemEntity>,
    ) {
        deleteSection(serverId, userId, "suggestions_buffer")
        if (items.isNotEmpty()) {
            val bufferSection =
                HomeSectionEntity(
                    id = "${serverId}_${userId}_suggestions_buffer",
                    serverId = serverId,
                    userId = userId,
                    sectionId = "suggestions_buffer",
                    sectionType = "SUGGESTIONS",
                    title = "",
                    collectionType = null,
                    orderIndex = -1,
                )
            insertSections(listOf(bufferSection))
            val bufferItems = items.mapIndexed { idx, item ->
                item.copy(
                    id = "${serverId}_${userId}_suggestions_buffer_${item.itemId}",
                    sectionCompositeId = "${serverId}_${userId}_suggestions_buffer",
                    sectionId = "suggestions_buffer",
                    orderIndex = idx,
                )
            }
            insertItems(bufferItems)
        }
    }

    @Transaction
    suspend fun promoteSuggestionsBuffer(serverId: String, userId: UUID): Boolean {
        val bufferItems = getItemsForSection(serverId, userId, "suggestions_buffer")
        if (bufferItems.isEmpty()) return false

        deleteSection(serverId, userId, "suggestions")
        val activeSection =
            HomeSectionEntity(
                id = "${serverId}_${userId}_suggestions",
                serverId = serverId,
                userId = userId,
                sectionId = "suggestions",
                sectionType = "SUGGESTIONS",
                title = "",
                collectionType = null,
                orderIndex = 0,
            )
        insertSections(listOf(activeSection))
        val promotedItems = bufferItems.mapIndexed { idx, item ->
            item.copy(
                id = "${serverId}_${userId}_suggestions_${item.itemId}",
                sectionCompositeId = "${serverId}_${userId}_suggestions",
                sectionId = "suggestions",
                orderIndex = idx,
            )
        }
        insertItems(promotedItems)
        deleteSection(serverId, userId, "suggestions_buffer")
        return true
    }

    @Query(
        """
        UPDATE home_items 
        SET playbackPositionTicks = :ticks, played = :played 
        WHERE userId = :userId AND itemId = :itemId
        """,
    )
    suspend fun updateItemPlaybackProgress(
        userId: UUID,
        itemId: UUID,
        ticks: Long,
        played: Boolean,
    )

    @Query(
        """
        DELETE FROM home_items 
        WHERE sectionId = 'resume' AND userId = :userId AND itemId = :itemId
        """,
    )
    suspend fun removeResumeItem(userId: UUID, itemId: UUID)
}
