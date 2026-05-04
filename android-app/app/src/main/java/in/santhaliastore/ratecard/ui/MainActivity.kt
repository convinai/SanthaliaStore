package `in`.santhaliastore.ratecard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.santhaliastore.ratecard.RateCardApp
import `in`.santhaliastore.ratecard.ui.nav.AppNavigation
import `in`.santhaliastore.ratecard.ui.screens.lock.LockScreen
import `in`.santhaliastore.ratecard.ui.theme.SanthaliaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single-activity host. Sets up the Compose tree:
 *   - SanthaliaTheme wraps everything
 *   - LockScreen overlay if PIN lock is enabled and not yet unlocked
 *     this session (or after a 5-minute background)
 *   - AppNavigation otherwise
 */
class MainActivity : ComponentActivity() {

    /**
     * Marks the moment we last saw the activity in the foreground.
     * If more than 5 minutes pass while we're in the background we
     * re-lock the app on next resume.
     */
    private var leftForegroundAtMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SanthaliaTheme {
                AppRoot()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        leftForegroundAtMillis = System.currentTimeMillis()
    }

    @Composable
    private fun AppRoot() {
        val app = applicationContext as RateCardApp
        val settings = app.container.settingsRepo
        val pinEnabled by settings.pinEnabled.collectAsStateWithLifecycle(initialValue = false)

        // Track unlock state across recompositions but reset on a fresh
        // process; "unlocked once" survives configuration changes.
        var unlocked by rememberSaveableUnlock()
        val scope = rememberCoroutineScope()

        // On every appearance, decide whether to require a PIN. If we
        // were in the background for >5 min, re-lock.
        LaunchedEffect(Unit) {
            val pinFlag = settings.pinEnabled.first()
            if (pinFlag) {
                val now = System.currentTimeMillis()
                val gap = if (leftForegroundAtMillis == 0L) Long.MAX_VALUE
                else now - leftForegroundAtMillis
                if (gap > 5 * 60 * 1000L) {
                    unlocked = false
                }
            }
            // Stamp foreground timestamp regardless — used by lock VM to
            // decide whether re-prompts are needed in future sessions.
            scope.launch { settings.stampForegroundNow() }
        }

        if (pinEnabled && !unlocked) {
            LockScreen(onUnlocked = { unlocked = true })
        } else {
            AppNavigation()
        }
    }
}

/**
 * Minimal saveable wrapper so we don't import the full saveable package
 * into MainActivity.
 */
@Composable
private fun rememberSaveableUnlock(): androidx.compose.runtime.MutableState<Boolean> =
    androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(false)
    }
