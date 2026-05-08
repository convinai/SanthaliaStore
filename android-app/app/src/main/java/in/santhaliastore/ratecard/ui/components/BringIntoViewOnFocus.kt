package `in`.santhaliastore.ratecard.ui.components

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scrolls the focused field into view above the IME (soft keyboard).
 *
 * Why: `Modifier.imePadding()` shrinks the scroll viewport but does
 * not auto-scroll the focused field. On a tap, the IME animates in
 * over ~250 ms; if the field happens to live in the bottom half of
 * the form the user has to scroll up by hand to see what they're
 * typing. The 300 ms delay rides past the IME animation so the
 * `bringIntoView` call lands after the viewport has actually shrunk.
 */
fun Modifier.bringIntoViewOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this
        .bringIntoViewRequester(requester)
        .onFocusEvent { focusState ->
            if (focusState.isFocused) {
                scope.launch {
                    delay(300)
                    requester.bringIntoView()
                }
            }
        }
}
