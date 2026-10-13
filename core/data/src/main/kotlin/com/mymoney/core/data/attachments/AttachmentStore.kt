package com.mymoney.core.data.attachments

import android.content.Context
import com.google.crypto.tink.StreamingAead
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.entity.AttachmentEntity
import com.mymoney.core.data.security.CryptoProvider
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Receipts and other files attached to an operation, stored only encrypted (Tink AES-GCM-HKDF streaming
 * under the Keystore-wrapped key) in app-private storage (docs/SECURITY.md §Attachments).
 * File names on disk are random ids; the original name lives only in the encrypted database.
 */
@Singleton
class AttachmentStore internal constructor(
    private val dir: File,
    private val streaming: () -> StreamingAead,
    private val db: MyMoneyDatabase,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : com.mymoney.core.domain.repository.AttachmentCleaner {
    @Inject constructor(
        @ApplicationContext context: Context,
        crypto: CryptoProvider,
        db: MyMoneyDatabase,
        ids: IdGenerator,
        clock: TimeProvider,
    ) : this(File(context.filesDir, "attachments"), { crypto.streamingAead }, db, ids, clock)

    fun observe(groupId: String): Flow<List<AttachmentEntity>> = db.attachmentDao().observeFor(groupId)

    /** Encrypts [input] into a new attachment of [groupId]. Rejects files over [MAX_BYTES]. */
    suspend fun add(groupId: String, displayName: String, mimeType: String, input: InputStream): AttachmentEntity {
        dir.mkdirs()
        val id = ids.newId()
        val stored = "a-$id"
        val file = File(dir, stored)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            streaming().newEncryptingStream(file.outputStream(), ad(stored)).use { out ->
                DigestInputStream(input, digest).use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > MAX_BYTES) throw TooLargeException()
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        val entity = AttachmentEntity(
            id, groupId, stored, displayName.take(120).ifBlank { "file" }, mimeType, size,
            digest.digest().joinToString("") { "%02x".format(it) }, clock.nowMillis(),
        )
        db.attachmentDao().insert(entity)
        return entity
    }

    /** Decrypted content of an attachment (caller closes the stream). */
    fun open(a: AttachmentEntity): InputStream = streaming().newDecryptingStream(File(dir, a.storedName).inputStream(), ad(a.storedName))

    suspend fun delete(a: AttachmentEntity) {
        db.attachmentDao().delete(a.id)
        File(dir, a.storedName).delete()
    }

    /** Removes attachments of operations that were permanently deleted. Returns the number removed. */
    override suspend fun purgeOrphans(): Int {
        val orphans = db.attachmentDao().orphans()
        orphans.forEach { delete(it) }
        return orphans.size
    }

    /** Files of a restore are written here first and swapped in only after the database commit. */
    internal fun stagingDir(): File = File(dir.parentFile, "attachments.restore").apply { deleteRecursively(); mkdirs() }

    internal fun encryptInto(target: File, input: InputStream) {
        streaming().newEncryptingStream(target.outputStream(), ad(target.name)).use { out -> input.copyTo(out) }
    }

    /** Replaces the attachment directory with [staged] (after a successful restore). */
    internal fun swapIn(staged: File) {
        val old = File(dir.parentFile, "attachments.old")
        old.deleteRecursively()
        if (dir.exists()) check(dir.renameTo(old)) { "could not move old attachments" }
        check(staged.renameTo(dir)) { "could not move restored attachments" }
        old.deleteRecursively()
    }

    internal fun deleteAll() {
        dir.deleteRecursively()
    }

    private fun ad(name: String) = "mymoney-attachment-v1:$name".toByteArray()

    class TooLargeException : Exception("attachment too large")

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
