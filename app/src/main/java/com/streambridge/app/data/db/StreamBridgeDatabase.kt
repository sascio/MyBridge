package com.streambridge.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExtensionEntity::class,
        LibraryItemEntity::class,
        WatchProgressEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class StreamBridgeDatabase : RoomDatabase() {

    abstract fun extensionDao(): ExtensionDao
    abstract fun libraryDao(): LibraryDao
    abstract fun progressDao(): ProgressDao

    companion object {
        fun build(context: Context): StreamBridgeDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                StreamBridgeDatabase::class.java,
                "stream_bridge.db"
            )
                // Pre-release schema evolution: local library data is
                // re-derivable, so destructive migration is acceptable here.
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
