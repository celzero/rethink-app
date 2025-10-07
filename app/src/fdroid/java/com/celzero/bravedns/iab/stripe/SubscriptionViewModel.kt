package com.rethinkdns.retrixed.iab.stripe

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SubscriptionViewModel : ViewModel() {

    private val _pricesLiveData = MutableLiveData<List<Price>>()
    val pricesLiveData: LiveData<List<Price>> get() = _pricesLiveData

    private val productKey = ""

    //private val stripeApi = RetrofitInstance.api
    private val publishableKey = ""
    private val secretKey = ""

    fun fetchPrices() {
        /*stripeApi.getPrices(authorization = secretKey).enqueue(object : Callback<PricesResponse> {
            override fun onResponse(call: Call<PricesResponse>, response: Response<PricesResponse>) {
                if (response.isSuccessful) {
                    val prices = response.body()?.data ?: emptyList()
                    _pricesLiveData.value = prices.filter { it.product == productKey }
                } else {
                    Log.e("StripeAPI", "Error: ${response.errorBody()?.string()}")
                }
            }

            override fun onFailure(call: Call<PricesResponse>, t: Throwable) {
                Log.e("StripeAPI", "Failure: ${t.message}")
            }
        })*/
    }
}
