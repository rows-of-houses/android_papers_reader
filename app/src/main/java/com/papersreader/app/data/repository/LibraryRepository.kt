package com.papersreader.app.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.papersreader.app.data.db.PaperDao
import com.papersreader.app.data.db.PaperEntity
import com.papersreader.app.data.pdf.PdfTitleExtractor
import com.papersreader.app.util.TitleSanitizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paperDao: PaperDao,
) {
    private val papersDir: File
        get() = File(context.filesDir, "papers").apply { if (!exists()) mkdirs() }

    fun observePapers(): Flow<List<PaperEntity>> = paperDao.observeAll()

    suspend fun getPaper(id: Long): PaperEntity? = paperDao.getById(id)

    fun paperFile(paper: PaperEntity): File = File(papersDir, paper.fileName)

    /** Imports a PDF the user picked (SAF / share intent), storing it under its real title. */
    suspend fun importFromUri(uriString: String, suggestedFallbackName: String): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = uriString.toUri()
                val tempFile = File.createTempFile("import", ".pdf", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: error("Could not open $uriString")

                val id = importFromLocalFile(tempFile, suggestedFallbackName, sourceUrl = null)
                tempFile.delete()
                id
            }.onFailure { Timber.e(it, "Import from URI failed: $uriString") }
        }

    /** Imports PDF bytes already downloaded (e.g. from the in-app browser). */
    suspend fun importFromBytes(bytes: ByteArray, suggestedFallbackName: String, sourceUrl: String?): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tempFile = File.createTempFile("download", ".pdf", context.cacheDir)
                tempFile.writeBytes(bytes)
                val id = importFromLocalFile(tempFile, suggestedFallbackName, sourceUrl)
                tempFile.delete()
                id
            }.onFailure { Timber.e(it, "Import from downloaded bytes failed") }
        }

    private suspend fun importFromLocalFile(tempFile: File, fallbackName: String, sourceUrl: String?): Long {
        val metadata = PdfTitleExtractor.extract(tempFile, fallbackTitle = fallbackName)
        val stem = TitleSanitizer.toFileNameStem(metadata.title)
        val fileName = TitleSanitizer.dedupe(stem, "pdf") { candidate -> File(papersDir, candidate).exists() }

        val destination = File(papersDir, fileName)
        tempFile.copyTo(destination, overwrite = true)

        return paperDao.insert(
            PaperEntity(
                title = metadata.title,
                authors = metadata.authors,
                year = metadata.year,
                fileName = fileName,
                sourceUrl = sourceUrl,
                addedAt = System.currentTimeMillis(),
                pageCount = metadata.pageCount.takeIf { it > 0 },
                fileSizeBytes = destination.length(),
            )
        )
    }

    suspend fun deletePaper(paper: PaperEntity) = withContext(Dispatchers.IO) {
        paperFile(paper).delete()
        paperDao.delete(paper)
    }

    suspend fun updateReadingPosition(paperId: Long, page: Int) = withContext(Dispatchers.IO) {
        paperDao.updateReadingPosition(paperId, page, System.currentTimeMillis())
    }
}
