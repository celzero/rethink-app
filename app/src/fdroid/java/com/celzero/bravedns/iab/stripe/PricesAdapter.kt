package com.celzero.bravedns.iab.stripe
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
/** Presentation-only list. The F-Droid placeholder has no live purchase callsite. */
class PricesAdapter {
    private var prices by mutableStateOf<List<Price>>(emptyList())
    fun submitList(newPrices: List<Price>) { prices = newPrices }
    @Composable fun Content(modifier: Modifier = Modifier) {
        LazyColumn(modifier) {
            items(prices) { price -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Product: ${price.product}")
                Text("Price: ${price.unit_amount / 100.0} ${price.currency.uppercase()}")
            } }
        }
    }
}
