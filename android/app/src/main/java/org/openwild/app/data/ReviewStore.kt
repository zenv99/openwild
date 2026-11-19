package org.openwild.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The user's own ratings, notes and ID corrections. Stored only in app-private storage.
 * There is no account; nothing is uploaded unless the user taps "Send" on a correction,
 * which opens a pre-filled report they can read and edit first.
 */
class ReviewStore(context: Context) {
    data class Review(val rating: Int, val body: String, val updatedAt: Long)

    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(
        File(context.filesDir, "reviews.db"),
        null,
    )

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS review (
                region_id TEXT NOT NULL,
                scientific_name TEXT NOT NULL,
                rating INTEGER NOT NULL,
                body TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (region_id, scientific_name)
            )""",
        )
    }

    /** "The app said X; it was really Y" — kept on the phone to improve matching once shared. */
    data class Correction(
        val id: Long, val regionId: String, val packVersion: Int, val modelId: String,
        val predicted: String, val chosen: String, val chosenRank: Int, val topMatches: String,
        val photoCount: Int, val createdAt: Long, val sent: Boolean,
        /** Model that produced the result: standard or bioclip. */
        val brain: String = "",
        /** What was selected, when that differs from [brain]. */
        val brainRequested: String = "",
        /** several_crops or one_crop. */
        val scanDetail: String = "",
    )

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS correction (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                region_id TEXT NOT NULL, pack_version INTEGER NOT NULL, model_id TEXT NOT NULL,
                predicted TEXT NOT NULL, chosen TEXT NOT NULL, chosen_rank INTEGER NOT NULL,
                top_matches TEXT NOT NULL, photo_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL, sent INTEGER NOT NULL DEFAULT 0,
                brain TEXT NOT NULL DEFAULT '',
                brain_requested TEXT NOT NULL DEFAULT '',
                scan_detail TEXT NOT NULL DEFAULT ''
            )""",
        )
        ensureColumn("correction", "brain", "TEXT NOT NULL DEFAULT ''")
        ensureColumn("correction", "brain_requested", "TEXT NOT NULL DEFAULT ''")
        ensureColumn("correction", "scan_detail", "TEXT NOT NULL DEFAULT ''")
    }

    private fun ensureColumn(table: String, column: String, decl: String) {
        val names = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(1))
            }
        }
        if (column !in names) db.execSQL("ALTER TABLE $table ADD COLUMN $column $decl")
    }

    fun saveCorrection(c: Correction): Long {
        db.execSQL(
            "INSERT INTO correction (region_id, pack_version, model_id, predicted, chosen, chosen_rank, top_matches, photo_count, created_at, sent, brain, brain_requested, scan_detail) VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?)",
            arrayOf(c.regionId, c.packVersion.toLong(), c.modelId, c.predicted, c.chosen, c.chosenRank.toLong(), c.topMatches, c.photoCount.toLong(), c.createdAt, c.brain, c.brainRequested, c.scanDetail),
        )
        return db.rawQuery("SELECT last_insert_rowid()", null).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    }

    fun markSent(id: Long) = db.execSQL("UPDATE correction SET sent = 1 WHERE id = ?", arrayOf(id))

    fun unsentCorrections(): List<Correction> =
        db.rawQuery("SELECT id, region_id, pack_version, model_id, predicted, chosen, chosen_rank, top_matches, photo_count, created_at, sent, brain, brain_requested, scan_detail FROM correction WHERE sent = 0 ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Correction(
                        c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5),
                        c.getInt(6), c.getString(7), c.getInt(8), c.getLong(9), c.getInt(10) == 1,
                        c.getString(11) ?: "", c.getString(12) ?: "", c.getString(13) ?: "",
                    ),
                )
            }
        }

    fun get(regionId: String, scientificName: String): Review? =
        db.rawQuery(
            "SELECT rating, body, updated_at FROM review WHERE region_id = ? AND scientific_name = ?",
            arrayOf(regionId, scientificName),
        ).use { c ->
            if (!c.moveToFirst()) null else Review(c.getInt(0), c.getString(1), c.getLong(2))
        }

    fun save(regionId: String, scientificName: String, rating: Int, body: String) {
        val stars = rating.coerceIn(1, 5)
        val text = body.trim().take(4000)
        db.execSQL(
            "INSERT OR REPLACE INTO review (region_id, scientific_name, rating, body, updated_at) VALUES (?,?,?,?,?)",
            arrayOf(regionId, scientificName, stars.toLong(), text, System.currentTimeMillis()),
        )
    }
}
