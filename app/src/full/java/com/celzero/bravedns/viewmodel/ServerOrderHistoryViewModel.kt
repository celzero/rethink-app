/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.viewmodel

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.iab.ServerOrderEntry
import com.celzero.bravedns.iab.ServerOrderHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServerOrderHistoryViewModel(
    private val repository: ServerOrderHistoryRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ServerOrderVM"
    }

    sealed class UiState {
        object Loading : UiState()
        data class Success(val orders: List<ServerOrderEntry>) : UiState()
        data class Empty(val isNoCredentials: Boolean) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun reload() {
        _uiState.value = UiState.Loading
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.fetchOrders()
            _uiState.value = when (result) {
                is ServerOrderHistoryRepository.Result.Success -> {
                    if (result.orders.isEmpty()) {
                        Logger.i(LOG_TAG_UI, "$TAG load: success but no orders")
                        UiState.Empty(isNoCredentials = false)
                    } else {
                        Logger.i(LOG_TAG_UI, "$TAG load: ${result.orders.size} orders")
                        UiState.Success(result.orders)
                    }
                }
                is ServerOrderHistoryRepository.Result.NoCredentials -> {
                    Logger.w(LOG_TAG_UI, "$TAG load: no credentials")
                    UiState.Empty(isNoCredentials = true)
                }
                is ServerOrderHistoryRepository.Result.Error -> {
                    Logger.e(LOG_TAG_UI, "$TAG load: error: ${result.message}")
                    UiState.Error(result.message)
                }
            }
        }
    }
}

