package com.mymoney.app.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mymoney.app.R
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.app.ui.common.EntryRowItem
import com.mymoney.core.data.db.dao.EntryListRow
import com.mymoney.core.data.repository.EntryFilter
import com.mymoney.core.data.repository.EntryQueries
import com.mymoney.core.designsystem.component.EmptyState
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.SectionHeader
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.money.Digits
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** A non-transaction match; [kind] decides where tapping it leads. */
data class SearchHit(val kind: Kind, val id: String, val title: String, val subtitle: String? = null, val minor: Long? = null, val currency: String? = null) {
    enum class Kind { ACCOUNT, RULE, ASSET, GOAL, OBLIGATION }
}

data class SearchState(
    val query: String = "",
    val searching: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val entries: List<EntryListRow> = emptyList(),
)

/** Matching that ignores case, Arabic/Persian letter variants and digit scripts. */
internal fun searchKey(text: String): String = Digits.normalize(text).lowercase()
    .replace('ي', 'ی').replace('ك', 'ک').replace("‌", "").replace('ة', 'ه')

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(provider: FinanceSnapshotProvider, private val queries: EntryQueries) : ViewModel() {
    private val query = MutableStateFlow("")
    val queryText: StateFlow<String> = query.asStateFlow()

    val state: StateFlow<SearchState> = combine(query.debounce(250), provider.snapshot) { q, s -> q to s }
        .mapLatest { (q, s) ->
            val key = searchKey(q.trim())
            if (key.length < 2) return@mapLatest SearchState(q)
            fun m(vararg texts: String?) = texts.any { it != null && searchKey(it).contains(key) }
            val hits = buildList {
                s.accounts.filter { !it.account.type.isObligation && m(it.account.name, it.account.institution) }
                    .forEach { add(SearchHit(SearchHit.Kind.ACCOUNT, it.account.id, it.account.name, null, it.balanceMinor, it.account.currency)) }
                s.obligations.forEach { o ->
                    val acc = s.accountsById[o.accountId] ?: return@forEach
                    if (m(acc.account.name, o.counterparty, o.note)) add(SearchHit(SearchHit.Kind.OBLIGATION, o.accountId, acc.account.name, o.counterparty, kotlin.math.abs(acc.balanceMinor), acc.account.currency))
                }
                s.rules.filter { m(it.name, it.payee, it.note) }.forEach { add(SearchHit(SearchHit.Kind.RULE, it.id, it.name, null, it.amountMinor, it.currency)) }
                s.assets.filter { m(it.name, it.note) }.forEach { add(SearchHit(SearchHit.Kind.ASSET, it.id, it.name)) }
                s.goals.filter { m(it.name, it.note) }.forEach { add(SearchHit(SearchHit.Kind.GOAL, it.id, it.name, null, it.targetMinor, it.currency)) }
            }
            SearchState(q, false, hits, queries.list(EntryFilter(text = q.trim()), 50))
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())

    fun setQuery(q: String) {
        query.value = q
    }
}

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val q by viewModel.queryText.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val spacing = MyMoneyTheme.spacing
    Scaffold(
        topBar = {
            // statusBarsPadding: keep the field below the status bar and the camera cut-out (edge-to-edge window).
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().statusBarsPadding().displayCutoutPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                OutlinedTextField(
                    value = q,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (q.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.search_clear)) }
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        },
    ) { padding ->
        val hasQuery = searchKey(q.trim()).length >= 2
        if (hasQuery && s.query == q && s.hits.isEmpty() && s.entries.isEmpty()) {
            EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.search_none_title), stringResource(R.string.search_none_message), Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = spacing.screenPadding, vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!hasQuery) {
                item(key = "hint") { Text(stringResource(R.string.search_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(spacing.l)) }
            }
            if (s.hits.isNotEmpty()) {
                item(key = "h-hits") { SectionHeader(stringResource(R.string.search_section_items)) }
                items(s.hits, key = { "${it.kind}-${it.id}" }) { h -> HitRow(h) { onOpenHit(h) } }
            }
            if (s.entries.isNotEmpty()) {
                item(key = "h-entries") { SectionHeader(stringResource(R.string.search_section_entries)) }
                s.entries.groupBy { it.date }.forEach { (day, rows) ->
                    item(key = "d-$day") {
                        Text(
                            LocalFormats.current.dates.long(java.time.LocalDate.ofEpochDay(day)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.s, start = 4.dp),
                        )
                    }
                    items(rows, key = { "e-" + it.id }) { row -> EntryRowItem(row, onClick = { onOpenEntry(row.groupId) }) }
                }
            }
        }
    }
}

@Composable
private fun HitRow(h: SearchHit, onClick: () -> Unit) {
    val kind = stringResource(
        when (h.kind) {
            SearchHit.Kind.ACCOUNT -> R.string.search_kind_account
            SearchHit.Kind.RULE -> R.string.search_kind_rule
            SearchHit.Kind.ASSET -> R.string.search_kind_asset
            SearchHit.Kind.GOAL -> R.string.search_kind_goal
            SearchHit.Kind.OBLIGATION -> R.string.search_kind_obligation
        },
    )
    ListItem(
        headlineContent = { Text(h.title) },
        supportingContent = { Text(listOfNotNull(kind, h.subtitle).joinToString(" · ")) },
        trailingContent = if (h.minor != null && h.currency != null) ({ MoneyText(h.minor, h.currency) }) else null,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
