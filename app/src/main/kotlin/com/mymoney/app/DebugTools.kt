package com.mymoney.app

/** A developer action shown in Settings › Developer. Only debug builds provide any (src/debug). */
class DebugAction(val label: String, val run: suspend () -> String)

/** Implemented in src/debug (seeders) and src/release (empty), so release builds contain no test-data code. */
interface DebugTools {
    val actions: List<DebugAction>
}
