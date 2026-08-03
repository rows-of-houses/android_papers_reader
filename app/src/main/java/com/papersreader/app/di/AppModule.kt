package com.papersreader.app.di

import android.content.Context
import androidx.room.Room
import com.papersreader.app.data.db.AnnotationDao
import com.papersreader.app.data.db.AppDatabase
import com.papersreader.app.data.db.MIGRATION_1_2
import com.papersreader.app.data.db.MIGRATION_2_3
import com.papersreader.app.data.db.PaperDao
import com.papersreader.app.logging.FileLogTree
import com.papersreader.app.logging.FileLogTreeHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "papers-reader.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun providePaperDao(db: AppDatabase): PaperDao = db.paperDao()

    @Provides
    fun provideAnnotationDao(db: AppDatabase): AnnotationDao = db.annotationDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideFileLogTree(@ApplicationContext context: Context): FileLogTree =
        FileLogTreeHolder.tree(File(context.filesDir, "logs"))
}
