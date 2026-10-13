package com.mymoney.core.data.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.security.PinPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores a PBKDF2-HMAC-SHA256 hash of the PIN (random 16-byte salt) — never the PIN — together with the
 * failure counter, encrypted as a whole with Tink AEAD under the Keystore-wrapped key (docs/SECURITY.md §PIN).
 * Encrypting the record ties it to this device and prevents editing the counter to skip delays.
 */
@Singleton
class PinStore internal constructor(
    private val dir: File,
    private val aead: () -> Aead,
    private val clock: TimeProvider,
) {
    @Inject constructor(@ApplicationContext context: Context, crypto: CryptoProvider, clock: TimeProvider) :
        this(context.noBackupFilesDir, { crypto.aead }, clock)

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsBeforeDelay: Int) : Result
        data class LockedOut(val untilMillis: Long) : Result
    }

    private data class Record(val salt: ByteArray, val iterations: Int, val hash: ByteArray, val failures: Int, val lockedUntil: Long)

    private val file: File get() = File(dir, FILE)

    fun hasPin(): Boolean = file.exists()

    /** Current lockout end (0 when not locked out), for showing a countdown. */
    @Synchronized
    fun lockedUntil(): Long = load()?.lockedUntil?.takeIf { it > clock.nowMillis() } ?: 0

    @Synchronized
    fun setPin(pin: String) {
        require(PinPolicy.validateNew(pin) == null) { "invalid PIN" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        save(Record(salt, ITERATIONS, derive(pin, salt, ITERATIONS), 0, 0))
    }

    @Synchronized
    fun verify(pin: String): Result {
        val r = load() ?: return Result.Wrong(0)
        val now = clock.nowMillis()
        if (r.lockedUntil > now) return Result.LockedOut(r.lockedUntil)
        return if (MessageDigest.isEqual(derive(pin, r.salt, r.iterations), r.hash)) {
            if (r.failures != 0 || r.lockedUntil != 0L) save(r.copy(failures = 0, lockedUntil = 0))
            Result.Ok
        } else {
            val failures = r.failures + 1
            val delay = PinPolicy.lockoutMillis(failures)
            save(r.copy(failures = failures, lockedUntil = if (delay > 0) now + delay else 0))
            if (delay > 0) Result.LockedOut(now + delay) else Result.Wrong(PinPolicy.FREE_ATTEMPTS - failures)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun load(): Record? {
        if (!file.exists()) return null
        val text = String(aead().decrypt(file.readBytes(), AD), Charsets.UTF_8)
        val p = text.split('|')
        return Record(b64d(p[1]), p[2].toInt(), b64d(p[3]), p[4].toInt(), p[5].toLong())
    }

    private fun save(r: Record) {
        val text = listOf("1", b64(r.salt), r.iterations.toString(), b64(r.hash), r.failures.toString(), r.lockedUntil.toString()).joinToString("|")
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeBytes(aead().encrypt(text.toByteArray(Charsets.UTF_8), AD))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not store PIN" }
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val FILE = "pin.enc"
        /** OWASP 2023 guidance for PBKDF2-HMAC-SHA256 is 600k; the PIN record is additionally Keystore-encrypted. */
        const val ITERATIONS = 210_000
        val AD = "mymoney-pin-v1".toByteArray()
    }
}
