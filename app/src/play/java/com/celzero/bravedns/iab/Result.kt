package com.celzero.bravedns.iab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Result {
    private var RESULT_STATE = ResultState.NONE

    private var _resultState = MutableStateFlow(ResultState.NONE)
    private val resultState: StateFlow<ResultState> get() = _resultState

    init {
        _resultState.value = ResultState.NONE
    }

    fun setResultState(resultState: ResultState) {
        Logger.d(Logger.LOG_IAB, "setResultState: $resultState")
        RESULT_STATE = resultState
        _resultState.value = RESULT_STATE
    }

    fun getResultState(): ResultState {
        return RESULT_STATE
    }

    fun getResultStateFlow(): StateFlow<ResultState> {
        return resultState
    }

    override fun toString(): String {
        return when (RESULT_STATE) {
            ResultState.NONE -> ResultState.NONE.message

            ResultState.CONNECTION_INVALID -> ResultState.CONNECTION_INVALID.message
            ResultState.CONNECTION_ESTABLISHING -> ResultState.CONNECTION_ESTABLISHING.message
            ResultState.CONNECTION_ESTABLISHING_IN_PROGRESS -> ResultState.CONNECTION_ESTABLISHING_IN_PROGRESS.message
            ResultState.CONNECTION_ALREADY_ESTABLISHED -> ResultState.CONNECTION_ALREADY_ESTABLISHED.message
            ResultState.CONNECTION_DISCONNECTED -> ResultState.CONNECTION_DISCONNECTED.message
            ResultState.CONNECTION_ESTABLISHED -> ResultState.CONNECTION_ESTABLISHED.message
            ResultState.CONNECTION_FAILED -> ResultState.CONNECTION_FAILED.message
            ResultState.ACTIVITY_REFERENCE_NOT_FOUND -> ResultState.ACTIVITY_REFERENCE_NOT_FOUND.message

            ResultState.USER_QUERY_LIST_EMPTY -> ResultState.USER_QUERY_LIST_EMPTY.message

            ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING -> ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING.message
            ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_FAILED -> ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_FAILED.message
            ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_SUCCESS -> ResultState.CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_SUCCESS.message

            ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING -> ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING.message
            ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_FAILED -> ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_FAILED.message
            ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_SUCCESS -> ResultState.CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_SUCCESS.message

            ResultState.CONSOLE_PURCHASE_PRODUCTS_RESPONSE_PROCESSING -> ResultState.CONSOLE_PURCHASE_PRODUCTS_RESPONSE_PROCESSING.message
            ResultState.CONSOLE_PURCHASE_PRODUCTS_RESPONSE_COMPLETE -> ResultState.CONSOLE_PURCHASE_PRODUCTS_RESPONSE_COMPLETE.message
            ResultState.CONSOLE_PURCHASE_PRODUCTS_CHECKED_FOR_ACKNOWLEDGEMENT -> ResultState.CONSOLE_PURCHASE_PRODUCTS_CHECKED_FOR_ACKNOWLEDGEMENT.message

            ResultState.CONSOLE_QUERY_PRODUCTS_INAPP_FETCHING -> ResultState.CONSOLE_QUERY_PRODUCTS_INAPP_FETCHING.message
            ResultState.CONSOLE_QUERY_PRODUCTS_SUB_FETCHING -> ResultState.CONSOLE_QUERY_PRODUCTS_SUB_FETCHING.message
            ResultState.CONSOLE_QUERY_PRODUCTS_COMPLETED -> ResultState.CONSOLE_QUERY_PRODUCTS_COMPLETED.message
            ResultState.CONSOLE_QUERY_PRODUCTS_FAILED -> ResultState.CONSOLE_QUERY_PRODUCTS_FAILED.message

            ResultState.CONSOLE_BUY_PRODUCT_EMPTY_ID -> ResultState.CONSOLE_BUY_PRODUCT_EMPTY_ID.message
            ResultState.CONSOLE_PRODUCTS_IN_APP_NOT_EXIST -> ResultState.CONSOLE_PRODUCTS_IN_APP_NOT_EXIST.message
            ResultState.CONSOLE_PRODUCTS_SUB_NOT_EXIST -> ResultState.CONSOLE_PRODUCTS_SUB_NOT_EXIST.message

            ResultState.CONSOLE_PRODUCTS_OLD_SUB_NOT_FOUND -> ResultState.CONSOLE_PRODUCTS_OLD_SUB_NOT_FOUND.message

            ResultState.LAUNCHING_FLOW_INVOCATION_SUCCESSFULLY -> ResultState.LAUNCHING_FLOW_INVOCATION_SUCCESSFULLY.message
            ResultState.LAUNCHING_FLOW_INVOCATION_USER_CANCELLED -> ResultState.LAUNCHING_FLOW_INVOCATION_USER_CANCELLED.message
            ResultState.LAUNCHING_FLOW_INVOCATION_EXCEPTION_FOUND -> ResultState.LAUNCHING_FLOW_INVOCATION_EXCEPTION_FOUND.message

            ResultState.PURCHASING_SUCCESSFULLY -> ResultState.PURCHASING_SUCCESSFULLY.message
            ResultState.PURCHASING_ALREADY_OWNED -> ResultState.PURCHASING_ALREADY_OWNED.message
            ResultState.PURCHASING_NO_PURCHASES_FOUND -> ResultState.PURCHASING_NO_PURCHASES_FOUND.message
            ResultState.PURCHASING_FAILURE -> ResultState.PURCHASING_FAILURE.message

            ResultState.PURCHASE_CONSUME -> ResultState.PURCHASE_CONSUME.message
            ResultState.PURCHASE_PENDING -> ResultState.PURCHASE_PENDING.message
            ResultState.PURCHASE_ACK_PENDING -> ResultState.PURCHASE_ACK_PENDING.message
            ResultState.PURCHASE_FAILURE -> ResultState.PURCHASE_FAILURE.message
        }
    }
}
