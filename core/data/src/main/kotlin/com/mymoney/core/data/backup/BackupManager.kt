package com.mymoney.core.data.backup

import android.content.Context
import android.database.Cursor
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.crypto.tink.StreamingAead
import com.mymoney.core.data.attachments.AttachmentStore
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.entity.BackupRecordEntity
import com.mymoney.core.data.security.CryptoProvider
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.DigitStyle
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.settings.SettingsRepository
import com.mymoney.core.domain.settings.ThemeMode
import com.mymoney.core.domain.settings.WeekStart
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** What a backup file (or the current database) contains, for the restore summary. */
data class DataSummary(val accounts: Int, val entries: Int, val attachments: Int, val dateMin: Long?, val dateMax: Long?)

sealed interface InspectResult {
    /** Verified and unpacked; pass to [BackupManager.restore]. */
    class Ready(val manifest: BackupManifest, val summary: DataSummary, internal val staging: File) : InspectResult
    data object NeedsPassword : InspectResult
    data object WrongPassword : InspectResult
    data object NotABackup : InspectResult
    data object NewerVersion : InspectResult
    data object Corrupt : InspectResult
}

data class BackupResult(val sizeBytes: Long, val entryCount: Int, val encrypted: Boolean)

/**
 * Creates and restores `.mmbak` backups (docs/BACKUP_RESTORE.md). Data is streamed table by table so large
 * ledgers do not need to fit in memory. A restore is verified completely before anything is changed, takes
 * a safety backup of the current data first, and replaces everything in one database transaction.
 */
