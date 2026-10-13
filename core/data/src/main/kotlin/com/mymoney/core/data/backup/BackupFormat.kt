package com.mymoney.core.data.backup

import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable

/**
 * `.mmbak` container (docs/BACKUP_RESTORE.md):
 *
 * ```
 * "MMBK" | version:1 byte | mode:1 byte | [mode=PASSWORD: salt:16 bytes | iterations:4 bytes BE] | body
 * body = ZIP(manifest.json, data.json, attachments/<name>)            mode PLAIN
 *      = AES-256-GCM-HKDF streaming(ZIP), key = PBKDF2(password)       mode PASSWORD
 *      = AES-256-GCM-HKDF streaming(ZIP), Keystore-wrapped device key  mode DEVICE (safety backups only)
 * ```
 * The header bytes are the associated data of the encryption, so they cannot be altered undetected.
 * No custom cryptography: PBKDF2-HMAC-SHA256 (JCA) and Tink's streaming AEAD.
 */
object BackupFormat {
    val MAGIC = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    const val CONTAINER_VERSION = 1
    const val FORMAT_VERSION = 1
    const val PBKDF2_ITERATIONS = 600_000
    private const val SEGMENT_SIZE = 64 * 1024

    enum class Mode(val code: Int) { PLAIN(0), PASSWORD(1), DEVICE(2) }

    class Header(val mode: Mode, val salt: ByteArray?, val iterations: Int, val bytes: ByteArray)

    class NotABackupException : Exception("not a MyMoney backup")
    class UnsupportedVersionException : Exception("backup made by a newer app version")

    fun newHeader(mode: Mode): Header {
        val salt = if (mode == Mode.PASSWORD) ByteArray(16).also { SecureRandom().nextBytes(it) } else null
        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(CONTAINER_VERSION)
        out.write(mode.code)
        if (salt != null) {
            out.write(salt)
            java.io.DataOutputStream(out).writeInt(PBKDF2_ITERATIONS)
        }
        return Header(mode, salt, PBKDF2_ITERATIONS, out.toByteArray())
    }

    fun readHeader(input: InputStream): Header {
        val data = DataInputStream(input)
        val magic = ByteArray(4)
        try {
            data.readFully(magic)
        } catch (_: EOFException) {
            throw NotABackupException()
        }
        if (!magic.contentEquals(MAGIC)) throw NotABackupException()
        val version = data.read()
        if (version != CONTAINER_VERSION) throw UnsupportedVersionException()
        val modeCode = data.read()
        val mode = Mode.entries.firstOrNull { it.code == modeCode } ?: throw NotABackupException()
        val out = java.io.ByteArrayOutputStream().apply { write(MAGIC); write(version); write(mode.code) }
        if (mode != Mode.PASSWORD) return Header(mode, null, 0, out.toByteArray())
        val salt = ByteArray(16).also { data.readFully(it) }
        val iterations = data.readInt()
        if (iterations !in 100_000..10_000_000) throw NotABackupException()
        out.write(salt)
        java.io.DataOutputStream(out).writeInt(iterations)
        return Header(mode, salt, iterations, out.toByteArray())
    }

    fun passwordAead(password: CharArray, header: Header): StreamingAead {
        val spec = PBEKeySpec(password, header.salt, header.iterations, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return AesGcmHkdfStreaming(key, "HmacSha256", 32, SEGMENT_SIZE, 0).also { key.fill(0) }
    }

    fun encryptingBody(out: OutputStream, header: Header, password: CharArray?, device: () -> StreamingAead): OutputStream = when (header.mode) {
        Mode.PLAIN -> out
        Mode.PASSWORD -> passwordAead(requireNotNull(password), header).newEncryptingStream(out, header.bytes)
        Mode.DEVICE -> device().newEncryptingStream(out, header.bytes)
    }

    fun decryptingBody(input: InputStream, header: Header, password: CharArray?, device: () -> StreamingAead): InputStream = when (header.mode) {
        Mode.PLAIN -> input
        Mode.PASSWORD -> passwordAead(requireNotNull(password), header).newDecryptingStream(input, header.bytes)
        Mode.DEVICE -> device().newDecryptingStream(input, header.bytes)
    }
}

/** manifest.json — what a backup contains, checked before anything is restored. */
@Serializable
data class BackupManifest(
    val format: String = "mymoney-backup",
    val formatVersion: Int = BackupFormat.FORMAT_VERSION,
    val schemaVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val dataSha256: String,
    /** Row count per table. */
    val counts: Map<String, Int>,
    val attachmentCount: Int,
    /** Epoch days of the oldest and newest transaction, if any. */
    val entryDateMin: Long? = null,
    val entryDateMax: Long? = null,
)
