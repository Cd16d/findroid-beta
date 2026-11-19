package dev.jdtech.jellyfin.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "home_items",
    foreignKeys =
        [
            ForeignKey(
                entity = HomeSectionEntity::class,
                parentColumns = ["id"],
                childColumns = ["sectionCompositeId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class HomeItemEntity(
    @PrimaryKey val id: String, // "${serverId}_${userId}_${sectionId}_${itemId}"
    @ColumnInfo(index = true) val sectionCompositeId: String,
    @ColumnInfo(index = true) val serverId: String,
    @ColumnInfo(index = true) val userId: UUID,
    val sectionId: String,
    @ColumnInfo(index = true) val itemId: UUID,
    val orderIndex: Int,
    val itemType: String, // "MOVIE", "EPISODE", "SHOW", "SEASON", "BOX_SET", "FOLDER"
    val name: String,
    val originalTitle: String? = null,
    val overview: String = "",
    val played: Boolean = false,
    val favorite: Boolean = false,
    val canPlay: Boolean = true,
    val canDownload: Boolean = true,
    val runtimeTicks: Long = 0L,
    val playbackPositionTicks: Long = 0L,
    val unplayedItemCount: Int? = null,
    val seriesName: String? = null,
    val seriesId: UUID? = null,
    val seasonName: String? = null,
    val seasonId: UUID? = null,
    val indexNumber: Int? = null,
    val parentIndexNumber: Int? = null,
    val productionYear: Int? = null,
    val communityRating: Float? = null,
    val criticRating: Float? = null,
    val officialRating: String? = null,
    val status: String? = null,
    val primaryImageUri: String? = null,
    val primaryImageBlurHash: String? = null,
    val backdropImageUri: String? = null,
    val backdropImageBlurHash: String? = null,
    val logoImageUri: String? = null,
    val logoImageBlurHash: String? = null,
    val isDownloaded: Boolean = false,
)
