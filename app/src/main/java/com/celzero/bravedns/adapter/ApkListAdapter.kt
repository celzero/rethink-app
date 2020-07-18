package com.celzero.bravedns.adapter


import android.content.Context
import android.content.pm.PackageInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Filter
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.adapter.Apk
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.BottomSheetFragment
import com.celzero.bravedns.util.ApkUtilities
import java.util.*
import kotlin.collections.ArrayList


class ApkListAdapter(var apkList: ArrayList<Apk>, private val context: Context) : RecyclerView.Adapter<ApkListAdapter.ApkListViewHolder>() {


    var apkListFiltered : ArrayList<Apk> = ArrayList()

    init {

        apkListFiltered.addAll(apkList)
    }

    fun updateApkList(){
        notifyDataSetChanged()
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ApkListViewHolder {
        return ApkListViewHolder(
            LayoutInflater.from(context).inflate(R.layout.layout_apk_item, parent, false),
            context,
            apkList
        )
    }

    override fun onBindViewHolder(holder: ApkListViewHolder, position: Int) {
        //print("onBindViewHolder: "+apkList[position].appInfo)
        holder.mIconImageView.setImageDrawable(context.packageManager.getApplicationIcon(apkList[position].packageName))
        holder.mLabelTextView.text =apkList[position].appName
        //holder.mLabelTextView.text =
          //  context.packageManager.getApplicationLabel(apkList.get(position).appInfo).toString()
        //holder.mPackageTextView.text = "Tap for more Info"
        /*if(position < 3 ) {
            holder.mIconIndicator.setBackgroundResource(R.color.colorRed_900)
            val anim: Animation = AlphaAnimation(0.0f, 1.0f)
            anim.setDuration(1000) //You can manage the blinking time with this parameter

            anim.setStartOffset(20)
            anim.setRepeatMode(Animation.REVERSE)
            anim.setRepeatCount(Animation.INFINITE)
            holder.mIconIndicator.startAnimation(anim)
        }*/

    }

    override fun getItemCount(): Int {
        return apkList.size
    }

    // Filter Class
    fun filter(c: String) {

        var charText = c
        println("apkList  : "+ apkList.size)
        println("1 apkList Filtered : "+ apkListFiltered.size)
        charText = charText.toLowerCase(Locale.getDefault())
        apkList.clear()
        if (charText.length == 0) {
            println("apkList Filtered : "+ apkListFiltered.size)
            apkList.addAll(apkListFiltered)
        } else {
            for (wp in apkListFiltered) {
                if (wp.appName.toLowerCase(Locale.getDefault()).contains(charText)) {
                    apkList.add(wp)
                }
            }
        }
        notifyDataSetChanged()
    }


    inner class ApkListViewHolder(view: View, context: Context, apkList: ArrayList<Apk>) :
        RecyclerView.ViewHolder(view) {

        val mIconImageView: ImageView = view.findViewById(R.id.apk_icon_iv)
        val mLabelTextView: TextView = view.findViewById(R.id.apk_label_tv)
        val mPackageTextView: TextView = view.findViewById(R.id.apk_package_tv)
        //val mIconIndicator : TextView = view.findViewById(R.id.status_indicator)

        init {
            //var permissionList : String = ""
            view.setOnClickListener{
                val permissionDetails  = ApkUtilities.getPermissionDetails(
                    context,
                    apkList[adapterPosition].packageName
                )
                println("One")

                var pos = 0
                if(permissionDetails.requestedPermissionsFlags!=null)
                permissionDetails.requestedPermissionsFlags.forEach {

                    if((it and PackageInfo.REQUESTED_PERMISSION_GRANTED) == PackageInfo.REQUESTED_PERMISSION_GRANTED){
                        println("Granted: "+permissionDetails.requestedPermissions[pos])
                    }
                    pos++
                }

                val bottomSheetFragment = BottomSheetFragment(context,apkList.get(adapterPosition))
                val frag = context as FragmentActivity
                bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)

                /*val viewDialog = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_permission_manager, null)
                val dialog = BottomSheetDialog(context)
                dialog.setContentView(viewDialog)*/

                /*viewDialog.findViewById<TextView>(R.id.textView).setOnClickListener{
                    print(apkList.get(adapterPosition).packageName)
                    dialog.dismiss()
                }

                viewDialog.findViewById<TextView>(R.id.textView2).setOnClickListener{
                    print(apkList.get(adapterPosition).packageName)
                    dialog.dismiss()
                }

                viewDialog.findViewById<TextView>(R.id.textView3).setOnClickListener{
                    print(apkList.get(adapterPosition).packageName)
                    dialog.dismiss()
                }*/

            }


        }
    }




}