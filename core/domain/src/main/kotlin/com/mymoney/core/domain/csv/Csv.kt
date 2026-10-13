package com.mymoney.core.domain.csv

/**
 * RFC 4180 CSV reading and writing. Reading accepts a UTF-8 BOM, CRLF/LF/CR line ends, quoted fields with
 * embedded delimiters, quotes ("") and line breaks, and detects `,`, `;` or tab as the delimiter.
 */
object Csv {
    fun detectDelimiter(text: String): Char {
        val firstLine = text.removePrefix("﻿").lineSequence().firstOrNull().orEmpty()
        var inQuotes = false
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0)
        firstLine.forEach { c ->
            if (c == '"') inQuotes = !inQuotes else if (!inQuotes && c in counts) counts[c] = counts.getValue(c) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val s = text.removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = mutableListOf()
        }
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(c)
                    delimiter -> endField()
                    '\r' -> { endRow(); if (i + 1 < s.length && s[i + 1] == '\n') i++ }
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /**
     * One CSV line (CRLF terminated). Text that a spreadsheet would execute as a formula (starting with
     * = + @ or a non-numeric -) is prefixed with an apostrophe (CSV injection protection).
     */
    fun line(fields: List<String?>, delimiter: Char = ','): String = fields.joinToString(delimiter.toString()) { raw ->
        var v = raw.orEmpty()
        if (v.isNotEmpty() && (v[0] in "=+@\t\r" || (v[0] == '-' && v.drop(1).toBigDecimalOrNull() == null))) v = "'$v"
        if (v.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } || v != v.trim()) "\"" + v.replace("\"", "\"\"") + "\"" else v
    } + "\r\n"
}
