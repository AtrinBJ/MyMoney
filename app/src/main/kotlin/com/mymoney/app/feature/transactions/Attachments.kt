package com.mymoney.app.feature.transactions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.R
import com.mymoney.app.feature.data.allowExternalScreen
import com.mymoney.app.navigation.EntryDetailRoute
import com.mymoney.app.ui.common.ConfirmDialog
import com.mymoney.core.data.attachments.AttachmentStore
import com.mymoney.core.data.db.entity.AttachmentEntity
import com.mymoney.core.designsystem.component.InfoBanner
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.domain.money.Digits
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receipts attached to an operation (docs/SECURITY.md §Attachments). Files are stored encrypted; to show one,
 * a decrypted copy is written to cache/shared and handed to a viewer app through FileProvider. Those copies
 * are removed at the next app start.
 */
@HiltViewModel
class AttachmentsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val store: AttachmentStore,
) : ViewModel() {
    private val groupId = savedState.toRoute<EntryDetailRoute>().groupId
    val attachments: StateFlow<List<AttachmentEntity>> = store.observe(groupId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    fun add(uri: Uri, deleteAfter: File? = null) {
        viewModelScope.launch {
            _error.value = withContext(Dispatchers.IO) {
                try {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "photo.jpg"
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { store.add(groupId, name, mime, it) }
                    null
                } catch (_: AttachmentStore.TooLargeException) {
                    R.string.att_err_large
                } catch (_: Exception) {
                    R.string.att_err_read
                } finally {
                    deleteAfter?.delete()
                }
            }
        }
    }

    /** A FileProvider uri for a fresh camera capture file. */
    fun newCaptureUri(): Pair<Uri, File> {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file) to file
    }

    fun view(a: AttachmentEntity, onReady: (Uri, String) -> Unit) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared/${UUID.randomUUID()}").apply { mkdirs() }
                val safeName = a.displayName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").ifBlank { "file" }
                val out = File(dir, safeName)
                store.open(a).use { input -> out.outputStream().use { input.copyTo(it) } }
                FileProvider.getUriForFile(context, "${context.packageName}.files", out)
            }
            onReady(uri, a.mimeType)
        }
    }

    fun delete(a: AttachmentEntity) {
        viewModelScope.launch { store.delete(a) }
    }

    fun clearError() { _error.value = null }

    companion object {
        /** Decrypted viewing copies and camera captures are temporary; called at app start. */
        fun clearTemporaryFiles(context: Context) {
            File(context.cacheDir, "shared").deleteRecursively()
            File(context.cacheDir, "camera").deleteRecursively()
        }
    }
}

@Composable
fun AttachmentsSection(viewModel: AttachmentsViewModel = hiltViewModel()) {
    val items by viewModel.attachments.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val f = LocalFormats.current
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCapture by remember { mutableStateOf<Pair<Uri, File>?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) viewModel.add(uri) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.add(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val capture = pendingCapture
        if (ok && capture != null) viewModel.add(capture.first, deleteAfter = capture.second) else capture?.second?.delete()
        pendingCapture = null
    }
    val noViewer = stringResource(R.string.att_no_viewer)

    SectionCard(title = stringResource(R.string.att_title)) {
        error?.let { InfoBanner(stringResource(it), actionLabel = stringResource(R.string.action_ok), onAction = viewModel::clearError) }
        if (items.isEmpty()) Text(stringResource(R.string.att_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    viewModel.view(a) { uri, mime ->
                        context.allowExternalScreen()
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: ActivityNotFoundException) {
                            android.widget.Toast.makeText(context, noViewer, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (a.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(a.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Digits.localize(Formatter.formatShortFileSize(context, a.sizeBytes), f.settings.digits), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { confirm = a.id }) { Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.allowExternalScreen()
                photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Outlined.Image, stringResource(R.string.att_add_photo)) }
            OutlinedButton(onClick = {
                context.allowExternalScreen()
                val capture = viewModel.newCaptureUri()
                pendingCapture = capture
                camera.launch(capture.first)
            }) { Icon(Icons.Outlined.CameraAlt, stringResource(R.string.att_take_photo)) }
            OutlinedButton(onClick = {
                context.allowExternalScreen()
                document.launch(arrayOf("application/pdf", "image/*"))
            }) { Icon(Icons.Outlined.AttachFile, stringResource(R.string.att_add_file)) }
        }
        Text(stringResource(R.string.att_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    confirm?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.action_delete),
            message = stringResource(R.string.att_delete_confirm),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { items.firstOrNull { it.id == id }?.let(viewModel::delete); confirm = null },
            onDismiss = { confirm = null },
        )
    }
}
