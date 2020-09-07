package com.celzero.bravedns.database

import androidx.lifecycle.LiveData

class CategoryInfoRepository  (private val categoryInfoDAO: CategoryInfoDAO){

    fun updateAsync(categoryInfo: CategoryInfo){
        categoryInfoDAO.update(categoryInfo)
    }

    fun deleteAsync(categoryInfo : CategoryInfo){
        categoryInfoDAO.delete(categoryInfo)
    }

    fun insertAsync(categoryInfo : CategoryInfo){
        categoryInfoDAO.insert(categoryInfo)
    }

    fun getAppCategoryForLiveData(): LiveData<List<CategoryInfo>>{
        return categoryInfoDAO.getAppCategoryListLiveData()
    }

    fun getAppCategoryList(): List<CategoryInfo>{
        return categoryInfoDAO.getAppCategoryList()
    }

    fun updateCategoryInternet(categoryName: String, isInternetBlocked : Boolean){
        categoryInfoDAO.updateCategoryInternet(categoryName, isInternetBlocked)
    }


}