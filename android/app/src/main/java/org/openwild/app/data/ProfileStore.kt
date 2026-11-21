package org.openwild.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.openwild.app.core.Attribution
import org.openwild.app.core.CoarseLocation
import org.openwild.app.core.LocalContribution
import org.openwild.app.core.LocalProfile
import org.openwild.app.core.PhotoLicense
import org.openwild.app.core.ProfileDocuments
import java.io.File

/**
 * The optional profile and the on-phone submission queue. Nothing here is uploaded.
 * [org.openwild.app.ui.ProfileSync] is the only path that can send the queue, and it is off
 * unless the build turns profiles on.
 */
class ProfileStore(context: Context) {
    private val root = File(context.filesDir, "profile").apply { mkdirs() }
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, "profile.db"), null)

    init {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS profile (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                display_name TEXT NOT NULL,
                title TEXT NOT NULL,
                affiliation TEXT NOT NULL,
                visibility TEXT NOT NULL,
                email TEXT NOT NULL,
                verified_academic INTEGER NOT NULL,
                verified_domain TEXT,
                license TEXT NOT NULL,
                has_photo INTEGER NOT NULL
            )""",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                species TEXT NOT NULL,
                region_id TEXT NOT NULL,
                body TEXT NOT NULL,
                rating INTEGER NOT NULL,
                visibility TEXT NOT NULL,
                license TEXT NOT NULL,
                credit TEXT NOT NULL,
                status TEXT NOT NULL,
                reported INTEGER NOT NULL DEFAULT 0,
                share_location INTEGER NOT NULL DEFAULT 0,
                coarse_lon REAL,
                coarse_lat REAL,
                created_at INTEGER NOT NULL
            )""",
        )
    }

    fun current(): LocalProfile? = db.rawQuery(
        "SELECT display_name, title, affiliation, visibility, email, verified_academic, verified_domain, license, has_photo FROM profile WHERE id = 1",
        null,
    ).use { c ->
        if (!c.moveToFirst()) null else LocalProfile(
            displayName = c.getString(0),
            title = c.getString(1),
            affiliation = c.getString(2),
            visibility = c.getString(3),
            email = c.getString(4),
            verifiedAcademic = c.getInt(5) == 1,
            verifiedDomain = c.getString(6)?.takeIf { it.isNotBlank() },
            license = c.getString(7),
            hasPhoto = c.getInt(8) == 1 && avatarFile().exists(),
        )
    }

    /** Writes the fields the person typed. A verified badge is kept only when the mailbox is unchanged. */
    fun save(next: LocalProfile) {
        val prev = current()
        val sameEmail = prev != null && prev.email.equals(next.email.trim(), ignoreCase = true)
        val verified = sameEmail && prev!!.verifiedAcademic
        val domain = if (verified) prev.verifiedDomain else null
        val license = (PhotoLicense.fromCode(next.license) ?: PhotoLicense.DEFAULT).code
        val visibility = if (next.visibility == "anonymous") "anonymous" else "public"
        db.execSQL(
            """INSERT OR REPLACE INTO profile
               (id, display_name, title, affiliation, visibility, email, verified_academic, verified_domain, license, has_photo)
               VALUES (1,?,?,?,?,?,?,?,?,?)""",
            arrayOf(
                next.displayName.trim().take(60),
                next.title.trim().take(80),
                next.affiliation.trim().take(120),
                visibility,
                next.email.trim().take(200),
                if (verified) 1L else 0L,
                domain,
                license,
                if (next.hasPhoto && avatarFile().exists()) 1L else 0L,
            ),
        )
    }

    fun saveAvatar(jpeg: ByteArray) {
        avatarFile().writeBytes(jpeg)
        val current = current() ?: LocalProfile()
        save(current.copy(hasPhoto = true))
    }

    fun avatarFile(): File = File(root, "avatar.jpg")

    fun add(
        kind: String,
        species: String,
        regionId: String,
        body: String,
        rating: Int,
        anonymous: Boolean,
        typedName: String,
        licenseCode: String? = null,
        shareLocation: Boolean = false,
        longitude: Double? = null,
        latitude: Double? = null,
    ): Long {
        val profile = current()
        val visibility = if (anonymous) "anonymous" else "public"
        val name = if (visibility == "anonymous") "" else typedName.ifBlank { profile?.displayName ?: "" }
        val credit = Attribution.creditName(visibility, name)
        val license = (PhotoLicense.fromCode(licenseCode) ?: PhotoLicense.fromCode(profile?.license) ?: PhotoLicense.DEFAULT).code
        val cell = if (visibility == "anonymous") null else CoarseLocation.cell(shareLocation, longitude, latitude)
        if (kind == "review") {
            val existing = db.rawQuery(
                "SELECT id FROM queue WHERE kind = 'review' AND region_id = ? AND species = ? AND status = 'queued' ORDER BY id DESC LIMIT 1",
                arrayOf(regionId, species),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (existing != null) {
                db.execSQL(
                    """UPDATE queue SET body = ?, rating = ?, visibility = ?, license = ?, credit = ?, reported = 0,
                       share_location = ?, coarse_lon = ?, coarse_lat = ?, created_at = ? WHERE id = ?""",
                    arrayOf(
                        body.take(4000), rating.coerceIn(0, 5).toLong(), visibility, license, credit,
                        if (cell == null) 0L else 1L, cell?.first, cell?.second, System.currentTimeMillis(), existing,
                    ),
                )
                return existing
            }
        }
        db.execSQL(
            """INSERT INTO queue (kind, species, region_id, body, rating, visibility, license, credit, status, reported,
               share_location, coarse_lon, coarse_lat, created_at)
               VALUES (?,?,?,?,?,?,?,?, 'queued', 0, ?,?,?,?)""",
            arrayOf(
                kind, species.take(200), regionId.take(40), body.take(4000), rating.coerceIn(0, 5).toLong(),
                visibility, license, credit, if (cell == null) 0L else 1L, cell?.first, cell?.second, System.currentTimeMillis(),
            ),
        )
        return db.rawQuery("SELECT last_insert_rowid()", null).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    }

    fun list(): List<LocalContribution> = query("SELECT ${COLUMNS} FROM queue ORDER BY id DESC LIMIT 50")

    fun pending(): List<LocalContribution> = query("SELECT ${COLUMNS} FROM queue WHERE status = 'queued' ORDER BY id")

    fun markReported(id: Long) = db.execSQL("UPDATE queue SET reported = 1, status = 'hidden' WHERE id = ?", arrayOf(id))

    fun markSynced(id: Long) = db.execSQL("UPDATE queue SET status = 'synced' WHERE id = ?", arrayOf(id))

    fun exportJson(): String = ProfileDocuments.ownerExport(current() ?: LocalProfile(), list())

    fun deleteAll() {
        db.execSQL("DELETE FROM profile")
        db.execSQL("DELETE FROM queue")
        root.listFiles()?.forEach { it.delete() }
    }

    private fun query(sql: String): List<LocalContribution> = db.rawQuery(sql, null).use { c ->
        buildList {
            while (c.moveToNext()) add(
                LocalContribution(
                    id = c.getLong(0),
                    kind = c.getString(1),
                    species = c.getString(2),
                    regionId = c.getString(3),
                    body = c.getString(4),
                    rating = c.getInt(5),
                    visibility = c.getString(6),
                    license = c.getString(7),
                    credit = c.getString(8),
                    status = c.getString(9),
                    reported = c.getInt(10) == 1,
                    shareLocation = c.getInt(11) == 1,
                    coarseLon = if (c.isNull(12)) null else c.getDouble(12),
                    coarseLat = if (c.isNull(13)) null else c.getDouble(13),
                    createdAt = c.getLong(14),
                ),
            )
        }
    }

    private companion object {
        const val COLUMNS =
            "id, kind, species, region_id, body, rating, visibility, license, credit, status, reported, share_location, coarse_lon, coarse_lat, created_at"
    }
}
