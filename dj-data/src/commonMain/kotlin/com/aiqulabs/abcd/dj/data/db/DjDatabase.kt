package com.aiqulabs.abcd.dj.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/** File name of the DJ database, next to Metrolist's `song.db`. */
const val DJ_DATABASE_FILE = "abcd_dj.db"

/** Bump together with a migration and a new exported schema in `dj-data/schemas`. */
const val DJ_DATABASE_VERSION = 1

/**
 * The DJ's own database (brief §6.1). Separate from Metrolist's `MusicDatabase`, so Metrolist's schema
 * and migrations are never touched.
 *
 * Never use destructive migration: the user's chorus edits live here and must survive upgrades.
 */
@Database(
    entities = [
        TrackAnalysisEntity::class,
        TrackSectionEntity::class,
        ChorusSegmentEntity::class,
        TrackMetaEntity::class,
        TrackTagEntity::class,
        TransitionFeedbackEntity::class,
        TrackFeedbackEntity::class,
        DjSessionEntity::class,
    ],
    version = DJ_DATABASE_VERSION,
    exportSchema = true,
)
@ConstructedBy(DjDatabaseConstructor::class)
abstract class DjDatabase : RoomDatabase() {
    abstract fun dao(): DjDao
}

@Suppress("KotlinNoActualForExpect")
expect object DjDatabaseConstructor : RoomDatabaseConstructor<DjDatabase> {
    override fun initialize(): DjDatabase
}
