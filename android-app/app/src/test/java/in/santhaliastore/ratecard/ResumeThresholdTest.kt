package `in`.santhaliastore.ratecard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pin the activity-resume auto-sync rule.
 *
 * The predicate `RateCardApp.shouldAutoSyncOnResume(now, last)` decides
 * whether a fresh `ON_START` event has been long enough away from the
 * previous one to justify a sync. Three behaviours we don't want to
 * regress:
 *
 *   1. Cold start (lastForegroundMillis == 0) ALWAYS triggers a sync.
 *      Process death IS a cold start; without this, a freshly-launched
 *      app would never auto-sync until 5 min after the first observed
 *      foreground event, which defeats the purpose entirely.
 *
 *   2. A short backgrounding (camera, WhatsApp lookup) does NOT trigger.
 *      We pay the network cost only when the gap is meaningful.
 *
 *   3. A gap of exactly the threshold DOES trigger (`>=`, not `>`).
 *      Otherwise a 5:00.000 nap would silently not sync; users who
 *      step away for "about five minutes" should always come back to
 *      fresh data.
 */
class ResumeThresholdTest {

    @Test
    fun `cold start always triggers a sync`() {
        // lastForegroundMillis == 0L is the field's default state on a
        // freshly-constructed Application instance, i.e. process cold
        // start. The predicate must return true here regardless of
        // wall-clock so the first ON_START always picks up server
        // changes that landed while we were dead.
        assertTrue(
            RateCardApp.shouldAutoSyncOnResume(
                nowMillis = 1L,
                lastForegroundMillis = 0L
            )
        )
        assertTrue(
            "even with a sane wall clock cold start should still fire",
            RateCardApp.shouldAutoSyncOnResume(
                nowMillis = System.currentTimeMillis(),
                lastForegroundMillis = 0L
            )
        )
    }

    @Test
    fun `quick task switch does not trigger`() {
        // Half a minute away from the app — skip. Coming back from a
        // camera capture or a quick WhatsApp lookup should NOT cost a
        // network round-trip. This is the whole point of having a
        // threshold instead of syncing on every resume.
        val now = 10_000_000L
        val thirtySecondsAgo = now - TimeUnit.SECONDS.toMillis(30)
        assertFalse(
            RateCardApp.shouldAutoSyncOnResume(
                nowMillis = now,
                lastForegroundMillis = thirtySecondsAgo
            )
        )
    }

    @Test
    fun `four minutes away does not trigger`() {
        // Just under the threshold. Confirms the boundary is at 5 min
        // exactly, not "any backgrounding over a couple minutes".
        val now = 10_000_000L
        val fourMinutesAgo = now - TimeUnit.MINUTES.toMillis(4)
        assertFalse(
            RateCardApp.shouldAutoSyncOnResume(
                nowMillis = now,
                lastForegroundMillis = fourMinutesAgo
            )
        )
    }

    @Test
    fun `exactly five minutes away triggers`() {
        // Boundary check. We use `>=` so a gap of exactly 5 minutes
        // counts as "long enough"; flipping to `>` would silently miss
        // the user who napped for round numbers of minutes.
        val now = 10_000_000L
        val fiveMinutesAgo = now - TimeUnit.MINUTES.toMillis(5)
        assertTrue(
            RateCardApp.shouldAutoSyncOnResume(
                nowMillis = now,
                lastForegroundMillis = fiveMinutesAgo
            )
        )
    }

    @Test
    fun `long absence triggers`() {
        // A user who left the app open in the recents stack overnight
        // and comes back the next morning absolutely needs a sync.
        val now = 10_000_000L
        val tenHoursAgo = now - TimeUnit.HOURS.toMillis(10)
        assertTrue(
            RateCardApp.shouldAutoSyncOnResume(
                nowMillis = now,
                lastForegroundMillis = tenHoursAgo
            )
        )
    }

    @Test
    fun `threshold is exactly five minutes in millis`() {
        // Pin the constant itself so a future "lower it to 2 min" or
        // "raise it to 30 min" change has to flip this test too,
        // forcing the author to re-read the docs and the manual smoke
        // entries that depend on the value.
        assertTrue(
            "threshold should be 5 minutes",
            RateCardApp.RESUME_AUTO_SYNC_THRESHOLD_MS == TimeUnit.MINUTES.toMillis(5)
        )
    }
}
