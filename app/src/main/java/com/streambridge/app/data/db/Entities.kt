package com.streambridge.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** An installed extension (Stremio-compatible addon). */
@Entity(tableName = "extensions")
data class ExtensionEntity(
    @PrimaryKey val addonId: String,
    val name: String,
    val version: String,
    val baseUrl: String,
    val manifestJson: String,
    val enabled: Boolean,
    val installedAt: Long,
    val updatedAt: Long
)

/** A library entry: favorites / watchlist plus cached display info. */
@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey val metaKey: String, // "{type}:{id}"
    val type: String,
    val id: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val releaseInfo: String?,
    val rating: String?,
    val runtime: String?,
    val description: String?,
    val genres: String?, // comma separated
    val favorite: Boolean,
    val watchlist: Boolean,
    val addedAt: Long
)

/** Watch progress and history. Also powers Continue Watching. */
@Entity(tableName = "watch_progress", primaryKeys = ["metaKey", "videoId"])
data class WatchProgressEntity(
    val metaKey: String,
    val videoId: String, // "" for movies
    val type: String,
    val metaId: String,
    val imdbId: String?,
    val metaName: String,
    val poster: String?,
    val backdrop: String?,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)
