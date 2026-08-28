package com.papersreader.app.data.network

import kotlinx.serialization.Serializable

@Serializable
data class SemanticScholarSearchResponse(val data: List<SemanticScholarSearchHit> = emptyList())

@Serializable
data class SemanticScholarSearchHit(val paperId: String, val title: String? = null)

@Serializable
data class SemanticScholarCitationsResponse(val data: List<SemanticScholarCitationEdge> = emptyList())

@Serializable
data class SemanticScholarCitationEdge(val citingPaper: SemanticScholarPaper? = null)

@Serializable
data class SemanticScholarPaper(
    val paperId: String,
    val title: String? = null,
    val year: Int? = null,
    val authors: List<SemanticScholarAuthor> = emptyList(),
    val externalIds: SemanticScholarExternalIds? = null,
    val openAccessPdf: SemanticScholarOpenAccessPdf? = null,
)

@Serializable
data class SemanticScholarAuthor(val name: String? = null)

@Serializable
data class SemanticScholarExternalIds(val DOI: String? = null, val ArXiv: String? = null)

@Serializable
data class SemanticScholarOpenAccessPdf(val url: String? = null)
