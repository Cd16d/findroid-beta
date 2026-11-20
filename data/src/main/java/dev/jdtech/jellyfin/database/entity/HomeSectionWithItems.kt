package dev.jdtech.jellyfin.database.entity

import androidx.room3.Embedded
import androidx.room3.Relation

data class HomeSectionWithItems(
    @Embedded val section: HomeSectionEntity,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["sectionCompositeId"],
    )
    val items: List<HomeItemEntity>,
)
