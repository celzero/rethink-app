/*
 * Copyright 2021 RethinkDNS and its authors
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
 */
package com.celzero.bravedns.util

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class CustomLinearLayoutManager(context: Context) : LinearLayoutManager(context) {

    override fun isSmoothScrollbarEnabled(): Boolean {
        return true
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView?,
        state: RecyclerView.State?,
        position: Int
    ) {
        super.smoothScrollToPosition(recyclerView, state, position)
    }

    // fixme: Revisit this file for fast scroll drawable changes
    /* override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?,
                                    state: RecyclerView.State?): Int {
        return super.scrollVerticallyBy(dy * 2, recycler, state)
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int {
         return super.computeVerticalScrollRange(state) / 2
     }

     override fun computeVerticalScrollRange(state: RecyclerView.State): Int {
         //return 15000 //+ state.remainingScrollVertical
         return super.computeVerticalScrollOffset(state) / 2
     }*/
}
