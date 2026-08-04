package com.papersreader.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PaperEntity::class, AnnotationEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao
    abstract fun annotationDao(): AnnotationDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE annotations ADD COLUMN strokeWidth REAL")
    }
}

/** The in-app WebView browser (and its persisted tabs) was removed in favor of the system browser. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS browser_tabs")
    }
}

/** Lets the reader restore the zoom level a paper was last viewed at, alongside its last page. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE papers ADD COLUMN lastZoom REAL NOT NULL DEFAULT 1.0")
    }
}
