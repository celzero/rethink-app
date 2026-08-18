/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *//*

   package com.celzero.bravedns.adapter

   import android.content.Context
   import android.view.LayoutInflater
   import android.view.View
   import android.view.ViewGroup
   import androidx.recyclerview.widget.RecyclerView
   import com.celzero.bravedns.database.AlertRegistry
   import com.celzero.bravedns.databinding.ListItemAlertRegistryBinding
   import com.celzero.bravedns.service.AlertCategory
   import com.google.android.material.dialog.MaterialAlertDialogBuilder

   class AlertAdapter(
       private val context: Context,
       private val alertRegistries: Array<AlertRegistry?>
   ) : RecyclerView.Adapter<AlertAdapter.AlertRegistryViewHolder>() {

       override fun onBindViewHolder(holder: AlertAdapter.AlertRegistryViewHolder, position: Int) {
           val alerts: AlertRegistry = alertRegistries[position] ?: return
           holder.update(alerts)
       }

       override fun onCreateViewHolder(
           parent: ViewGroup,
           viewType: Int
       ): AlertAdapter.AlertRegistryViewHolder {
           val itemBinding =
               ListItemAlertRegistryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
           return AlertRegistryViewHolder(itemBinding)
       }

       override fun getItemCount(): Int {
           return alertRegistries.size
       }

       inner class AlertRegistryViewHolder(private val b: ListItemAlertRegistryBinding) :
           RecyclerView.ViewHolder(b.root) {
           fun update(alert: AlertRegistry) {
               // do not show the alert if the message is empty
               if (alert.alertMessage.isEmpty()) {
                   b.root.visibility = View.GONE
                   return
               }
               b.title.text = alert.alertTitle
               val message = ""
               */
/*when (AlertCategory.valueOf(alert.alertCategory)) {
    AlertCategory.DNS ->
        "List of domains blocked in past one hour. Click to <<see more>>..."
    AlertCategory.FIREWALL ->
        "List of IP addresses blocked in past one hour. Click to <<see more>>..."
    AlertCategory.APP ->
        "List of apps blocked in past one hour. Click to <<see more>>..."
    else -> "Unknown category"
}*//*

       b.description.text = message
       b.descriptionMore.text = alert.alertMessage
       b.priority.text = alert.alertSeverity.lowercase().replaceFirstChar(Char::uppercase)
       setupClickListeners(alert)
   }

   fun setupClickListeners(alert: AlertRegistry) {
       b.description.setOnClickListener {
           if (b.descriptionMore.visibility == View.VISIBLE)
               b.descriptionMore.visibility = View.GONE
           else if (b.descriptionMore.visibility == View.GONE)
               b.descriptionMore.visibility = View.VISIBLE
       }

       b.descriptionMore.setOnClickListener {
           if (b.descriptionMore.visibility == View.VISIBLE)
               b.descriptionMore.visibility = View.GONE
           else if (b.descriptionMore.visibility == View.GONE)
               b.descriptionMore.visibility = View.VISIBLE
       }

       b.action.setOnClickListener {
           val category = AlertCategory.valueOf(alert.alertCategory)
           showActionDialog(category)
       }
   }

   private fun showActionDialog(category: AlertCategory) {
       // show dialog with actions to be taken on the alert
       val message = ""
       */
/*when (category) {
    AlertCategory.DNS ->
        "Some actions to be taken on the alert \n\n 1. Allow the connection \n\n 2. Block the connection \n\n 3. Allow all connections from this domain \n\n 4. Block all connections from this domain"
    AlertCategory.FIREWALL ->
        "Some actions to be taken on the alert \n\n 1. Allow the connection \n\n 2. Block the connection \n\n 3. Allow this connections for all app \n\n 4. Block this connections for all app"
    AlertCategory.APP ->
        "Some actions to be taken on the alert \n\n 1. Allow the connection \n\n 2. Block the connection \n\n 3. Allow all connections from this app \n\n 4. Block all connections from this app"
    else -> "Unknown category"
}*//*

               MaterialAlertDialogBuilder(context)
                   .setTitle("Actions")
                   .setMessage(message)
                   .setPositiveButton("Okay") { dialog, _ ->
                       // allow the connection
                       dialog.dismiss()
                   }
                   .create()
                   .show()
           }
       }
   }
   */
