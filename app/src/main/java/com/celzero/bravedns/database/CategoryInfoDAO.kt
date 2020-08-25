package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CategoryInfoDAO {

    @Update
    fun update(categoryInfo: CategoryInfo)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(categoryInfo: CategoryInfo)

    @Delete
    fun delete(categoryInfo: CategoryInfo)

    @Query("select * from CategoryInfo order by categoryName")
    fun getAppCategoryListLiveData(): LiveData<List<CategoryInfo>>

    @Query("select * from CategoryInfo order by categoryName")
    fun getAppCategoryList(): List<CategoryInfo>

    @Query("update CategoryInfo set isInternetBlocked = :isInternetBlocked where categoryName = :categoryName")
    fun updateCategoryInternet(categoryName: String, isInternetBlocked: Boolean)
}