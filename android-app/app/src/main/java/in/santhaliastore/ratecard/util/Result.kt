package `in`.santhaliastore.ratecard.util

/**
 * Tiny app-local sealed result type. We deliberately don't use kotlin.Result
 * because it's `value class` and confuses Compose's smart-recomposition.
 *
 * Use this only for transient operation outcomes (sync test, save, etc.).
 * Long-lived UI state should be modelled as an explicit sealed UiState.
 */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val message: String, val cause: Throwable? = null) : AppResult<Nothing>

    val isOk: Boolean get() = this is Ok
}

inline fun <T> appResultOf(block: () -> T): AppResult<T> = try {
    AppResult.Ok(block())
} catch (e: Throwable) {
    AppResult.Err(e.message ?: e::class.java.simpleName, e)
}
