package com.mymoney.app.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.mymoney.core.data.db.dao.EntryListRow
import com.mymoney.core.data.repository.EntryFilter
import com.mymoney.core.data.repository.EntryQueries
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** List item: either a day header or an entry row. */
sealed interface TxListItem {
    data class Header(val date: LocalDate) : TxListItem
    data class Row(val row: EntryListRow) : TxListItem
}

sealed interface TxEvent {
    data class Deleted(val groupId: String) : TxEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val queries: EntryQueries,
    private val ledger: LedgerRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    tags: TagRepository,
) : ViewModel() {

    private val _filter = MutableStateFlow(EntryFilter())
    val filter: StateFlow<EntryFilter> = _filter.asStateFlow()

    val accounts: StateFlow<List<Account>> = accounts.observeAccounts(includeArchived = true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories: StateFlow<List<Category>> = categories.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags: StateFlow<List<Tag>> = tags.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Paging stream; text search is debounced so typing does not re-query on every key. */
    val items: Flow<PagingData<TxListItem>> = _filter
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { queries.paged(it) }
        .map { paging ->
            paging.map { TxListItem.Row(it) as TxListItem }
                .insertSeparators { before, after ->
                    val b = (before as? TxListItem.Row)?.row?.date
                    val a = (after as? TxListItem.Row)?.row?.date ?: return@insertSeparators null
                    if (b != a) TxListItem.Header(LocalDate.ofEpochDay(a)) else null
                }
        }
        .cachedIn(viewModelScope)

    private val _events = MutableSharedFlow<TxEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun setFilter(filter: EntryFilter) = _filter.update { filter.copy(text = it.text) }
    fun setText(text: String) = _filter.update { it.copy(text = text) }
    fun clearFilters() = _filter.update { EntryFilter(accountId = null, text = it.text) }
    fun setAccount(id: String?) = _filter.update { it.copy(accountId = id) }

    fun delete(groupId: String) {
        viewModelScope.launch {
            ledger.softDeleteGroup(groupId)
            _events.emit(TxEvent.Deleted(groupId))
        }
    }

    fun undoDelete(groupId: String) {
        viewModelScope.launch { ledger.restoreGroup(groupId) }
    }
}
