package com.mymoney.app.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mymoney.app.BuildConfig
import com.mymoney.app.R
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.theme.MyMoneyTheme

private data class Library(val name: String, val license: String)

/** Third-party code and assets shipped in the app (docs/README §licenses). */
private val libraries = listOf(
    Library("Vazirmatn font (Saber Rastikerdar)", "SIL Open Font License 1.1"),
    Library("jalaali-js calendar algorithm", "MIT"),
    Library("AndroidX, Jetpack Compose, Material 3, Room, WorkManager, DataStore, Biometric", "Apache License 2.0"),
    Library("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache License 2.0"),
    Library("Dagger / Hilt", "Apache License 2.0"),
    Library("Google Tink", "Apache License 2.0"),
    Library("SQLCipher for Android (Zetetic)", "BSD-style (Zetetic LLC)"),
    Library("Material icons", "Apache License 2.0"),
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val spacing = MyMoneyTheme.spacing
    var showOfl by rememberSaveable { mutableStateOf(false) }
    val ofl = remember(showOfl) {
        if (showOfl) runCatching { context.assets.open("licenses/Vazirmatn-OFL.txt").bufferedReader().use { it.readText() } }.getOrNull() else null
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.about_title), onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.screenPadding, spacing.s, spacing.screenPadding, 32.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
        ) {
            item(key = "app") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(96.dp))
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item(key = "privacy") {
                SectionCard(title = stringResource(R.string.about_privacy_title)) {
                    Text(stringResource(R.string.about_privacy), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item(key = "licenses-title") { Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.titleMedium) }
            items(libraries, key = { it.name }) { l ->
                SectionCard {
                    Text(l.name, style = MaterialTheme.typography.bodyMedium)
                    Text(l.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "ofl") {
                TextButton(onClick = { showOfl = !showOfl }) { Text(stringResource(R.string.about_show_ofl)) }
                ofl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
