package com.mymoney.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.repository.TagRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class CurrencyRepositoryImpl @Inject constructor(private val db: MyMoneyDatabase) : CurrencyRepository {
    private val dao get() = db.currencyDao()

    override fun observeAll(): Flow<List<Currency>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun getAll(): List<Currency> = dao.getAll().map { it.toDomain() }
    override suspend fun get(code: String): Currency? = dao.get(code)?.toDomain()

    override suspend fun addCustom(currency: Currency): Outcome<Unit> {
        if (dao.get(currency.code) != null) return Outcome.Failure(DomainError.InUse("currency code"))
        dao.insert(currency.copy(isCustom = true).toEntity())
        return Outcome.Success(Unit)
    }

    override suspend fun setEnabled(code: String, enabled: Boolean) {
        dao.get(code)?.let { dao.update(it.copy(isEnabled = enabled)) }
    }

    override suspend fun isInUse(code: String): Boolean = dao.usageCount(code) > 0
}

@Singleton
class ExchangeRateRepositoryImpl @Inject constructor(private val db: MyMoneyDatabase) : ExchangeRateRepository {
    private val dao get() = db.rateDao()

    override fun observeAll(): Flow<List<ExchangeRate>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun getAll(): List<ExchangeRate> = dao.getAll().map { it.toDomain() }

    override suspend fun save(rate: ExchangeRate): Outcome<Unit> {
        if (rate.base == rate.quote) return Outcome.Failure(DomainError.InvalidRate("same currency"))
        if (rate.rate.signum() <= 0) return Outcome.Failure(DomainError.InvalidRate("must be positive"))
        if (rate.rate.precision() - rate.rate.scale() > 13) return Outcome.Failure(DomainError.InvalidRate("too large"))
        dao.upsert(rate.toEntity())
        return Outcome.Success(Unit)
    }

    override suspend fun delete(id: String) = dao.delete(id)
}

@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val db: MyMoneyDatabase,
    private val changeLog: ChangeLogger,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : CategoryRepository {
    private val dao get() = db.categoryDao()

    override fun observe(type: CategoryType?, includeArchived: Boolean): Flow<List<Category>> =
        dao.observe(type, includeArchived).map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<Category> = dao.getAll().map { it.toDomain() }
    override suspend fun get(id: String): Category? = dao.get(id)?.toDomain()
    override suspend fun getBySystemKey(key: String): Category? = dao.getBySystemKey(key)?.toDomain()

    override suspend fun save(category: Category): Outcome<Unit> = db.withTransaction {
        val name = category.name?.trim()
        if (category.systemKey == null && name.isNullOrEmpty()) return@withTransaction Outcome.Failure(DomainError.InvalidName("name"))
        if (name != null && name.length > 60) return@withTransaction Outcome.Failure(DomainError.InvalidName("name"))
        category.parentId?.let { parentId ->
            val parent = dao.get(parentId) ?: return@withTransaction Outcome.Failure(DomainError.NotFound("category", parentId))
            if (parent.parentId != null) return@withTransaction Outcome.Failure(DomainError.RuleViolation("DM-3.3", "only one sub-level"))
            if (parent.type != category.type) return@withTransaction Outcome.Failure(DomainError.RuleViolation("DM-3.3", "parent type mismatch"))
            if (parentId == category.id) return@withTransaction Outcome.Failure(DomainError.RuleViolation("DM-3.3", "self parent"))
        }
        val now = clock.nowMillis()
        val existing = dao.get(category.id)
        if (existing == null) {
            val id = category.id.ifBlank { ids.newId() }
            dao.insert(
                category.copy(id = id, name = name, sortOrder = dao.nextSortOrder(category.type), createdAt = now, updatedAt = now).toEntity(),
            )
            changeLog.log(ChangeLogger.CATEGORY, id, "CREATE")
        } else {
            if (existing.type != category.type && dao.usageCount(category.id) > 0) {
                return@withTransaction Outcome.Failure(DomainError.InUse("category type"))
            }
            if (category.parentId != null && dao.childCount(category.id) > 0) {
                return@withTransaction Outcome.Failure(DomainError.RuleViolation("DM-3.3", "a parent cannot become a child"))
            }
            dao.update(category.copy(name = name, systemKey = existing.systemKey, createdAt = existing.createdAt, updatedAt = now).toEntity())
            changeLog.log(ChangeLogger.CATEGORY, category.id, "UPDATE")
        }
        Outcome.Success(Unit)
    }

    override suspend fun setArchived(id: String, archived: Boolean): Outcome<Unit> {
        val c = dao.get(id) ?: return Outcome.Failure(DomainError.NotFound("category", id))
        if (c.toDomain().isProtected && archived) return Outcome.Failure(DomainError.InUse("system category"))
        dao.update(c.copy(isArchived = archived, updatedAt = clock.nowMillis()))
        return Outcome.Success(Unit)
    }

    override suspend fun delete(id: String, reassignTo: String?): Outcome<Unit> = db.withTransaction {
        val c = dao.get(id) ?: return@withTransaction Outcome.Failure(DomainError.NotFound("category", id))
        if (c.toDomain().isProtected) return@withTransaction Outcome.Failure(DomainError.InUse("system category"))
        if (dao.childCount(id) > 0) return@withTransaction Outcome.Failure(DomainError.InUse("category with subcategories"))
        if (dao.usageCount(id) > 0) {
            val target = reassignTo?.let { dao.get(it) } ?: return@withTransaction Outcome.Failure(DomainError.InUse("category"))
            if (target.type != c.type || target.id == id) {
                return@withTransaction Outcome.Failure(DomainError.RuleViolation("DM-3.3", "reassign to a category of the same type"))
            }
            dao.reassignEntries(id, target.id)
            dao.reassignSplits(id, target.id)
        }
        dao.delete(id)
        changeLog.log(ChangeLogger.CATEGORY, id, "DELETE", reason = reassignTo?.let { "reassigned to $it" })
        Outcome.Success(Unit)
    }

    override suspend fun usageCount(id: String): Int = dao.usageCount(id)
}

@Singleton
class TagRepositoryImpl @Inject constructor(
    private val db: MyMoneyDatabase,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : TagRepository {
    private val dao get() = db.tagDao()

    override fun observeAll(): Flow<List<Tag>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun save(tag: Tag): Outcome<Unit> {
        val name = tag.name.trim()
        if (name.isEmpty() || name.length > 30) return Outcome.Failure(DomainError.InvalidName("tag"))
        val clash = dao.findByName(name)
        if (clash != null && clash.id != tag.id) return Outcome.Failure(DomainError.InUse("tag name"))
        return try {
            val existing = tag.id.takeIf { it.isNotBlank() }?.let { dao.get(it) }
            if (existing == null) {
                dao.insert(tag.copy(id = tag.id.ifBlank { ids.newId() }, name = name, createdAt = clock.nowMillis()).toEntity())
            } else {
                dao.update(existing.copy(name = name, colorKey = tag.colorKey))
            }
            Outcome.Success(Unit)
        } catch (_: SQLiteConstraintException) {
            Outcome.Failure(DomainError.InUse("tag name"))
        }
    }

    override suspend fun delete(id: String) = dao.delete(id)
}
