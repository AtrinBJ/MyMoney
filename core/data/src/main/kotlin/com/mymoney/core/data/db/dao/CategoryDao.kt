package com.mymoney.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mymoney.core.data.db.entity.CategoryEntity
import com.mymoney.core.data.db.entity.TagEntity
import com.mymoney.core.domain.model.CategoryType
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query(
        """SELECT * FROM category
           WHERE (:type IS NULL OR type = :type) AND (:includeArchived OR isArchived = 0)
           ORDER BY type, sortOrder, createdAt""",
    )
    fun observe(type: CategoryType?, includeArchived: Boolean): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY type, sortOrder, createdAt")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun get(id: String): CategoryEntity?

    @Query("SELECT * FROM category WHERE systemKey = :key")
    suspend fun getBySystemKey(key: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM category WHERE parentId = :id")
    suspend fun childCount(id: String): Int

    @Query(
        """SELECT (SELECT COUNT(*) FROM entry WHERE categoryId = :id)
                + (SELECT COUNT(*) FROM entry_split WHERE categoryId = :id)""",
    )
    suspend fun usageCount(id: String): Int

    @Query("UPDATE entry SET categoryId = :to WHERE categoryId = :from")
    suspend fun reassignEntries(from: String, to: String)

    @Query("UPDATE entry_split SET categoryId = :to WHERE categoryId = :from")
    suspend fun reassignSplits(from: String, to: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM category WHERE type = :type")
    suspend fun nextSortOrder(type: CategoryType): Int
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tag ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE name = :name")
    suspend fun findByName(name: String): TagEntity?

    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun get(id: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tag: TagEntity)

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun delete(id: String)
}
