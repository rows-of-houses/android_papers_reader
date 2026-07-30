package com.papersreader.app

import android.app.Application
import com.papersreader.app.logging.CrashHandler
import com.papersreader.app.logging.FileLogTreeHolder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File

@HiltAndroidApp
class PapersReaderApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val logDir = File(filesDir, "logs")
        Timber.plant(FileLogTreeHolder.tree(logDir))
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(logDir))

        PDFBoxResourceLoader.init(applicationContext)

        Timber.i("PapersReaderApp started")
    }
}
