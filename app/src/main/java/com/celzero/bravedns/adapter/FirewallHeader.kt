package com.celzero.bravedns.adapter

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.core.content.ContextCompat.getColor
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.PersistentState.Companion.isCategoryBlocked
import com.celzero.bravedns.ui.FirewallActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.withContext


class FirewallHeader(var categoryName : String, var isInternet : Boolean): AbstractItem<FirewallHeader.ViewHolder>() {

    val debug = true

    companion object{
        lateinit var context : Context
        fun setContextVal(context: Context){
            this.context = context
        }
    }

    inner class ViewHolder(itemView: View): FastAdapter.ViewHolder<FirewallHeader>(itemView) {
        private val categoryNameTV : TextView = itemView.findViewById(R.id.textView_category_name)
        private val appCountTV : TextView = itemView.findViewById(R.id.textView_app_count)
        private val internetChk : AppCompatToggleButton = itemView.findViewById((R.id.checkbox))
        private val imageHolderLL : LinearLayout = itemView.findViewById(R.id.imageLayout)
        private val imageHolder1 : AppCompatImageView = itemView.findViewById(R.id.imageLayout_1)
        private val imageHolder2 : AppCompatImageView = itemView.findViewById(R.id.imageLayout_2)
        private val imageHolder3 : AppCompatImageView = itemView.findViewById(R.id.imageLayout_3)
        private val imageHolder4 : AppCompatImageView = itemView.findViewById(R.id.imageLayout_4)


        @InternalCoroutinesApi
        override fun bindView(item: FirewallHeader, payloads: MutableList<Any>) {
            categoryNameTV.text = item.categoryName
            //internetChk.isChecked = !FirewallManager.isCategoryInternetAllowed(item.categoryName)//item.isInternet
            internetChk.isChecked = isCategoryBlocked(item.categoryName, context)

            if(this.layoutPosition%2 == 0){
                val layoutLL : LinearLayout = itemView.findViewById(type)
                layoutLL.setBackgroundColor(getColor(context ,R.color.colorPrimaryDark))
            }

            var packageList = ArrayList<String>()
            var packageName = ArrayList<String>()
            HomeScreenActivity.GlobalVariable.appList.forEach{
                if(it.value.appCategory == item.categoryName){
                    packageList.add(it.value.packageInfo)
                    packageName.add(it.value.appName)
                }
            }

            appCountTV.setText(packageList.size.toString() + " apps")

            if(packageList.size!=0) {
                if (packageList.size >= 4) {
                    imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(packageList[0]))
                    imageHolder2.setImageDrawable(context.packageManager.getApplicationIcon(packageList[1]))
                    imageHolder3.setImageDrawable(context.packageManager.getApplicationIcon(packageList[2]))
                    imageHolder4.setImageDrawable(context.packageManager.getApplicationIcon(packageList[3]))
                } else if (packageList.size == 3) {
                    imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(packageList[0]))
                    imageHolder2.setImageDrawable(context.packageManager.getApplicationIcon(packageList[1]))
                    imageHolder3.setImageDrawable(context.packageManager.getApplicationIcon(packageList[2]))
                    imageHolder4.visibility = View.GONE
                } else if (packageList.size == 2) {
                    imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(packageList[0]))
                    imageHolder2.setImageDrawable(context.packageManager.getApplicationIcon(packageList[1]))
                    imageHolder3.visibility = View.GONE
                    imageHolder4.visibility = View.GONE
                } else {
                    imageHolder1.setImageDrawable(context.packageManager.getApplicationIcon(packageList[0]))
                    imageHolder2.visibility = View.GONE
                    imageHolder3.visibility = View.GONE
                    imageHolder4.visibility = View.GONE
                }
            }else{
                imageHolder1.visibility = View.GONE
                imageHolder2.visibility = View.GONE
                imageHolder3.visibility = View.GONE
                imageHolder4.visibility = View.GONE
            }


            categoryNameTV.setOnClickListener{
                showDialog(packageName, item.categoryName)
            }

            imageHolderLL.setOnClickListener{
                showDialog(packageName, item.categoryName)
            }
            //if(debug) Log.d("BraveDNS", "FirewallManager.isCategoryInternetAllowed() :  "+item.categoryName+ FirewallManager.isCategoryInternetAllowed(item.categoryName))
            //FirewallManager.printAllAppStatus()
            internetChk.setOnCheckedChangeListener(null)
            internetChk.setOnCheckedChangeListener { compoundButton: CompoundButton, b: Boolean ->
                internetChk.isEnabled = false
                internetChk.isClickable  = false
                FirewallManager.updateCategoryAppsInternetPermission(item.categoryName, !b,context)
                internetChk.isEnabled = true
                internetChk.isClickable  = true
                //firewallActivity.showProgress(false)
                //FirewallManager.printAllAppStatus()
            }

        }

        override fun unbindView(item: FirewallHeader) {
        }

    }

    fun showDialog(packageList : ArrayList<String>, categoryName: String){
        val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)
        builderSingle.setIcon(R.drawable.ic_launcher)
        builderSingle.setTitle("App List for "+categoryName)

        val arrayAdapter = ArrayAdapter<String>(
            context,
            android.R.layout.simple_list_item_activated_1
        )
        arrayAdapter.addAll(packageList)

        builderSingle.setAdapter(
            arrayAdapter,
            DialogInterface.OnClickListener { dialog, which ->
                val strName = arrayAdapter.getItem(which)
                val builderInner: AlertDialog.Builder = AlertDialog.Builder(context)
                builderInner.setMessage(strName)
                builderInner.setTitle("App List")
               /* builderInner.setPositiveButton(
                    "Ok",
                    DialogInterface.OnClickListener { dialog, which -> dialog.dismiss() })*/
                //builderInner.show()
            })
        builderSingle.show()
    }


    override val layoutRes: Int
        get() = R.layout.recycleview_firewall_header


    override val type: Int
        get() = R.id.firewall_header_ll

    override fun getViewHolder(view: View) : FirewallHeader.ViewHolder {
        return ViewHolder(view)
    }

}
