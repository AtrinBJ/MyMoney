package com.mymoney.app.lock

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.mymoney.core.data.security.PinStore
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App lock state (docs/SECURITY.md §App lock). The lock is on exactly when a PIN exists. It engages at
 * process start and when the app returns from the background after the auto-lock delay
 * (AppSettings.autoLockMinutes: 0 = immediately, -1 = only when the app restarts).
 *
 * This is a UI gate; the data at rest is protected independently by the Keystore-wrapped database key.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val pins: PinStore,
    private val settings: SettingsRepository,
    private val clock: TimeProvider,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _locked = MutableStateFlow(pins.hasPin())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile private var autoLockMinutes: Int = AppSettings().autoLockMinutes
    @Volatile private var backgroundAt: Long? = null
    @Volatile private var skipNextBackground = false

    init {
        scope.launch { settings.settings.collect { autoLockMinutes = it.autoLockMinutes } }
    }

    val isEnabled: Boolean get() = pins.hasPin()

    fun unlock() {
        _locked.value = false
    }

    /** Locks right away (e.g. "lock now"); no-op without a PIN. */
    fun lockNow() {
        if (pins.hasPin()) _locked.value = true
    }

    /**
     * Call before opening a system screen that sends the app to the background on purpose (file picker,
     * camera, notification settings) so returning from it does not ask for the PIN again.
     */
    fun allowNextBackground() {
        skipNextBackground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundAt = clock.nowMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (skipNextBackground) {
            skipNextBackground = false
            return
        }
        if (shouldLock(since, clock.nowMillis(), autoLockMinutes) && pins.hasPin()) _locked.value = true
    }

    companion object {
        fun shouldLock(backgroundAt: Long, now: Long, autoLockMinutes: Int): Boolean = when {
            autoLockMinutes < 0 -> false
            autoLockMinutes == 0 -> true
            else -> now - backgroundAt >= autoLockMinutes * 60_000L
        }
    }
}
