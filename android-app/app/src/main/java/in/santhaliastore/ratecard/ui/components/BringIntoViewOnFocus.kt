package `in`.santhaliastore.ratecard.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

/**
 * Scrolls the focused field into view above the IME (soft keyboard).
 *
 * The previous implementation relied on a fixed 300 ms delay, which
 * was unreliable on slower devices and on screens where the IME
 * animation runs longer. This version reads the live IME bottom inset
 * — `WindowInsets.ime.getBottom(density)` updates frame by frame as
 * the keyboard slides in — and re-runs `bringIntoView` whenever the
 * inset changes. The result: the field stays visible above the
 * keyboard regardless of animation speed, and we self-correct if
 * the keyboard resizes mid-typing (e.g. autofill suggestions appear).
 */
fun Modifier.bringIntoViewOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isFocused, imeBottom) {
        if (isFocused) {
            // Tiny delay so the parent's `imePadding()` has settled the
            // scroll viewport before we measure where the field landed.
            // 50 ms rides past one frame on 60 Hz; cheap and reliable.
            delay(50)
            requester.bringIntoView()
        }
    }

    this
        .bringIntoViewRequester(requester)
        .onFocusEvent { focusState ->
            isFocused = focusState.isFocused
        }
}
