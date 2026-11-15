package dev.jdtech.jellyfin.models

import dev.jdtech.jellyfin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

data class FindroidPerson(
    val id: UUID,
    val name: String,
    val overview: String,
    val images: FindroidImages,
    val providerIds: Map<String, String> = emptyMap(),
)

fun BaseItemDto.toFindroidPerson(repository: JellyfinRepository): FindroidPerson {
    return FindroidPerson(
        id = id,
        name = name.orEmpty(),
        overview = overview.orEmpty(),
        images = toFindroidImages(repository),
        providerIds =
            providerIds?.filterValues { !it.isNullOrBlank() }?.mapValues { it.value!! }
                ?: emptyMap(),
    )
}
