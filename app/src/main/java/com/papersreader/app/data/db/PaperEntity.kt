package com.papersreader.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "papers")
data class PaperEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Real paper title, parsed from PDF metadata/first page — never the original download filename. */
    val title: String,
    val authors: String? = null,
    val year: Int? = null,
    /** File name inside the app-private papers/ directory. */
    val fileName: String,
    /** Page the paper was downloaded from, if it came in through the in-app browser. */
    val sourceUrl: String? = null,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val lastPage: Int = 0,
    val pageCount: Int? = null,
    val fileSizeBytes: Long = 0,
)
