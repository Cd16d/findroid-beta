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
        "SELECT * FROM home_sections WHERE serverId = :serverId AND userId = :userId ORDER BY orderIndex ASC",
    )
    fun observeHomeSectionsWithItems(
        serverId: String,
        userId: UUID,
    ): Flow<List<HomeSectionWithItems>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSections(sections: List<HomeSectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<HomeItemEntity>)

    @Query("DELETE FROM home_sections WHERE serverId = :serverId AND userId = :userId")
    suspend fun deleteHomeSectionsForUser(serverId: String, userId: UUID)

    @Transaction
    suspend fun replaceAllHomeSections(
        serverId: String,
        userId: UUID,
        sections: List<HomeSectionEntity>,
        items: List<HomeItemEntity>,
    ) {
        deleteHomeSectionsForUser(serverId, userId)
        if (sections.isNotEmpty()) {
            insertSections(sections)
            insertItems(items)
        }
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
