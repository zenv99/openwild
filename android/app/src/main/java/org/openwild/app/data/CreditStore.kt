package org.openwild.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.openwild.app.core.Attribution
import org.openwild.app.core.CoarseLocation
import org.openwild.app.core.PhotoLicense
import org.openwild.app.core.QueuedSubmission
import java.io.File
import java.util.UUID

/**
 * The name a person asked the phone to remember, and the on-phone send queue.
 * Nothing here is an account, and nothing is uploaded by itself.
 */
class CreditStore(context: Context) {
    private val prefs = context.getSharedPreferences("credit", Context.MODE_PRIVATE)
    private val root = File(context.filesDir, "credit").apply { mkdirs() }
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "submissions.db"), null)

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS submission (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                species TEXT NOT NULL,
                region_id TEXT NOT NULL,
                body TEXT NOT NULL,
                rating INTEGER NOT NULL,
                credit TEXT NOT NULL,
                title TEXT NOT NULL,
                affiliation TEXT NOT NULL,
                license TEXT NOT NULL,
                status TEXT NOT NULL,
                share_location INTEGER NOT NULL DEFAULT 0,
                coarse_lon REAL,
                coarse_lat REAL,
                created_at INTEGER NOT NULL,
                has_avatar INTEGER NOT NULL DEFAULT 0
            )""",
        )
    }

    data class Remembered(
        val name: String,
        val title: String,
        val affiliation: String,
        val avatarJpeg: ByteArray?,
    )

    /** Null unless the person checked "Remember my name". */
    fun remembered(): Remembered? {
        if (!prefs.getBoolean("remember", false)) return null
        return Remembered(
            name = prefs.getString("name", "") ?: "",
            title = prefs.getString("title", "") ?: "",
            affiliation = prefs.getString("affiliation", "") ?: "",
            avatarJpeg = avatarFile().takeIf { it.exists() }?.readBytes(),
        )
    }

    fun remember(name: String, title: String, affiliation: String, avatarJpeg: ByteArray?) {
        prefs.edit()
            .putBoolean("remember", true)
            .putString("name", name.trim().take(60))
            .putString("title", title.trim().take(80))
            .putString("affiliation", affiliation.trim().take(120))
            .apply()
        if (avatarJpeg != null && avatarJpeg.isNotEmpty()) avatarFile().writeBytes(avatarJpeg) else avatarFile().delete()
    }

    /** Wipes the remembered name, title, affiliation, and picture. The install id stays. */
    fun clearSaved() {
        prefs.edit().remove("remember").remove("name").remove("title").remove("affiliation").apply()
        avatarFile().delete()
    }

    /**
     * A random id created on this install. It is not derived from the person.
     * The server uses it only to rate-limit and to let this phone delete its own sends.
     */
    fun installId(): String {
        val existing = prefs.getString("install_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("install_id", created).apply()
        return created
    }

    fun enqueue(
        kind: String,
        species: String,
        regionId: String,
        body: String,
        rating: Int,
        typedName: String,
        title: String,
        affiliation: String,
        licenseCode: String?,
        avatarJpeg: ByteArray?,
        shareLocation: Boolean = false,
        longitude: Double? = null,
        latitude: Double? = null,
    ): Long {
        val credit = Attribution.creditName(typedName)
        val anonymous = credit == Attribution.ANONYMOUS
        val keptTitle = if (anonymous) "" else title.trim().take(80)
        val keptAffiliation = if (anonymous) "" else affiliation.trim().take(120)
        val avatar = Attribution.avatarBytes(typedName, avatarJpeg)
        val license = (PhotoLicense.fromCode(licenseCode) ?: PhotoLicense.DEFAULT).code
        val cell = CoarseLocation.cell(shareLocation, longitude, latitude)
        if (kind == "review") {
            val existing = db.rawQuery(
                "SELECT id FROM submission WHERE kind = 'review' AND region_id = ? AND species = ? AND status = 'queued' ORDER BY id DESC LIMIT 1",
                arrayOf(regionId, species),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (existing != null) {
                writeAvatar(existing, avatar)
                db.execSQL(
                    """UPDATE submission SET body = ?, rating = ?, credit = ?, title = ?, affiliation = ?, license = ?,
                       share_location = ?, coarse_lon = ?, coarse_lat = ?, created_at = ?, has_avatar = ? WHERE id = ?""",
                    arrayOf(
                        body.take(4000), rating.coerceIn(0, 5).toLong(), credit, keptTitle, keptAffiliation, license,
                        if (cell == null) 0L else 1L, cell?.first, cell?.second, System.currentTimeMillis(),
                        if (avatar != null) 1L else 0L, existing,
                    ),
                )
                return existing
            }
        }
        db.execSQL(
            """INSERT INTO submission (kind, species, region_id, body, rating, credit, title, affiliation, license, status,
               share_location, coarse_lon, coarse_lat, created_at, has_avatar)
               VALUES (?,?,?,?,?,?,?,?,?, 'queued', ?,?,?,?,?)""",
            arrayOf(
                kind, species.take(200), regionId.take(40), body.take(4000), rating.coerceIn(0, 5).toLong(),
                credit, keptTitle, keptAffiliation, license,
                if (cell == null) 0L else 1L, cell?.first, cell?.second, System.currentTimeMillis(),
                if (avatar != null) 1L else 0L,
            ),
        )
        val id = db.rawQuery("SELECT last_insert_rowid()", null).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        writeAvatar(id, avatar)
        return id
    }

    fun avatarJpeg(id: Long): ByteArray? = File(root, "queue-$id.jpg").takeIf { it.exists() }?.readBytes()

    fun pending(): List<QueuedSubmission> = query("SELECT $COLUMNS FROM submission WHERE status = 'queued' ORDER BY id")

    fun markSynced(id: Long) {
        db.execSQL("UPDATE submission SET status = 'synced' WHERE id = ?", arrayOf(id))
    }

    private fun writeAvatar(id: Long, jpeg: ByteArray?) {
        val file = File(root, "queue-$id.jpg")
        if (jpeg == null) file.delete() else file.writeBytes(jpeg)
    }

    private fun avatarFile() = File(root, "avatar.jpg")

    private fun query(sql: String): List<QueuedSubmission> = db.rawQuery(sql, null).use { c ->
        buildList {
            while (c.moveToNext()) add(
                QueuedSubmission(
                    id = c.getLong(0),
                    kind = c.getString(1),
                    species = c.getString(2),
                    regionId = c.getString(3),
                    body = c.getString(4),
                    rating = c.getInt(5),
                    credit = c.getString(6),
                    title = c.getString(7),
                    affiliation = c.getString(8),
                    license = c.getString(9),
                    status = c.getString(10),
                    shareLocation = c.getInt(11) == 1,
                    coarseLon = if (c.isNull(12)) null else c.getDouble(12),
                    coarseLat = if (c.isNull(13)) null else c.getDouble(13),
                    createdAt = c.getLong(14),
                    hasAvatar = c.getInt(15) == 1,
                ),
            )
        }
    }

    private companion object {
        const val COLUMNS =
            "id, kind, species, region_id, body, rating, credit, title, affiliation, license, status, share_location, coarse_lon, coarse_lat, created_at, has_avatar"
    }
}
