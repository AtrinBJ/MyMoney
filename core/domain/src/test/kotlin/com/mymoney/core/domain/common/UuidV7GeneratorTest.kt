package com.mymoney.core.domain.common

import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class UuidV7GeneratorTest {
    private class FixedClock(var millis: Long) : TimeProvider {
        override fun nowMillis() = millis
        override fun today(): LocalDate = LocalDate.of(2026, 1, 1)
    }

    @Test
    fun `ids are version 7 and RFC variant`() {
        val id = UUID.fromString(UuidV7Generator(FixedClock(1_700_000_000_000)).newId())
        assertEquals(7, id.version())
        assertEquals(2, id.variant())
    }

    @Test
    fun `timestamp prefix makes ids sortable by creation time`() {
        val clock = FixedClock(1_700_000_000_000)
        val gen = UuidV7Generator(clock)
        val ids = (0 until 50).map { clock.millis += 1; gen.newId() }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `ids are unique`() {
        val gen = UuidV7Generator(FixedClock(1_700_000_000_000))
        val ids = (0 until 10_000).map { gen.newId() }.toSet()
        assertTrue(ids.size == 10_000)
    }
}
