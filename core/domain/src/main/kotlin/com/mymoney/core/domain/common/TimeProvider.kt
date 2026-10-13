package com.mymoney.core.domain.common

import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Abstraction over the clock so date-dependent rules are testable. */
interface TimeProvider {
    fun nowMillis(): Long

    /** Today's civil date in the device time zone. Entry dates are civil dates, not instants (FBR-25). */
    fun today(): LocalDate
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}
