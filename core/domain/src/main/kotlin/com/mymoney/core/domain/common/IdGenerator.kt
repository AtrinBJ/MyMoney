package com.mymoney.core.domain.common

import java.security.SecureRandom
import javax.inject.Inject

/** Produces stable, globally unique record ids (docs/03-data-model.md §3.1). */
fun interface IdGenerator {
    fun newId(): String
}

/**
 * RFC 9562 UUID version 7: 48-bit Unix-millis timestamp + 74 random bits.
 * Time-ordered ids keep B-tree inserts local and make ids sortable by creation time.
 */
class UuidV7Generator @Inject constructor(
    private val clock: TimeProvider,
) : IdGenerator {
    private val random = SecureRandom()

    override fun newId(): String {
        val millis = clock.nowMillis() and 0xFFFF_FFFF_FFFFL
        val randA = random.nextInt(1 shl 12).toLong()
        val randB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL
        val msb = (millis shl 16) or (0x7L shl 12) or randA
        val lsb = (0x2L shl 62) or randB
        return java.util.UUID(msb, lsb).toString()
    }
}
