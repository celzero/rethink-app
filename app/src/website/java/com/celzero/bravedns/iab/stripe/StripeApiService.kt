package com.rethinkdns.retrixed.iab.stripe

import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

data class Price(
    val id: String,
    val unit_amount: Long,
    val currency: String,
    val product: String
)

data class PricesResponse(
    val data: List<Price>
)

interface StripeApiService {
    @GET("v1/prices")
    fun getPrices(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 10
    ): Call<PricesResponse>
}


data class PaymentIntentRequest(
    val amount: Long,
    val currency: String
)

data class PaymentIntentResponse(
    val id: String,
    val client_secret: String,
    val amount: Long,
    val currency: String
)

data class CustomerCreateParams(
    val email: String,
    val name: String,
    val description: String? = null,
    @SerializedName("address[city]") val city: String? = null,
    @SerializedName("address[country]") val country: String? = null
)


interface StripePaymentService {
    @POST("v1/payment_intents")
    fun createPaymentIntent(
        @Header("Authorization") authorization: String,
        @Body formBody: RequestBody
    ): Call<PaymentIntentResponse>
}

interface StripeCustomerService {
    @POST("v1/customers")
    fun createCustomer(
        @Header("Authorization") authorization: String,
        @Body params: CustomerCreateParams
    ): Call<RetrofitInstance.CustomerResponse>
}


