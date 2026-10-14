package com.mymoney.app.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.R
import com.mymoney.app.navigation.PinSetupRoute
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.core.data.security.PinStore
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.security.PinPolicy
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What may unlock the app besides its own PIN: any fingerprint or face the phone offers to apps (class 2 included,
 * so Samsung face unlock works where exposed), and on Android 11+ the phone's own screen lock. The app lock is a UI
 * gate; the data key is not tied to biometrics (docs/SECURITY.md §App lock), so the weaker class is acceptable.
 */
internal fun unlockAuthenticators(): Int =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK

fun biometricAvailable(context: android.content.Context): Boolean =
    BiometricManager.from(context).canAuthenticate(unlockAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

// ------------------------------------------------------------------------------------------------
// Lock screen
// ------------------------------------------------------------------------------------------------

data class LockState(
    val entered: Int = 0,
    val checking: Boolean = false,
    val wrong: Int? = null,
    val lockedUntil: Long = 0,
    val biometric: Boolean = false,
)

@HiltViewModel
class LockViewModel @Inject constructor(
    private val pins: PinStore,
    private val lock: AppLockManager,
    private val clock: TimeProvider,
    settings: SettingsRepository,
) : ViewModel() {
    private val digits = StringBuilder()
    private val _state = MutableStateFlow(LockState())
    val state: StateFlow<LockState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val until = withContext(Dispatchers.Default) { pins.lockedUntil() }
            _state.update { it.copy(lockedUntil = until, biometric = settings.settings.first().biometricEnabled) }
        }
    }

    fun now() = clock.nowMillis()

    fun digit(d: Char) {
        if (_state.value.checking || digits.length >= PinPolicy.MAX_LENGTH) return
        digits.append(d)
        _state.update { it.copy(entered = digits.length, wrong = null) }
    }

    fun backspace() {
        if (digits.isNotEmpty()) digits.setLength(digits.length - 1)
        _state.update { it.copy(entered = digits.length) }
    }

    fun submit() {
        if (digits.length < PinPolicy.MIN_LENGTH || _state.value.checking) return
        val pin = digits.toString()
        digits.setLength(0)
        _state.update { it.copy(checking = true, entered = 0) }
        viewModelScope.launch {
            // PBKDF2 takes a moment; never on the main thread.
            val r = withContext(Dispatchers.Default) { pins.verify(pin) }
            when (r) {
                PinStore.Result.Ok -> lock.unlock()
                is PinStore.Result.Wrong -> _state.update { it.copy(checking = false, wrong = r.attemptsBeforeDelay) }
                is PinStore.Result.LockedOut -> _state.update { it.copy(checking = false, lockedUntil = r.untilMillis, wrong = 0) }
            }
        }
    }

    fun biometricSucceeded() = lock.unlock()
}

@Composable
fun LockScreen(viewModel: LockViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = stringResource(R.string.lock_biometric_title)
    val usePin = stringResource(R.string.lock_use_pin)
    val subtitle = stringResource(R.string.lock_biometric_subtitle)
    val showBiometric = s.biometric && remember { biometricAvailable(context) }
    val prompt: () -> Unit = {
        (context as? FragmentActivity)?.let { activity ->
            BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = viewModel.biometricSucceeded()
                },
            ).authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(unlockAuthenticators())
                    // With the phone's screen lock allowed the system shows its own fallback; otherwise "Use PIN".
                    .apply { if (unlockAuthenticators() and DEVICE_CREDENTIAL == 0) setNegativeButtonText(usePin) }
                    .build(),
            )
        }
    }
    LaunchedEffect(showBiometric) { if (showBiometric) prompt() }

    var now by remember { mutableLongStateOf(viewModel.now()) }
    LaunchedEffect(s.lockedUntil) {
        while (s.lockedUntil > now) {
            delay(1_000)
            now = viewModel.now()
        }
    }
    val waiting = s.lockedUntil > now
    val f = LocalFormats.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            PinDots(s.entered)
            Text(
                when {
                    waiting -> stringResource(R.string.lock_wait, Digits.localize(((s.lockedUntil - now + 999) / 1000).toString(), f.settings.digits))
                    s.wrong != null -> if (s.wrong!! > 0) {
                        stringResource(R.string.lock_wrong_left, Digits.localize(s.wrong.toString(), f.settings.digits))
                    } else {
                        stringResource(R.string.lock_wrong)
                    }
                    else -> ""
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.height(48.dp).padding(top = 8.dp),
            )
            PinPad(
                enabled = !waiting && !s.checking,
                onDigit = viewModel::digit,
                onBackspace = viewModel::backspace,
                onSubmit = viewModel::submit,
                canSubmit = s.entered >= PinPolicy.MIN_LENGTH,
            )
            if (showBiometric) {
                TextButton(onClick = prompt, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                    Text("  " + stringResource(R.string.lock_use_biometric))
                }
            }
        }
    }
}

