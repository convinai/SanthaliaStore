package `in`.santhaliastore.ratecard.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

/**
 * Tiny preferences-backed settings store.
 *
 * Holds:
 *   - The Apps Script web app URL the phone syncs against.
 *   - Sync metadata (last successful sync timestamp, last error).
 *   - PIN lock toggle and the SHA-256 hash of the user's PIN.
 *   - "Last foreground" timestamp so the lock screen knows when to
 *     re-prompt (we re-lock after 5 minutes in background).
 *
 * Key names are versioned via the `pref_v1_` prefix so a future
 * schema change can ship a one-shot migration.
 */
class SettingsRepository(private val context: Context) {

    private val ds get() = context.dataStore

    /* --------------------------- public flows --------------------------- */

    val sheetUrl: Flow<String> = ds.data.map { it[KEY_SHEET_URL].orEmpty() }

    val lastSyncedAt: Flow<Long> = ds.data.map { it[KEY_LAST_SYNCED_AT] ?: 0L }

    val lastSyncError: Flow<String?> = ds.data.map { it[KEY_LAST_SYNC_ERROR] }

    val pinEnabled: Flow<Boolean> = ds.data.map { it[KEY_PIN_ENABLED] ?: false }

    val pinHash: Flow<String?> = ds.data.map { it[KEY_PIN_HASH] }

    val lastForegroundAt: Flow<Long> = ds.data.map { it[KEY_LAST_FG_AT] ?: 0L }

    /**
     * Opaque incremental-pull cursor handed to us by the server on the
     * last successful `pullChanges` call. Empty string means "I have
     * nothing yet — send everything", which is exactly what we want
     * on a fresh install or after the user points the phone at a new
     * sheet (we reset it inside [setSheetUrl]).
     */
    val pullCursor: Flow<String> = ds.data.map { it[KEY_PULL_CURSOR].orEmpty() }

    /* --------------------------- mutators ------------------------------ */

    /**
     * Persist the sheet URL. Resetting the URL also resets [pullCursor]
     * to empty so a phone repointed at a different sheet pulls a full
     * dataset on its next sync — otherwise the device would only ask
     * the new sheet for changes since the OLD sheet's cursor, which is
     * meaningless on the new spreadsheet and would silently leave
     * the local DB in a half-populated state.
     */
    suspend fun setSheetUrl(url: String) {
        ds.edit {
            val trimmed = url.trim()
            val previous = it[KEY_SHEET_URL].orEmpty()
            it[KEY_SHEET_URL] = trimmed
            if (trimmed != previous) {
                it.remove(KEY_PULL_CURSOR)
            }
        }
    }

    suspend fun setPullCursor(value: String) {
        ds.edit {
            if (value.isBlank()) it.remove(KEY_PULL_CURSOR)
            else it[KEY_PULL_CURSOR] = value
        }
    }

    suspend fun setLastSyncedNow() {
        ds.edit {
            it[KEY_LAST_SYNCED_AT] = System.currentTimeMillis()
            it.remove(KEY_LAST_SYNC_ERROR)
        }
    }

    suspend fun setLastSyncError(message: String?) {
        ds.edit {
            if (message.isNullOrBlank()) it.remove(KEY_LAST_SYNC_ERROR)
            else it[KEY_LAST_SYNC_ERROR] = message
        }
    }

    suspend fun setPinEnabled(enabled: Boolean) {
        ds.edit {
            it[KEY_PIN_ENABLED] = enabled
            if (!enabled) it.remove(KEY_PIN_HASH)
        }
    }

    suspend fun setPin(rawPin: String) {
        ds.edit {
            it[KEY_PIN_HASH] = hashPin(rawPin)
            it[KEY_PIN_ENABLED] = true
        }
    }

    suspend fun verifyPin(rawPin: String): Boolean {
        val stored = ds.data.map { it[KEY_PIN_HASH] }.first() ?: return false
        return stored == hashPin(rawPin)
    }

    suspend fun stampForegroundNow() {
        ds.edit { it[KEY_LAST_FG_AT] = System.currentTimeMillis() }
    }

    /* --------------------------- internals ----------------------------- */

    /**
     * Hash the PIN with a fixed app-level salt. We don't use a
     * per-user salt because the lock is a soft barrier (a relative
     * picking up the phone), not a security primitive. Storing the
     * raw PIN would be worse than this.
     */
    private fun hashPin(rawPin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(SALT.toByteArray(Charsets.UTF_8))
        md.update(rawPin.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SALT = "santhalia.v1.pin.salt"

        val KEY_SHEET_URL = stringPreferencesKey("pref_v1_sheet_url")
        val KEY_LAST_SYNCED_AT = longPreferencesKey("pref_v1_last_synced_at")
        val KEY_LAST_SYNC_ERROR = stringPreferencesKey("pref_v1_last_sync_error")
        val KEY_PIN_ENABLED = booleanPreferencesKey("pref_v1_pin_enabled")
        val KEY_PIN_HASH = stringPreferencesKey("pref_v1_pin_hash")
        val KEY_LAST_FG_AT = longPreferencesKey("pref_v1_last_fg_at")
        val KEY_PULL_CURSOR = stringPreferencesKey("pref_v1_pull_cursor")
    }
}

// File-level extension lives outside the class so the underlying file
// is only created once per Context, which is the DataStore contract.
private val Context.dataStore: androidx.datastore.core.DataStore<Preferences>
    by preferencesDataStore(name = "settings")
