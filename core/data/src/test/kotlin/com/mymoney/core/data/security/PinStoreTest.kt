package com.mymoney.core.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.mymoney.core.domain.common.TimeProvider
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric has no Android Keystore, so the store runs with an in-memory Tink key here. */
@RunWith(AndroidJUnit4::class)
class PinStoreTest {
    private val clock = object : TimeProvider {
        var t = 1_000_000L
        override fun nowMillis() = t
        override fun today(): LocalDate = LocalDate.of(2026, 10, 14)
    }
    private val aead: Aead = run {
        AeadConfig.register()
        KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
    private val dir: File = Files.createTempDirectory("pin").toFile()
    private val store = PinStore(dir, { aead }, clock)

    @Test
    fun `the PIN is never stored in clear and verifies only when correct`() {
        assertFalse(store.hasPin())
        store.setPin("2580")
        assertTrue(store.hasPin())
        val bytes = File(dir, "pin.enc").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("2580"))
        assertIs<PinStore.Result.Ok>(store.verify("2580"))
        assertEquals(PinStore.Result.Wrong(4), store.verify("1111"))
    }

    @Test
    fun `failures lead to growing delays that survive a restart, and success resets them`() {
        store.setPin("2580")
        repeat(4) { assertIs<PinStore.Result.Wrong>(store.verify("0000")) }
        val locked = assertIs<PinStore.Result.LockedOut>(store.verify("0000"))
        assertEquals(clock.t + 30_000, locked.untilMillis)
        // Even the right PIN is refused during the delay; a new instance (app restart) keeps the state.
        val restarted = PinStore(dir, { aead }, clock)
        assertIs<PinStore.Result.LockedOut>(restarted.verify("2580"))
        clock.t += 30_001
        assertIs<PinStore.Result.LockedOut>(restarted.verify("0000")) // 6th failure → 60 s
        assertEquals(60_000, restarted.lockedUntil() - clock.t)
        clock.t += 60_001
        assertIs<PinStore.Result.Ok>(restarted.verify("2580"))
        assertEquals(PinStore.Result.Wrong(4), restarted.verify("9999")) // counter was reset
    }

    @Test
    fun `a record encrypted with another key is rejected`() {
        store.setPin("2580")
        val other = run { KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(RegistryConfiguration.get(), Aead::class.java) }
        val foreign = PinStore(dir, { other }, clock)
        val failed = runCatching { foreign.verify("2580") }
        assertTrue(failed.isFailure)
        store.clear()
        assertFalse(store.hasPin())
    }
}
