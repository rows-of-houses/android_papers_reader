package com.papersreader.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE paperId = :paperId ORDER BY page, createdAt")
    fun observeForPaper(paperId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE paperId = :paperId AND page = :page ORDER BY createdAt")
    fun observeForPage(paperId: Long, page: Int): Flow<List<AnnotationEntity>>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Update
    suspend fun update(annotation: AnnotationEntity)

    @Delete
    suspend fun delete(annotation: AnnotationEntity)
}
