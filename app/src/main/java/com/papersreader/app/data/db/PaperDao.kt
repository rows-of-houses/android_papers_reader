package com.papersreader.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PaperDao {
    @Query("SELECT * FROM papers ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<PaperEntity>>

    @Query("SELECT * FROM papers WHERE id = :id")
    suspend fun getById(id: Long): PaperEntity?

    @Query("SELECT * FROM papers WHERE id = :id")
    fun observeById(id: Long): Flow<PaperEntity?>

    @Insert
    suspend fun insert(paper: PaperEntity): Long

    @Update
    suspend fun update(paper: PaperEntity)

    @Delete
    suspend fun delete(paper: PaperEntity)

    @Query("UPDATE papers SET lastPage = :page, lastZoom = :zoom, lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun updateReadingPosition(id: Long, page: Int, zoom: Float, openedAt: Long)

    @Query("SELECT * FROM papers WHERE fileName = :fileName LIMIT 1")
    suspend fun findByFileName(fileName: String): PaperEntity?
}
