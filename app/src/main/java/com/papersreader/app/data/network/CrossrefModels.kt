package com.papersreader.app.data.network

import kotlinx.serialization.Serializable

@Serializable
data class CrossrefResponse(val message: CrossrefMessage? = null)

@Serializable
data class CrossrefMessage(val items: List<CrossrefItem> = emptyList())

@Serializable
data class CrossrefItem(
    val DOI: String? = null,
    val title: List<String> = emptyList(),
    val URL: String? = null,
)
