package com.papersreader.app.data.repository

import com.papersreader.app.data.db.AnnotationDao
import com.papersreader.app.data.db.AnnotationEntity
import com.papersreader.app.data.db.AnnotationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A rectangle normalized to 0..1 of the page's width/height, so it survives zoom/DPI changes. */
@Serializable
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class Annotation(
    val id: Long,
    val paperId: Long,
    val page: Int,
    val type: AnnotationType,
    val color: Int,
    val rects: List<NormalizedRect>,
    val note: String?,
    val createdAt: Long,
)

@Singleton
class AnnotationRepository @Inject constructor(
    private val annotationDao: AnnotationDao,
    private val json: Json,
) {
    fun observeForPaper(paperId: Long): Flow<List<Annotation>> =
        annotationDao.observeForPaper(paperId).map(::toDomainList)

    fun observeForPage(paperId: Long, page: Int): Flow<List<Annotation>> =
        annotationDao.observeForPage(paperId, page).map(::toDomainList)

    suspend fun addHighlight(paperId: Long, page: Int, rects: List<NormalizedRect>, color: Int, note: String? = null): Long =
        annotationDao.insert(
            AnnotationEntity(
                paperId = paperId,
                page = page,
                type = AnnotationType.HIGHLIGHT,
                color = color,
                geometryJson = json.encodeToString(rects),
                note = note,
                createdAt = System.currentTimeMillis(),
            )
        )

    suspend fun addNote(paperId: Long, page: Int, anchor: NormalizedRect, color: Int, note: String): Long =
        annotationDao.insert(
            AnnotationEntity(
                paperId = paperId,
                page = page,
                type = AnnotationType.NOTE,
                color = color,
                geometryJson = json.encodeToString(listOf(anchor)),
                note = note,
                createdAt = System.currentTimeMillis(),
            )
        )

    suspend fun delete(annotation: Annotation) = annotationDao.delete(
        AnnotationEntity(
            id = annotation.id,
            paperId = annotation.paperId,
            page = annotation.page,
            type = annotation.type,
            color = annotation.color,
            geometryJson = json.encodeToString(annotation.rects),
            note = annotation.note,
            createdAt = annotation.createdAt,
        )
    )

    suspend fun updateNoteText(annotation: Annotation, newText: String) = annotationDao.update(
        AnnotationEntity(
            id = annotation.id,
            paperId = annotation.paperId,
            page = annotation.page,
            type = annotation.type,
            color = annotation.color,
            geometryJson = json.encodeToString(annotation.rects),
            note = newText,
            createdAt = annotation.createdAt,
        )
    )

    private fun toDomainList(entities: List<AnnotationEntity>): List<Annotation> =
        entities.map { entity ->
            Annotation(
                id = entity.id,
                paperId = entity.paperId,
                page = entity.page,
                type = entity.type,
                color = entity.color,
                rects = runCatching { json.decodeFromString<List<NormalizedRect>>(entity.geometryJson) }.getOrDefault(emptyList()),
                note = entity.note,
                createdAt = entity.createdAt,
            )
        }
}
