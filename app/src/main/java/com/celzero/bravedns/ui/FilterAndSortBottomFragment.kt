package com.celzero.bravedns.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.celzero.bravedns.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FilterAndSortBottomFragment : BottomSheetDialogFragment(){


    private lateinit var fragmentView: View

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        fragmentView = inflater.inflate(R.layout.fragment_filter_and_sort, container, false)
        //initView(fragmentView)
        return fragmentView
    }

    /*override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //initView(view)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState)
    }*/

}