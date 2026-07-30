package com.papersreader.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserTabDao {
    @Query("SELECT * FROM browser_tabs ORDER BY position")
    fun observeAll(): Flow<List<BrowserTabEntity>>

    @Insert
    suspend fun insert(tab: BrowserTabEntity): Long

    @Update
    suspend fun update(tab: BrowserTabEntity)

    @Delete
    suspend fun delete(tab: BrowserTabEntity)

    @Query("UPDATE browser_tabs SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE browser_tabs SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: Long)

    @Query("DELETE FROM browser_tabs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM browser_tabs")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM browser_tabs WHERE isActive = 1")
    suspend fun activeCount(): Int

    @Query("SELECT id FROM browser_tabs ORDER BY position LIMIT 1")
    suspend fun firstTabId(): Long?
}
