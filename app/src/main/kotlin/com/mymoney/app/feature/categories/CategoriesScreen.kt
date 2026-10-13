package com.mymoney.app.feature.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.app.ui.common.categoryLabel
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.core.designsystem.component.ColorKeys
import com.mymoney.core.designsystem.component.IconBadge
import com.mymoney.core.designsystem.component.IconKeys
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val repo: CategoryRepository) : ViewModel() {
    val categories: StateFlow<List<Category>> = repo.observe(includeArchived = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _error = MutableStateFlow<DomainError?>(null)
    val error: StateFlow<DomainError?> = _error.asStateFlow()

    fun save(category: Category, onDone: () -> Unit) {
        viewModelScope.launch {
            when (val r = repo.save(category)) {
                is Outcome.Success -> { _error.value = null; onDone() }
                is Outcome.Failure -> _error.value = r.error
            }
        }
    }

    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch { (repo.setArchived(id, archived) as? Outcome.Failure)?.let { _error.value = it.error } }
    }

    fun delete(id: String, reassignTo: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            when (val r = repo.delete(id, reassignTo)) {
                is Outcome.Success -> { _error.value = null; onDone() }
                is Outcome.Failure -> _error.value = r.error
            }
        }
    }

    suspend fun usage(id: String): Int = repo.usageCount(id)
    fun clearError() { _error.value = null }
}

@Composable
fun CategoriesScreen(onBack: () -> Unit, viewModel: CategoriesViewModel = hiltViewModel()) {
    val all by viewModel.categories.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Category?>(null) }
    val type = if (tab == 0) CategoryType.EXPENSE else CategoryType.INCOME
    val list = all.filter { it.type == type }
    val parents = list.filter { it.parentId == null }
    val children = list.filter { it.parentId != null }.groupBy { it.parentId }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.cat_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Category("", type) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.cat_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.kind_expense)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.kind_income)) })
            }
            LazyColumn(Modifier.fillMaxSize()) {
                parents.forEach { p ->
                    item(key = p.id) { CategoryItem(p, indent = false) { editing = p } }
                    children[p.id].orEmpty().forEach { c -> item(key = c.id) { CategoryItem(c, indent = true) { editing = c } } }
                }
            }
        }
    }
    editing?.let { cat ->
        CategoryEditorDialog(
            category = cat,
            parents = parents.filter { it.id != cat.id && !it.isArchived },
            all = list,
            viewModel = viewModel,
            error = error,
            onDismiss = { editing = null; viewModel.clearError() },
        )
    }
}

@Composable
private fun CategoryItem(c: Category, indent: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).padding(start = if (indent) 32.dp else 0.dp),
        leadingContent = { IconBadge(IconKeys.icon(c.iconKey), ColorKeys.color(c.colorKey)) },
        headlineContent = { Text(categoryLabel(c)) },
        supportingContent = if (c.isArchived) ({ Text(stringResource(R.string.cat_archived)) }) else null,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(
    category: Category,
    parents: List<Category>,
    all: List<Category>,
    viewModel: CategoriesViewModel,
    error: DomainError?,
    onDismiss: () -> Unit,
) {
    val isNew = category.id.isBlank()
    var name by rememberSaveable { mutableStateOf(category.name.orEmpty()) }
    var icon by rememberSaveable { mutableStateOf(category.iconKey ?: "category") }
    var color by rememberSaveable { mutableStateOf(category.colorKey ?: "c0") }
    var parentId by rememberSaveable { mutableStateOf(category.parentId) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var reassignTo by rememberSaveable { mutableStateOf<String?>(null) }
    val placeholder = if (category.systemKey != null) categoryLabel(category.systemKey, null) else ""
    val spacing = MyMoneyTheme.spacing

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.cat_add else R.string.action_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.s)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.cat_name)) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (category.isProtected) Text(stringResource(R.string.cat_protected), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.cat_parent), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    FilterChip(parentId == null, { parentId = null }, { Text(stringResource(R.string.cat_none)) })
                    parents.forEach { p -> FilterChip(parentId == p.id, { parentId = p.id }, { Text(categoryLabel(p)) }) }
                }
                Text(stringResource(R.string.cat_icon), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    IconKeys.selectable.forEach { k ->
                        IconBadge(IconKeys.icon(k), if (k == icon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Modifier.clickable { icon = k }, size = 36.dp)
                    }
                }
                Text(stringResource(R.string.cat_color), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    ColorKeys.all.forEach { k ->
                        IconBadge(IconKeys.icon(icon), ColorKeys.color(k), Modifier.clickable { color = k }, size = if (k == color) 40.dp else 32.dp)
                    }
                }
                if (confirmDelete) {
                    Text(stringResource(R.string.cat_delete_reassign), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        all.filter { it.id != category.id && !it.isArchived }.forEach { c ->
                            FilterChip(reassignTo == c.id, { reassignTo = c.id }, { Text(categoryLabel(c)) })
                        }
                    }
                }
                error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (confirmDelete) {
                    viewModel.delete(category.id, reassignTo, onDismiss)
                } else {
                    viewModel.save(
                        category.copy(
                            name = name.trim().ifEmpty { null },
                            iconKey = icon,
                            colorKey = color,
                            parentId = parentId,
                        ),
                        onDismiss,
                    )
                }
            }) { Text(stringResource(if (confirmDelete) R.string.action_delete else R.string.action_save)) }
        },
        dismissButton = {
            if (!isNew && !category.isProtected) {
                Column {
                    TextButton(onClick = { viewModel.setArchived(category.id, !category.isArchived); onDismiss() }) {
                        Text(stringResource(if (category.isArchived) R.string.acc_unarchive else R.string.acc_archive))
                    }
                    if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
