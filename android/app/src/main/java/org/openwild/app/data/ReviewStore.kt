package org.openwild.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The user's own ratings and notes. Stored only in app-private storage.
 * There is no account and nothing is uploaded.
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
