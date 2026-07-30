package com.papersreader.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PaperEntity::class, AnnotationEntity::class, BrowserTabEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun browserTabDao(): BrowserTabDao
}