@Composable
fun PinDots(count: Int) {
    val description = stringResource(R.string.lock_digits_entered, count)
    Row(
        Modifier.semantics { contentDescription = description }.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(maxOf(count, PinPolicy.MIN_LENGTH)) { i ->
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (i < count) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** Numeric keypad. Keys are always laid out left-to-right like a phone dial, also in Persian. */
@Composable
fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean) {
    val digitsStyle = LocalFormats.current.settings.digits
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { d -> Key(Digits.localize(d.toString(), digitsStyle), enabled, onClick = { onDigit(d) }) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Key(null, enabled, stringResource(R.string.lock_delete), onBackspace) { Icon(Icons.AutoMirrored.Outlined.Backspace, null) }
                Key(Digits.localize("0", digitsStyle), enabled, onClick = { onDigit('0') })
                Key(null, enabled && canSubmit, stringResource(R.string.lock_confirm), onSubmit) { Icon(Icons.Filled.Check, null) }
            }
        }
    }
}

@Composable
private fun Key(label: String?, enabled: Boolean, description: String? = null, onClick: () -> Unit, icon: (@Composable () -> Unit)? = null) {
    Box(
        Modifier.size(72.dp).clip(CircleShape)
            .background(if (label != null) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.headlineSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        } else {
            icon?.invoke()
        }
    }
}

// ------------------------------------------------------------------------------------------------
// PIN setup: set, change, or turn off
// ------------------------------------------------------------------------------------------------

enum class PinSetupMode { SET, CHANGE, DISABLE }

enum class PinStep { CURRENT, NEW, CONFIRM, OFFER_BIOMETRIC, DONE }

data class PinSetupState(
    val step: PinStep,
    val entered: Int = 0,
    val error: Int? = null,
    val checking: Boolean = false,
)

