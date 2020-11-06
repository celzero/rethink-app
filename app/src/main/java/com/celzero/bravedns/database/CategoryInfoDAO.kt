/*
Copyright 2020 RethinkDNS developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CategoryInfoDAO {

    @Update
    fun update(categoryInfo: CategoryInfo)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(categoryInfo: CategoryInfo)

    @Delete
    fun delete(categoryInfo: CategoryInfo)

    @Query("select * from CategoryInfo order by categoryName")
    fun getAppCategoryListLiveData(): LiveData<List<CategoryInfo>>

    @Transaction
    @Query("select * from CategoryInfo order by categoryName")
    fun getAppCategoryList(): List<CategoryInfo>

    @Query("update CategoryInfo set isInternetBlocked = :isInternetBlocked where categoryName = :categoryName")
    fun updateCategoryInternet(categoryName: String, isInternetBlocked: Boolean)

    @Query("delete from CategoryInfo")
    fun deleteAllCategory()

    @Query("update CategoryInfo set numOfAppsBlocked = numOfAppsBlocked+1 where categoryName =:categoryName")
    fun increaseNumberOfBlocked(categoryName: String)

    @Query("update CategoryInfo set numOfAppsBlocked = numOfAppsBlocked-1 where categoryName =:categoryName")
    fun decreaseNumberOfBlocked(categoryName: String)

    @Query("select * from CategoryInfo where categoryName = :categoryName")
    fun getCategoryDetail(categoryName: String) : CategoryInfo

    @Query("update CategoryInfo set numOfAppsBlocked = numberOFApps where categoryName =:categoryName")
    fun updateBlockedCount(categoryName: String)

    @Query("update CategoryInfo set numOfAppsBlocked = 0 where categoryName =:categoryName")
    fun updateBlockedCountToZero(categoryName: String)

    @Query("update CategoryInfo set numOfAppsBlocked = :count where categoryName =:categoryName")
    fun updateBlockedCount(categoryName: String, count :Int)

}