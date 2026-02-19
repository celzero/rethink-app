package com.celzero.bravedns.iab

enum class ResultState(val message: String, val priority: InAppBillingHandler.Priority = InAppBillingHandler.Priority.LOW) {

    NONE("Not Started"),
    ACTIVITY_REFERENCE_NOT_FOUND("Activity reference is null", InAppBillingHandler.Priority.HIGH),

    // Connections
    CONNECTION_INVALID("Billing is not ready, seems to be disconnected", InAppBillingHandler.Priority.HIGH),
    CONNECTION_ESTABLISHING("Connecting to Google Play Console", InAppBillingHandler.Priority.LOW),
    CONNECTION_ESTABLISHING_IN_PROGRESS("An attempt to connect to Google Play Console is already in progress.", InAppBillingHandler.Priority.MEDIUM),
    CONNECTION_ALREADY_ESTABLISHED("Already connected to Google Play Console", InAppBillingHandler.Priority.LOW),
    CONNECTION_DISCONNECTED("Connection disconnected to Google Play Console", InAppBillingHandler.Priority.HIGH),
    CONNECTION_ESTABLISHED("Connection has been established to Google Play Console", InAppBillingHandler.Priority.LOW),
    CONNECTION_FAILED("Failed to connect Google Play Console", InAppBillingHandler.Priority.HIGH),

    // Purchases
    USER_QUERY_LIST_EMPTY("User query list is empty", InAppBillingHandler.Priority.HIGH),

    // Purchases
    CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING("InApp -> Fetching purchased products from google play console.", InAppBillingHandler.Priority.LOW),
    CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_FAILED("InApp -> Failed to fetch purchased products from google play console.", InAppBillingHandler.Priority.HIGH),
    CONSOLE_PURCHASE_PRODUCTS_INAPP_FETCHING_SUCCESS("InApp -> Successfully fetched purchased products from google play console.", InAppBillingHandler.Priority.LOW),

    CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING("SUB -> Fetching purchased products from google play console.", InAppBillingHandler.Priority.LOW),
    CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_FAILED("SUB ->Failed to fetch purchased products from google play console.", InAppBillingHandler.Priority.HIGH),
    CONSOLE_PURCHASE_PRODUCTS_SUB_FETCHING_SUCCESS("SUB -> Successfully fetched purchased products from google play console.", InAppBillingHandler.Priority.LOW),

    CONSOLE_PURCHASE_PRODUCTS_RESPONSE_PROCESSING("InApp, Subs -> Processing purchases and their product details", InAppBillingHandler.Priority.LOW),
    CONSOLE_PURCHASE_PRODUCTS_RESPONSE_COMPLETE("InApp, Subs -> Returning result with each purchase product's detail", InAppBillingHandler.Priority.LOW),
    CONSOLE_PURCHASE_PRODUCTS_CHECKED_FOR_ACKNOWLEDGEMENT("InApp, Subs -> Acknowledging purchases if not acknowledge yet", InAppBillingHandler.Priority.LOW),

    // Querying
    CONSOLE_QUERY_PRODUCTS_INAPP_FETCHING("InApp -> Querying product details from console", InAppBillingHandler.Priority.LOW),
    CONSOLE_QUERY_PRODUCTS_SUB_FETCHING("Subs -> Querying product details from console", InAppBillingHandler.Priority.LOW),
    CONSOLE_QUERY_PRODUCTS_COMPLETED("InApp, Subs -> Fetched product details from console", InAppBillingHandler.Priority.LOW),
    CONSOLE_QUERY_PRODUCTS_FAILED("Failed to fetch product details from console", InAppBillingHandler.Priority.HIGH),

    // Buying
    CONSOLE_BUY_PRODUCT_EMPTY_ID("InApp, Subs -> Product Id can't be empty", InAppBillingHandler.Priority.HIGH),
    CONSOLE_PRODUCTS_IN_APP_NOT_EXIST("InApp -> No product has been found", InAppBillingHandler.Priority.HIGH),
    CONSOLE_PRODUCTS_SUB_NOT_EXIST("SUB -> No product has been found", InAppBillingHandler.Priority.HIGH),

    // Update
    CONSOLE_PRODUCTS_OLD_SUB_NOT_FOUND("SUB -> Old product not being able to found", InAppBillingHandler.Priority.HIGH),

    // Billing Flows
    LAUNCHING_FLOW_INVOCATION_SUCCESSFULLY("Google Play Billing has been launched successfully", InAppBillingHandler.Priority.LOW),
    LAUNCHING_FLOW_INVOCATION_USER_CANCELLED("Cancelled by user", InAppBillingHandler.Priority.HIGH),
    LAUNCHING_FLOW_INVOCATION_EXCEPTION_FOUND("Exception Found, launching Google billing sheet", InAppBillingHandler.Priority.HIGH),
    PURCHASING_NO_PURCHASES_FOUND("No purchases found", InAppBillingHandler.Priority.HIGH),
    PURCHASING_ALREADY_OWNED("Already owned this product! No need to purchase", InAppBillingHandler.Priority.HIGH),
    PURCHASING_SUCCESSFULLY("Successfully Purchased", InAppBillingHandler.Priority.LOW),
    PURCHASING_FAILURE("Failed to make transaction", InAppBillingHandler.Priority.HIGH),

    PURCHASE_CONSUME("Successfully Consumed", InAppBillingHandler.Priority.LOW),
    PURCHASE_PENDING("Purchase is pending", InAppBillingHandler.Priority.MEDIUM),
    PURCHASE_ACK_PENDING("Purchase confirmation is pending", InAppBillingHandler.Priority.MEDIUM),
    PURCHASE_FAILURE("Failed to consume product", InAppBillingHandler.Priority.HIGH),
}
