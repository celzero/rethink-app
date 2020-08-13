package com.celzero.bravedns.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider


class FirewallViewModel(application: Application) : AndroidViewModel(application) {

    //private val latency = MutableLiveData<Any>()
    val appsBlockedCount  = MutableLiveData<String>()
   /* val categoriesBlockedCount = MutableLiveData<Any>()
    val universalBlockedCount = MutableLiveData<Any>()*/

    /*fun setLatency(value : Long){
        latency.value = value
    }*/

    fun setAppsBlockedCount(value : String){
        //Log.d("BraveDNS","Value inside setAppsBlockedCount :"+value.toString())
        appsBlockedCount.value = value
    }

    fun getAppsBlockedCount(): String{
        appsBlockedCount.value = appsBlockedCount.value + "S".toString()
        return appsBlockedCount.toString()
    }

   /* fun setCategoriesBlockedCount(value : Int){
        categoriesBlockedCount.value = value
    }

    fun setUniversalBlockedCount(value : Int){
        universalBlockedCount.value = value
    }*/

}

/*
class FirewallViewModelFactory (val arg : Application) : ViewModelProvider.Factory{
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return modelClass.getConstructor(Application::class.java).newInstance(arg)
    }

}*/
