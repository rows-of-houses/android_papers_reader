package com.papersreader.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AnnotationType { HIGHLIGHT, NOTE }

@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("paperId")],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paperId: Long,
    /** Zero-based page index. */
    val page: Int,
    val type: AnnotationType,
    /** ARGB color int. */
    val color: Int,
    /**
     * JSON array of normalized rects (0..1 relative to page width/height), e.g.
     * `[{"left":0.1,"top":0.2,"right":0.8,"bottom":0.25}]`. A NOTE uses a single zero-area
     * rect as its pin location.
     */
    val geometryJson: String,
    val note: String? = null,
    val createdAt: Long,
)
