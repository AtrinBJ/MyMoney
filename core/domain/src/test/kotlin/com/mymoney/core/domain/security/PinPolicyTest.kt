package com.mymoney.core.domain.security

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class PinPolicyTest {
    @Test
    fun `new PIN must be 4-8 digits and not trivially guessable`() {
        assertEquals(PinPolicy.Problem.LENGTH, PinPolicy.validateNew("123"))
        assertEquals(PinPolicy.Problem.LENGTH, PinPolicy.validateNew("123456789"))
        assertEquals(PinPolicy.Problem.NOT_DIGITS, PinPolicy.validateNew("12a4"))
        assertEquals(PinPolicy.Problem.NOT_DIGITS, PinPolicy.validateNew("۱۳۵۷")) // callers normalize digits first
        assertEquals(PinPolicy.Problem.TOO_SIMPLE, PinPolicy.validateNew("0000"))
        assertEquals(PinPolicy.Problem.TOO_SIMPLE, PinPolicy.validateNew("123456"))
        assertEquals(PinPolicy.Problem.TOO_SIMPLE, PinPolicy.validateNew("9876"))
        assertNull(PinPolicy.validateNew("1357"))
        assertNull(PinPolicy.validateNew("20481024"))
    }

    @Test
    fun `delays start after five failures, double, and cap at one hour`() {
        assertEquals(0, PinPolicy.lockoutMillis(4))
        assertEquals(30_000, PinPolicy.lockoutMillis(5))
        assertEquals(60_000, PinPolicy.lockoutMillis(6))
        assertEquals(120_000, PinPolicy.lockoutMillis(7))
        assertEquals(3_600_000, PinPolicy.lockoutMillis(20))
        assertEquals(3_600_000, PinPolicy.lockoutMillis(1_000))
    }
}