@Singleton
class BackupManager internal constructor(
    private val db: MyMoneyDatabase,
    private val attachments: AttachmentStore,
    private val settings: SettingsRepository,
    private val device: () -> StreamingAead,
    private val workDir: File,
    private val safetyDir: File,
    private val clock: TimeProvider,
    private val ids: IdGenerator,
    private val appVersion: String,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        db: MyMoneyDatabase,
        attachments: AttachmentStore,
        settings: SettingsRepository,
        crypto: CryptoProvider,
        clock: TimeProvider,
        ids: IdGenerator,
    ) : this(
        db, attachments, settings, { crypto.streamingAead }, File(context.cacheDir, "backup"), File(context.filesDir, "backups"),
        clock, ids, runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    /** Tables included in a backup: everything except Room's bookkeeping and this device's backup history. */
    private fun tables(): List<String> = sql.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
            "AND name NOT IN ('android_metadata', 'backup_record') ORDER BY name",
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    // ------------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------------

    /** Writes a backup to [out] (closed afterwards). With a [password] the file is encrypted. */
    suspend fun export(out: OutputStream, password: CharArray?, kind: String = "MANUAL", fileName: String = "backup.mmbak"): BackupResult =
        withContext(Dispatchers.IO) {
            val mode = when {
                kind == "SAFETY" -> BackupFormat.Mode.DEVICE
                password != null -> BackupFormat.Mode.PASSWORD
                else -> BackupFormat.Mode.PLAIN
            }
            workDir.mkdirs()
            val data = File(workDir, "data-${clock.nowMillis()}.json")
            try {
                val (manifest, attachmentRows) = writeData(data)
                val header = BackupFormat.newHeader(mode)
                var size = 0L
                val counting = object : java.io.FilterOutputStream(out) {
                    override fun write(b: Int) { size++; out.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { size += len; out.write(b, off, len) }
                }
                counting.write(header.bytes)
                BackupFormat.encryptingBody(counting, header, password, device).use { body ->
                    ZipOutputStream(body).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("data.json"))
                        data.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        attachmentRows.forEach { a ->
                            zip.putNextEntry(ZipEntry("attachments/${a.storedName}"))
                            attachments.open(a).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                val entryCount = manifest.counts["entry"] ?: 0
                db.backupRecordDao().upsert(
                    BackupRecordEntity(ids.newId(), clock.nowMillis(), fileName, size, mode != BackupFormat.Mode.PLAIN, BackupFormat.FORMAT_VERSION, entryCount, kind),
                )
                if (kind == "MANUAL") settings.update { it.copy(lastBackupAtMillis = clock.nowMillis()) }
                BackupResult(size, entryCount, mode != BackupFormat.Mode.PLAIN)
            } finally {
                data.delete()
            }
        }

    /** Streams every table into [file] as `{"schemaVersion":n,"settings":{…},"tables":{"t":{"columns":[…],"rows":[[…]]}}}`. */
    private suspend fun writeData(file: File): Pair<BackupManifest, List<com.mymoney.core.data.db.entity.AttachmentEntity>> {
        val digest = MessageDigest.getInstance("SHA-256")
        val counts = linkedMapOf<String, Int>()
        val s = settings.settings.first()
        var dateMin: Long? = null
        var dateMax: Long? = null
        // One read transaction: all tables come from the same moment.
        db.withTransaction {
            JsonWriter(DigestOutputStream(file.outputStream().buffered(), digest).writer(Charsets.UTF_8)).use { w ->
                w.beginObject()
                w.name("schemaVersion").value(sql.version.toLong())
                w.name("settings"); writeSettings(w, s)
                w.name("tables").beginObject()
                tables().forEach { t ->
                    w.name(t).beginObject()
                    var n = 0
                    sql.query("SELECT * FROM `$t`").use { c ->
                        w.name("columns").beginArray()
                        c.columnNames.forEach { w.value(it) }
                        w.endArray()
                        w.name("rows").beginArray()
                        while (c.moveToNext()) {
                            w.beginArray()
                            for (i in 0 until c.columnCount) writeCell(w, c, i)
                            w.endArray()
                            n++
                        }
                        w.endArray()
                    }
                    w.endObject()
                    counts[t] = n
                }
                w.endObject()
                w.endObject()
            }
            sql.query("SELECT MIN(date), MAX(date) FROM entry WHERE deletedAt IS NULL").use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    dateMin = c.getLong(0)
                    dateMax = c.getLong(1)
                }
            }
        }
        val rows = db.attachmentDao().all()
        val manifest = BackupManifest(
            schemaVersion = sql.version,
            createdAt = clock.nowMillis(),
            appVersion = appVersion,
            dataSha256 = digest.digest().hex(),
            counts = counts,
            attachmentCount = rows.size,
            entryDateMin = dateMin,
            entryDateMax = dateMax,
        )
        return manifest to rows
    }

    private fun writeCell(w: JsonWriter, c: Cursor, i: Int) {
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> w.nullValue()
            Cursor.FIELD_TYPE_INTEGER -> w.value(c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> w.value(c.getDouble(i))
            Cursor.FIELD_TYPE_BLOB -> w.value("base64:" + android.util.Base64.encodeToString(c.getBlob(i), android.util.Base64.NO_WRAP))
            else -> w.value(c.getString(i))
        }
    }

    /** Display and formatting preferences travel with the data; security settings never do. */
    private fun writeSettings(w: JsonWriter, s: AppSettings) {
        w.beginObject()
        w.name("calendar").value(s.calendar.name)
        w.name("digits").value(s.digits.name)
        w.name("weekStart").value(s.weekStart.name)
        w.name("baseCurrency").value(s.baseCurrency)
        w.name("irrUnit").value(s.irrUnit.name)
        w.name("themeMode").value(s.themeMode.name)
        w.name("hiddenDashboardCards").beginArray(); s.hiddenDashboardCards.forEach { w.value(it) }; w.endArray()
        w.name("backupReminderDays").value(s.backupReminderDays.toLong())
        w.endObject()
    }

    // ------------------------------------------------------------------------------------------
    // Inspect (verify + unpack, changes nothing)
    // ------------------------------------------------------------------------------------------

    /** Reads the backup header only. */
    suspend fun needsPassword(open: () -> InputStream): Boolean? = withContext(Dispatchers.IO) {
        try {
            open().use { BackupFormat.readHeader(it).mode == BackupFormat.Mode.PASSWORD }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun inspect(open: () -> InputStream, password: CharArray?): InspectResult = withContext(Dispatchers.IO) {
        val staging = File(workDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            open().use { raw ->
                val input = BufferedInputStream(raw)
                val header = try {
                    BackupFormat.readHeader(input)
                } catch (_: BackupFormat.UnsupportedVersionException) {
                    return@withContext InspectResult.NewerVersion
                } catch (_: BackupFormat.NotABackupException) {
                    return@withContext InspectResult.NotABackup
                }
                if (header.mode == BackupFormat.Mode.PASSWORD && password == null) return@withContext InspectResult.NeedsPassword
                val body = BackupFormat.decryptingBody(input, header, password, device)
                try {
                    unzip(body, staging)
                } catch (e: IOException) {
                    // Tink reports a wrong key as an IOException on the first segment.
                    return@withContext if (header.mode == BackupFormat.Mode.PASSWORD && !File(staging, "manifest.json").exists()) {
                        InspectResult.WrongPassword
                    } else {
                        InspectResult.Corrupt
                    }
                }
            }
            val manifestFile = File(staging, "manifest.json")
            val dataFile = File(staging, "data.json")
            if (!manifestFile.exists() || !dataFile.exists()) return@withContext InspectResult.Corrupt
            val manifest = runCatching { json.decodeFromString(BackupManifest.serializer(), manifestFile.readText()) }.getOrNull()
                ?: return@withContext InspectResult.Corrupt
            if (manifest.format != "mymoney-backup") return@withContext InspectResult.NotABackup
            if (manifest.formatVersion > BackupFormat.FORMAT_VERSION || manifest.schemaVersion > sql.version) return@withContext InspectResult.NewerVersion
            if (sha256(dataFile) != manifest.dataSha256) return@withContext InspectResult.Corrupt
            val attachmentFiles = File(staging, "attachments").listFiles()?.size ?: 0
            if (attachmentFiles != manifest.attachmentCount) return@withContext InspectResult.Corrupt
            InspectResult.Ready(
                manifest,
                DataSummary(manifest.counts["account"] ?: 0, manifest.counts["entry"] ?: 0, manifest.attachmentCount, manifest.entryDateMin, manifest.entryDateMax),
                staging,
            )
        } catch (e: GeneralSecurityException) {
            InspectResult.WrongPassword
        }
    }

    private fun unzip(input: InputStream, staging: File) {
        val safe = Regex("^(manifest\\.json|data\\.json|attachments/[A-Za-z0-9._-]{1,120})$")
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // Only the known layout is accepted; this also rules out path traversal ("../").
                if (e.isDirectory || !safe.matches(e.name)) continue
                val target = File(staging, e.name)
                target.parentFile?.mkdirs()
                target.outputStream().use { zip.copyTo(it) }
            }
        }
    }

    /** Counts of the data on this device now, shown next to the backup's counts. */
    suspend fun currentSummary(): DataSummary = withContext(Dispatchers.IO) {
        fun count(q: String) = sql.query(q).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val (min, max) = sql.query("SELECT MIN(date), MAX(date) FROM entry WHERE deletedAt IS NULL").use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to c.getLong(1) else null to null
        }
        DataSummary(count("SELECT COUNT(*) FROM account"), count("SELECT COUNT(*) FROM entry"), count("SELECT COUNT(*) FROM attachment"), min, max)
    }

    // ------------------------------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------------------------------

    /**
     * Replaces all data with the inspected backup. Order: safety backup → re-encrypt attachments into a
     * staging folder → one DB transaction (delete all, insert all, verify counts and foreign keys) →
     * swap attachment folder → settings. Any failure before the commit leaves the current data untouched.
     */
    suspend fun restore(ready: InspectResult.Ready): DataSummary = withContext(Dispatchers.IO) {
        safetyBackup()
        val stagedAttachments = attachments.stagingDir()
        File(ready.staging, "attachments").listFiles()?.forEach { f ->
            f.inputStream().use { attachments.encryptInto(File(stagedAttachments, f.name), it) }
        }
        var restoredSettings: Map<String, Any?> = emptyMap()
        db.withTransaction {
            sql.execSQL("PRAGMA defer_foreign_keys = ON")
            val current = tables()
            current.forEach { sql.execSQL("DELETE FROM `$it`") }
            val inserted = mutableMapOf<String, Int>()
            JsonReader(File(ready.staging, "data.json").reader(Charsets.UTF_8).buffered()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "settings" -> restoredSettings = readSettings(r)
                        "tables" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                val table = r.nextName()
                                if (table in current) inserted[table] = readTable(r, table) else r.skipValue()
                            }
                            r.endObject()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            ready.manifest.counts.forEach { (t, n) ->
                if (t in current) check(inserted[t] == n) { "row count mismatch in $t" }
            }
            sql.query("PRAGMA foreign_key_check").use { c -> check(!c.moveToFirst()) { "foreign key violation in backup" } }
        }
        attachments.swapIn(stagedAttachments)
        applySettings(restoredSettings)
        ready.staging.deleteRecursively()
        currentSummary()
    }

    /** Inserts the rows of one table; columns unknown to this schema are ignored, new columns get defaults. */
    private fun readTable(r: JsonReader, table: String): Int {
        val existing = sql.query("PRAGMA table_info(`$table`)").use { c -> buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
        var columns = emptyList<String>()
        var count = 0
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "columns" -> {
                    columns = buildList { r.beginArray(); while (r.hasNext()) add(r.nextString()); r.endArray() }
                }
                "rows" -> {
                    val keep = columns.map { it in existing }
                    val names = columns.filterIndexed { i, _ -> keep[i] }
                    val stmt = sql.compileStatement(
                        "INSERT INTO `$table` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})",
                    )
                    r.beginArray()
                    while (r.hasNext()) {
                        stmt.clearBindings()
                        r.beginArray()
                        var col = 0
                        var bind = 1
                        while (r.hasNext()) {
                            if (keep.getOrElse(col) { false }) {
                                when (r.peek()) {
                                    JsonToken.NULL -> { r.nextNull(); stmt.bindNull(bind) }
                                    JsonToken.NUMBER -> {
                                        val t = r.nextString()
                                        if (t.any { it == '.' || it == 'e' || it == 'E' }) stmt.bindDouble(bind, t.toDouble()) else stmt.bindLong(bind, t.toLong())
                                    }
                                    JsonToken.BOOLEAN -> stmt.bindLong(bind, if (r.nextBoolean()) 1 else 0)
                                    else -> {
                                        val t = r.nextString()
                                        if (t.startsWith("base64:")) stmt.bindBlob(bind, android.util.Base64.decode(t.removePrefix("base64:"), android.util.Base64.NO_WRAP))
                                        else stmt.bindString(bind, t)
                                    }
                                }
                                bind++
                            } else {
                                r.skipValue()
                            }
                            col++
                        }
                        r.endArray()
                        stmt.executeInsert()
                        count++
                    }
                    r.endArray()
                    stmt.close()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return count
    }

    private fun readSettings(r: JsonReader): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            m[name] = when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> buildList { r.beginArray(); while (r.hasNext()) add(r.nextString()); r.endArray() }
                JsonToken.NULL -> { r.nextNull(); null }
                else -> r.nextString()
            }
        }
        r.endObject()
        return m
    }

    private suspend fun applySettings(m: Map<String, Any?>) {
        if (m.isEmpty()) return
        fun str(k: String) = m[k] as? String
        settings.update { s ->
            s.copy(
                calendar = str("calendar")?.let { runCatching { CalendarType.valueOf(it) }.getOrNull() } ?: s.calendar,
                digits = str("digits")?.let { runCatching { DigitStyle.valueOf(it) }.getOrNull() } ?: s.digits,
                weekStart = str("weekStart")?.let { runCatching { WeekStart.valueOf(it) }.getOrNull() } ?: s.weekStart,
                baseCurrency = str("baseCurrency") ?: s.baseCurrency,
                irrUnit = str("irrUnit")?.let { runCatching { IrrUnit.valueOf(it) }.getOrNull() } ?: s.irrUnit,
                themeMode = str("themeMode")?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: s.themeMode,
                hiddenDashboardCards = (m["hiddenDashboardCards"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: s.hiddenDashboardCards,
                backupReminderDays = str("backupReminderDays")?.toIntOrNull() ?: s.backupReminderDays,
                onboardingDone = true,
            )
        }
    }

    /** Device-encrypted copy of the current data, kept privately (newest [SAFETY_KEEP]) before a restore. */
    suspend fun safetyBackup(): File {
        safetyDir.mkdirs()
        val file = File(safetyDir, "safety-${clock.nowMillis()}.mmbak")
        file.outputStream().use { export(it, null, kind = "SAFETY", fileName = file.name) }
        safetyDir.listFiles { f -> f.name.startsWith("safety-") }?.sortedByDescending { it.name }?.drop(SAFETY_KEEP)?.forEach { it.delete() }
        return file
    }

    fun observeHistory(): kotlinx.coroutines.flow.Flow<List<BackupRecordEntity>> = db.backupRecordDao().observeAll()

    fun safetyBackups(): List<File> = safetyDir.listFiles { f -> f.name.startsWith("safety-") }?.sortedByDescending { it.name }.orEmpty()

    /** Removes temporary restore/backup files and safety backups (used by "delete all data"). */
    fun deleteLocalCopies() {
        workDir.deleteRecursively()
        safetyDir.deleteRecursively()
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val SAFETY_KEEP = 3
    }
}

