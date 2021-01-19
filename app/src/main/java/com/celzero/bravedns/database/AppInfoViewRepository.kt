package com.celzero.bravedns.database

class AppInfoViewRepository(private val appInfoViewDAO: AppInfoViewDAO) {

    fun getAllAppDetails(): List<AppInfoView> {
        return appInfoViewDAO.getAllAppDetails()
    }
}