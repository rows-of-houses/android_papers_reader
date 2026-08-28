package com.papersreader.app.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenAlexWorksResponse(val results: List<OpenAlexWork> = emptyList())

@Serializable
data class OpenAlexWork(
    val id: String? = null,
    val title: String? = null,
    val doi: String? = null,
    @SerialName("publication_year") val publicationYear: Int? = null,
    val authorships: List<OpenAlexAuthorship> = emptyList(),
    @SerialName("open_access") val openAccess: OpenAlexOpenAccess? = null,
    @SerialName("best_oa_location") val bestOaLocation: OpenAlexLocation? = null,
)

@Serializable
data class OpenAlexAuthorship(val author: OpenAlexAuthor? = null)

@Serializable
data class OpenAlexAuthor(@SerialName("display_name") val displayName: String? = null)

@Serializable
data class OpenAlexOpenAccess(@SerialName("oa_url") val oaUrl: String? = null)

@Serializable
data class OpenAlexLocation(@SerialName("pdf_url") val pdfUrl: String? = null)
