package com.aiqulabs.abcd.dj.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.aiqulabs.abcd.dj.data.db.DJ_DATABASE_FILE
import com.aiqulabs.abcd.dj.data.db.DjDatabase
import kotlinx.coroutines.Dispatchers

/**
 * Entry point to the DJ database on Android: one database and one repository for the whole app.
 *
 * Deliberately not part of Metrolist's Koin setup, so no upstream file has to change.
 */
object DjData {
    @Volatile
    private var database: DjDatabase? = null

    @Volatile
    private var repository: DjAnalysisRepository? = null

    /** The app-wide database (created on first use). */
    fun database(context: Context): DjDatabase =
        database ?: synchronized(this) {
            database ?: build(context.applicationContext).also { database = it }
        }

    /** The app-wide repository. */
    fun analysisRepository(context: Context): DjAnalysisRepository =
        repository ?: synchronized(this) {
            repository ?: DjAnalysisRepository(database(context).dao()).also { repository = it }
        }

    /** Builds a database at [path]; tests use this with their own file. */
    fun build(
        context: Context,
        path: String = context.getDatabasePath(DJ_DATABASE_FILE).absolutePath,
    ): DjDatabase =
        Room
            .databaseBuilder<DjDatabase>(context = context.applicationContext, name = path)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
}
