/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.fields

import android.text.InputType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** abc_search_view's textAutoComplete|textNoSuggestions are not representable by
 * KeyboardOptions.autoCorrectEnabled alone. Preserve those native search hints without replacing
 * Compose's connection, owning an input session, observing text, or creating a platform editor.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun originalSearchInputRequest(request: PlatformTextInputMethodRequest): PlatformTextInputMethodRequest =
    PlatformTextInputMethodRequest { attributes ->
        val connection = request.createInputConnection(attributes)
        if (attributes.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            val variation = attributes.inputType and InputType.TYPE_MASK_VARIATION
            if (variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
                attributes.inputType = (attributes.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv()) or
                    InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        connection
    }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OriginalSearchInputMethod(style: OriginalFieldStyle, options: KeyboardOptions,
    content: @Composable () -> Unit) {
    // Honor explicit callers that deliberately request autocorrection. Existing source search
    // instances leave it unspecified; non-search and password/number editors are untouched.
    if (!style.search || options.autoCorrectEnabled == true) {
        content()
        return
    }
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            // Forward the same connection through the normal cancellable Compose session.
            nextHandler.startInputMethod(originalSearchInputRequest(request))
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}
