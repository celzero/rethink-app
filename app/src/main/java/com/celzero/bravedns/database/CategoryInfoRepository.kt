package com.celzero.bravedns.database

import android.util.Log
import androidx.lifecycle.LiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class CategoryInfoRepository  (private val categoryInfoDAO: CategoryInfoDAO){

    fun updateAsync(categoryInfo: CategoryInfo){
        Log.d("BraveDNS","updateAsync: ${categoryInfo.categoryName}, ${categoryInfo.isInternetBlocked} ")
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