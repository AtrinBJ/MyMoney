package com.mymoney.core.domain.calendar

/**
 * Jalali (Solar Hijri) ⇄ Julian Day Number conversion.
 *
 * Kotlin port of jalaali-js by Behrang Noruzi Niya (MIT License,
 * https://github.com/jalaali/jalaali-js), itself based on Kazimierz M. Borkowski's algorithm with the
 * table of "break" years that matches the official Iranian calendar for Jalali years −61 … 3177.
 * Integer division here truncates toward zero exactly like the original's `~~(a / b)`.
 *
 * Verified in tests against reference Nowruz dates and day-by-day against Android's ICU Persian calendar.
 */
internal object JalaliAlgorithm {

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210,
        1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    const val MIN_YEAR = -61
    const val MAX_YEAR = 3177

    class YearInfo(val leap: Int, val gy: Int, val march: Int)

    private fun div(a: Int, b: Int): Int = a / b
    private fun mod(a: Int, b: Int): Int = a - (a / b) * b

    fun isValidYear(jy: Int): Boolean = jy >= BREAKS[0] && jy < BREAKS[BREAKS.size - 1]

    /** 0 when [jy] is a leap year (Esfand has 30 days); otherwise the years since the last leap year. */
    fun jalCal(jy: Int, withoutLeap: Boolean = false): YearInfo {
        require(isValidYear(jy)) { "Invalid Jalali year $jy" }
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp

        leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1

        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG
        if (withoutLeap) return YearInfo(leap = -1, gy = gy, march = march)

        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return YearInfo(leap = leap, gy = gy, march = march)
    }

    fun isLeap(jy: Int): Boolean = jalCal(jy).leap == 0

    fun monthLength(jy: Int, jm: Int): Int = when {
        jm <= 6 -> 31
        jm <= 11 -> 30
        isLeap(jy) -> 30
        else -> 29
    }

    /** Jalali date → Julian Day Number. */
    fun j2d(jy: Int, jm: Int, jd: Int): Int {
        val r = jalCal(jy, withoutLeap = true)
        return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1
    }

    /** Julian Day Number → Jalali (year, month, day). */
    fun d2j(jdn: Int): Triple<Int, Int, Int> {
        val gy = d2g(jdn).first
        var jy = gy - 621
        val r = jalCal(jy)
        val jdn1f = g2d(gy, 3, r.march)
        var k = jdn - jdn1f
        if (k >= 0) {
            if (k <= 185) {
                return Triple(jy, 1 + div(k, 31), mod(k, 31) + 1)
            } else {
                k -= 186
            }
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        return Triple(jy, 7 + div(k, 30), mod(k, 30) + 1)
    }

    /** Gregorian date → Julian Day Number. */
    fun g2d(gy: Int, gm: Int, gd: Int): Int {
        var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) +
            div(153 * mod(gm + 9, 12) + 2, 5) +
            gd - 34840408
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
        return d
    }

    /** Julian Day Number → Gregorian (year, month, day). */
    fun d2g(jdn: Int): Triple<Int, Int, Int> {
        var j = 4 * jdn + 139361631
        j = j + div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
        val i = div(mod(j, 1461), 4) * 5 + 308
        val gd = div(mod(i, 153), 5) + 1
        val gm = mod(div(i, 153), 12) + 1
        val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
        return Triple(gy, gm, gd)
    }

    /** Julian Day Number of 1970-01-01. */
    const val EPOCH_JDN = 2440588
}
