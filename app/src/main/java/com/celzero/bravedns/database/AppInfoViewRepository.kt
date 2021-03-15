package com.celzero.bravedns.database

import androidx.lifecycle.LiveData

class AppInfoViewRepository(private val appInfoViewDAO: AppInfoViewDAO) {

    fun getAllAppDetails(): List<AppInfoView> {
        return appInfoViewDAO.getAllAppDetails()
    }

    fun getAllAppDetailsForLiveData(): LiveData<List<AppInfoView>> {
        return appInfoViewDAO.getAllAppDetailsForLiveData()
    }

    fun getWhitelistCountLiveData(): LiveData<Int> {
        return appInfoViewDAO.getWhitelistCountLiveData()
    }

    fun getExcludedAppListCountLiveData(): LiveData<Int> {
        return appInfoViewDAO.getExcludedAppListCountLiveData()
    }


}