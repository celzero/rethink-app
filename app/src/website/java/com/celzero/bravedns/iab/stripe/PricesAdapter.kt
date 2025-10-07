package com.rethinkdns.retrixed.iab.stripe

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PricesAdapter : RecyclerView.Adapter<PricesAdapter.PriceViewHolder>() {

    private var prices: List<Price> = emptyList()

    fun submitList(newPrices: List<Price>) {
        prices = newPrices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PriceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return PriceViewHolder(view)
    }

    override fun onBindViewHolder(holder: PriceViewHolder, position: Int) {
        val price = prices[position]
        holder.bind(price)
    }

    override fun getItemCount(): Int = prices.size

    class PriceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(android.R.id.text1)
        private val details: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(price: Price) {
            Logger.i("StripeApi","Price: $price, title: ${price.id}, details: ${price.unit_amount}, ${price.currency}, ${price.product}")
            title.text = "Product: ${price.product}"
            details.text = "Price: ${price.unit_amount / 100.0} ${price.currency.uppercase()}"
        }
    }
}