@HiltViewModel
class PinSetupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val pins: PinStore,
    private val settings: SettingsRepository,
    private val lock: AppLockManager,
) : ViewModel() {
    val mode: PinSetupMode = PinSetupMode.valueOf(savedState.toRoute<PinSetupRoute>().mode)

    /** Set by the screen: whether the phone has a fingerprint, face or screen lock to offer after a new PIN. */
    var canOfferBiometric: Boolean = false
    private val digits = StringBuilder()
    private var newPin: String? = null
    private val _state = MutableStateFlow(PinSetupState(if (mode == PinSetupMode.SET) PinStep.NEW else PinStep.CURRENT))
    val state: StateFlow<PinSetupState> = _state.asStateFlow()

    fun digit(d: Char) {
        if (digits.length >= PinPolicy.MAX_LENGTH) return
        digits.append(d)
        _state.update { it.copy(entered = digits.length, error = null) }
    }

    fun backspace() {
        if (digits.isNotEmpty()) digits.setLength(digits.length - 1)
        _state.update { it.copy(entered = digits.length) }
    }

    fun submit() {
        val pin = digits.toString()
        digits.setLength(0)
        _state.update { it.copy(entered = 0) }
        when (_state.value.step) {
            PinStep.CURRENT -> viewModelScope.launch {
                _state.update { it.copy(checking = true) }
                when (val r = withContext(Dispatchers.Default) { pins.verify(pin) }) {
                    PinStore.Result.Ok -> if (mode == PinSetupMode.DISABLE) {
                        withContext(Dispatchers.Default) { pins.clear() }
                        settings.update { it.copy(lockEnabled = false, biometricEnabled = false) }
                        _state.update { it.copy(step = PinStep.DONE, checking = false) }
                    } else {
                        _state.update { it.copy(step = PinStep.NEW, checking = false) }
                    }
                    is PinStore.Result.Wrong -> _state.update { it.copy(error = R.string.lock_wrong, checking = false) }
                    is PinStore.Result.LockedOut -> _state.update { it.copy(error = R.string.pin_locked_out, checking = false) }
                }
            }
            PinStep.NEW -> {
                val problem = PinPolicy.validateNew(pin)
                if (problem != null) {
                    _state.update {
                        it.copy(
                            error = when (problem) {
                                PinPolicy.Problem.LENGTH, PinPolicy.Problem.NOT_DIGITS -> R.string.pin_err_length
                                PinPolicy.Problem.TOO_SIMPLE -> R.string.pin_err_simple
                            },
                        )
                    }
                } else {
                    newPin = pin
                    _state.update { it.copy(step = PinStep.CONFIRM) }
                }
            }
            PinStep.CONFIRM -> if (pin != newPin) {
                newPin = null
                _state.update { it.copy(step = PinStep.NEW, error = R.string.pin_err_mismatch) }
            } else {
                viewModelScope.launch {
                    _state.update { it.copy(checking = true) }
                    withContext(Dispatchers.Default) { pins.setPin(pin) }
                    settings.update { it.copy(lockEnabled = true) }
                    lock.unlock()
                    val alreadyOn = settings.settings.first().biometricEnabled
                    _state.update { it.copy(step = if (mode == PinSetupMode.SET && canOfferBiometric && !alreadyOn) PinStep.OFFER_BIOMETRIC else PinStep.DONE, checking = false) }
                }
            }
            PinStep.OFFER_BIOMETRIC, PinStep.DONE -> Unit
        }
    }

    fun answerBiometricOffer(accept: Boolean) {
        viewModelScope.launch {
            if (accept) settings.update { it.copy(biometricEnabled = true) }
            _state.update { it.copy(step = PinStep.DONE) }
        }
    }
}

@Composable
fun PinSetupScreen(onDone: () -> Unit, viewModel: PinSetupViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.canOfferBiometric = biometricAvailable(context) }
    LaunchedEffect(s.step) { if (s.step == PinStep.DONE) onDone() }
    val title = stringResource(
        when (viewModel.mode) {
            PinSetupMode.SET -> R.string.pin_set_title
            PinSetupMode.CHANGE -> R.string.pin_change_title
            PinSetupMode.DISABLE -> R.string.pin_disable_title
        },
    )
    Scaffold(topBar = { BackTopBar(title, onDone) }) { padding ->
        if (s.step == PinStep.OFFER_BIOMETRIC) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.pin_offer_biometric_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.pin_offer_biometric_text), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
                androidx.compose.material3.Button(onClick = { viewModel.answerBiometricOffer(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pin_offer_biometric_yes))
                }
                TextButton(onClick = { viewModel.answerBiometricOffer(false) }) { Text(stringResource(R.string.pin_offer_biometric_no)) }
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(
                    when (s.step) {
                        PinStep.CURRENT -> R.string.pin_enter_current
                        PinStep.NEW -> R.string.pin_enter_new
                        PinStep.CONFIRM -> R.string.pin_enter_again
                        PinStep.OFFER_BIOMETRIC, PinStep.DONE -> R.string.pin_done
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            PinDots(s.entered)
            Text(
                s.error?.let { stringResource(it) } ?: if (s.step == PinStep.NEW) stringResource(R.string.pin_hint) else "",
                color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.height(56.dp).padding(top = 8.dp),
            )
            PinPad(!s.checking, viewModel::digit, viewModel::backspace, viewModel::submit, s.entered >= PinPolicy.MIN_LENGTH)
        }
    }
}
