package com.papersreader.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "browser_tabs")
data class BrowserTabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String? = null,
    /** Order in the tab strip, left to right. */
    val position: Int,
    val isActive: Boolean = false,
    val createdAt: Long,
)
