package dev.jdtech.jellyfin.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.User
import java.util.UUID

@Entity(
    tableName = "home_sections",
    foreignKeys =
        [
            ForeignKey(
                entity = Server::class,
                parentColumns = ["id"],
                childColumns = ["serverId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = User::class,
                parentColumns = ["id"],
                childColumns = ["userId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
)
data class HomeSectionEntity(
    @PrimaryKey val id: String, // "${serverId}_${userId}_${sectionId}"
    @ColumnInfo(index = true) val serverId: String,
    @ColumnInfo(index = true) val userId: UUID,
    val sectionId: String, // "suggestions", "resume", "next_up", or dynamic view/plugin UUID
    val sectionType: String, // "SUGGESTIONS", "RESUME", "NEXT_UP", "VIEW", or dynamic plugin type
    val title: String,
    val collectionType: String? = null,
    val orderIndex: Int,
)
