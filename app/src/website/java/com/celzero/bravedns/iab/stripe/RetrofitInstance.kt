package com.rethinkdns.retrixed.iab.stripe

import com.rethinkdns.retrixed.customdownloader.RetrofitManager
import com.rethinkdns.retrixed.customdownloader.RetrofitManager.Companion.okHttpClient
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitInstance {
    private const val BASE_URL = "https://api.stripe.com/"

    private val client = OkHttpClient.Builder().build()

    val api: StripeApiService by lazy {
        getStripeBaseBuilder()
    }

    private fun getStripeBaseBuilder(): StripeApiService {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient(false))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StripeApiService::class.java)
    }

    val paymentApi: StripePaymentService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StripePaymentService::class.java)
    }

    data class CustomerResponse(
        val id: String,
        val email: String,
        val name: String,
        val description: String?,
        val created: Long,
        val address: Address?
    )

    data class Address(
        val city: String?,
        val country: String?
    )

    val retrofit = Retrofit.Builder()
        .baseUrl("https://api.stripe.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val stripeCustomerService = retrofit.create(StripeCustomerService::class.java)

}
