package com.mymoney.core.domain.money

import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.settings.IrrUnit
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class AmountParserTest {

    private fun ok(input: String, currency: String = "USD", digits: Int = 2, unit: IrrUnit = IrrUnit.RIAL, neg: Boolean = false): Long {
        val r = AmountParser.parse(input, currency, digits, unit, neg)
        return (r as? Outcome.Success)?.value ?: error("expected success for '$input' but got $r")
    }

    private fun err(input: String, currency: String = "USD", digits: Int = 2, unit: IrrUnit = IrrUnit.RIAL, neg: Boolean = false): DomainError {
        val r = AmountParser.parse(input, currency, digits, unit, neg)
        return (r as? Outcome.Failure)?.error ?: error("expected failure for '$input' but got $r")
    }

    @Test
    fun `parses latin digits with grouping and decimals`() {
        assertEquals(125_000, ok("1,250.00"))
        assertEquals(125_050, ok("1250.5"))
        assertEquals(100, ok("1"))
        assertEquals(50, ok(".5"))
        assertEquals(100, ok("1."))
    }

    @Test
    fun `parses Persian and Arabic-Indic digits and separators`() {
        assertEquals(125_000, ok("۱٬۲۵۰٫۰۰"))
        assertEquals(125_000, ok("١٬٢٥٠٫٠٠"))
        assertEquals(1_250_000, ok("۱ ۲۵۰ ۰۰۰", currency = "IRR", digits = 0))
    }

    @Test
    fun `toman input is multiplied by exactly ten (FBR-22)`() {
        assertEquals(1_250_000, ok("125,000", currency = "IRR", digits = 0, unit = IrrUnit.TOMAN))
        assertEquals(5, ok("0.5", currency = "IRR", digits = 0, unit = IrrUnit.TOMAN))
        assertEquals(DomainError.InvalidAmount(AmountProblem.TOO_MANY_DECIMALS), err("0.25", "IRR", 0, IrrUnit.TOMAN))
        assertEquals(DomainError.InvalidAmount(AmountProblem.TOO_MANY_DECIMALS), err("10.5", "IRR", 0, IrrUnit.RIAL))
    }

    @Test
    fun `negative input requires permission`() {
        assertEquals(DomainError.InvalidAmount(AmountProblem.NEGATIVE_NOT_ALLOWED), err("-5"))
        assertEquals(-500, ok("-5", neg = true))
        assertEquals(-500, ok("−5", neg = true))
        assertEquals(-500, ok("(5)", neg = true))
    }

    @Test
    fun `rejects garbage, too many decimals and too large`() {
        assertEquals(DomainError.Required("amount"), err("   "))
        assertEquals(DomainError.InvalidAmount(AmountProblem.UNPARSABLE), err("12a"))
        assertEquals(DomainError.InvalidAmount(AmountProblem.UNPARSABLE), err("1.2.3"))
        assertEquals(DomainError.InvalidAmount(AmountProblem.TOO_MANY_DECIMALS), err("1.234"))
        assertEquals(DomainError.InvalidAmount(AmountProblem.TOO_LARGE), err("10000000000000.01"))
        assertEquals(DomainError.InvalidAmount(AmountProblem.TOO_LARGE), err("1000000000000001", "IRR", 0))
    }

    @Test
    fun `trailing zero decimals beyond currency digits are accepted`() {
        assertEquals(100, ok("1.000"))
        assertEquals(10, ok("1.0", currency = "IRR", digits = 0, unit = IrrUnit.TOMAN))
    }
}
