# Testing — Santhalia Rate Card

This document captures the testing strategy for the Android app and the
ground rules we follow whenever we add features or change existing code.

## Ground rules

1. **Tests gate every push.** `.github/workflows/build-android-apk.yml`
   runs `./gradlew testDebugUnitTest` before assembling the APK. A
   failing test fails the whole job, so a broken `main` never produces a
   downloadable APK.
2. **Every feature change updates this file.** When you add a screen,
   data model, repository method, or sync action, add a test for it and
   list it in the matrix below. If a behaviour can't be unit-tested,
   write a manual checklist entry instead.
3. **Sync wire format is sacred.** The DTOs in
   `sync/SyncDtos.kt` are pinned by `SyncDtosTest`. Any change to JSON
   field names there MUST be paired with a matching change in
   `apps-script/Code.gs`. The test exists to catch silent drift.

## Test layout

```
app/src/test/java/in/santhaliastore/ratecard/
├── util/
│   ├── TimeTest.kt          # ISO 8601, YYYY-MM-DD, display formatting
│   └── MoneyTest.kt         # ₹ formatting, lakh grouping, parse() tolerance
├── data/repo/
│   ├── FtsQueryTest.kt      # FTS4 prefix-query escape rules
│   └── CrashRepositoryTest.kt # File-backed crash queue read/clear/truncate
└── sync/
    ├── SyncDtosTest.kt      # Moshi round-trip for every push action
    ├── PullDtosTest.kt      # Moshi round-trip for the bidirectional pullChanges action
    ├── PullApplierConflictTest.kt # Last-writer-wins matrix for soft-delete propagation
    ├── CrashEventTest.kt    # Crash payload field names + JSON shape
    └── AppsScriptApiTest.kt # Retrofit interface installs cleanly + envelope() shape
```

These are pure JVM tests — they run on the host JDK with no emulator,
no Android SDK initialisation, and no Robolectric. They finish in well
under a second on the GitHub-hosted runner.

Anything that needs real Room, Compose UI, or WorkManager would belong
under `app/src/androidTest/` and be run on an emulator. We don't have
that wired up yet (it would slow CI to several minutes per run), so for
now **manual smoke tests** below cover those surfaces.

## Run locally

You need a JDK 17 on `PATH`. The Gradle wrapper handles everything else
(it will download the Android SDK on first run, which takes a few
minutes — only once).

```bash
cd android-app
./gradlew testDebugUnitTest
```

To run a single class:

```bash
./gradlew testDebugUnitTest --tests "in.santhaliastore.ratecard.util.TimeTest"
```

If the host doesn't have Java installed (the case for many Macs), push
the change and let CI run the tests — the workflow blocks the APK
artifact on a green test job.

## Test matrix

Each row tracks a behaviour and where it's covered. Update this when
adding new behaviour.

| Area | Behaviour | Coverage |
| --- | --- | --- |
| util/Time | `nowIso()` returns ISO 8601 with `Z` | TimeTest |
| util/Time | `todayLocal()` is `YYYY-MM-DD` | TimeTest |
| util/Time | `displayDate()` formats as `d MMM yyyy`, falls back to raw on bad input | TimeTest |
| util/Time | `localDateToMillis` ↔ `millisToLocalDate` round-trips | TimeTest |
| util/Time | `localDateToMillis("not-a-date")` returns null (no crash) | TimeTest |
| util/Money | `rupees()` strips trailing zeros, uses lakh grouping | MoneyTest |
| util/Money | `rupees(null)` / `plain(null)` / `parse(null)` are safe | MoneyTest |
| util/Money | `parse()` tolerates `₹` and `,` | MoneyTest |
| FTS query | empty input returns sentinel `""` (no SQLite crash) | FtsQueryTest |
| FTS query | tokens are lower-cased and prefixed with `*` | FtsQueryTest |
| FTS query | special chars (`-`, `"`, `*`) stripped | FtsQueryTest |
| FTS query | digits + alphanumeric codes preserved | FtsQueryTest |
| Sync DTOs | request envelope serialises `action` + `payload` | SyncDtosTest |
| Sync DTOs | `health` payload is `{}` | SyncDtosTest |
| Sync DTOs | `upsertItem` field names match Apps Script contract | SyncDtosTest |
| Sync DTOs | `upsertEntry` field names match Apps Script contract | SyncDtosTest |
| Sync DTOs | `bulkSync` exposes 4 arrays | SyncDtosTest |
| Sync DTOs | response parses with / without `errors` | SyncDtosTest |
| Pull DTOs | `pullChanges` payload exposes `sinceCursor` (empty string allowed) | PullDtosTest |
| Pull DTOs | `PulledItem` / `PulledEntry` field names match server contract | PullDtosTest |
| Pull DTOs | `PullChangesResponse` parses with / without optional `schemaVersion` and `time` | PullDtosTest |
| Pull DTOs | empty server response (no items, no entries, empty cursor) parses cleanly | PullDtosTest |
| Sync API | Retrofit can install the interface (no wildcard / annotation regression) | AppsScriptApiTest |
| Sync API | `envelope()` builds correct JSON for `health`, `upsertItem`, `bulkSync` | AppsScriptApiTest |
| Item repo | renaming an item's code repoints purchase history atomically (FK satisfied at every step, repointed entries flagged `pendingSync = 1`) | Manual smoke (needs Room, see "Rename item code…" entries below) |
| Add/Edit item nav | after a rename, the Edit screen hands the new code back so the navigator pops the stale Item Detail and pushes a fresh one at the new code | Manual smoke ("Rename navigation lands on the new code" below) |
| Item Detail safety | a stale Item Detail (code now soft-deleted via rename or delete) shows the "Yeh item ab nahi raha" state and hides the FAB, so writes cannot orphan against a dead code | Manual smoke ("Stale Item Detail refuses writes" below) |
| Layout containment | Home row, Item Detail header, and history rows stay single-line on extreme code/name/price values | Manual smoke ("Long values don't break layouts" below) |
| SyncRepository | `runFullSyncNow()` performs pull → apply → push in that order, aborting before push if pull fails | Manual smoke ("Bidirectional sync via Home refresh" / "Pull failure aborts before push" below) |
| SyncRepository | `runFullSyncNow()` writes lastSyncedAt on success, lastSyncError on failure | Manual smoke ("Sync now success/failure surfacing" below) |
| SyncRepository | `runFullSyncNow()` returns `AppResult.Ok(SyncOutcome(0,0,0))` when nothing pending AND nothing pulled, still stamps lastSyncedAt | Manual smoke ("Sync now after empty mark" below) |
| PullApplier | server changes apply atomically inside one Room transaction (items first, entries second, FK held) | Manual smoke ("First-install pull populates the local DB" below) |
| PullApplier | last-writer-wins on `updatedAt` — pulled row newer than local overwrites and clears `pendingSync`; pulled row older skips | Manual smoke ("Conflict resolution: local edit wins / server edit wins" below) |
| PullApplier | pulled `deleted = true` row writes a tombstone locally so the row disappears from the UI | Manual smoke ("Bidirectional sync via Home refresh" — soft-delete propagation step) |
| PullApplier (predicate) | tombstone propagates when `pulled.updatedAt > existing.updatedAt` (live local row gets soft-deleted) | PullApplierConflictTest |
| PullApplier (predicate) | tombstone is skipped when `pulled.updatedAt < existing.updatedAt` (newer local edit wins, push will resolve) | PullApplierConflictTest |
| PullApplier (predicate) | un-delete propagates when a newer pulled live row arrives over a local tombstone | PullApplierConflictTest |
| PullApplier (predicate) | equal timestamps with differing `deleted` flag — pulled wins (`>=`, not `>`) | PullApplierConflictTest |
| PullApplier (predicate) | first-pull (no existing local row) always applies the pulled row | PullApplierConflictTest |
| SyncRepository | `resetLocalAndPullFresh()` wipes items + entries inside one Room transaction, resets `pullCursor`, then runs a full pull-then-push | Manual smoke ("Reset local data via Settings" below) |
| SyncRepository | `resetLocalAndPullFresh()` leaves the local DB cleared (no rollback) when the post-wipe pull fails — `lastSyncError` is populated and the user can retry via Sync now | Manual smoke ("Reset local data — pull failure path" below) |
| Pull cursor | `setSheetUrl` resets `pullCursor` to empty so a phone repointed at a new sheet pulls a full dataset | Manual smoke ("Switching sheet URL re-pulls everything" below) |
| No background sync | nothing is enqueued via WorkManager any more — `notifyChange` is a no-op and SyncWorker no longer exists | Manual smoke ("No auto sync after item add" below) |
| Home screen | Top-bar refresh button runs the same pull-then-push sync as Settings | Manual smoke ("Bidirectional sync via Home refresh" below) |
| Home screen | Refresh button swaps to a 24 dp spinner while syncing without shifting the app bar layout | Manual smoke ("Home refresh button feedback" below) |
| Home screen | "Last sync" line under the search bar shows relative time and "Abhi tak sync nahi hua" pre-first-sync | Manual smoke ("Home last-sync label" below) |
| Snackbar copy | Push-only / pull-only / combined / nothing-new outcomes each render their own Hinglish line on Home and Settings | Manual smoke ("Bidirectional sync via Home refresh" below) |
| Crash DTOs | `CrashEvent` field names match the Apps Script `Crashes` tab columns verbatim | CrashEventTest |
| Crash DTOs | `appVersionCode` serialises as a JSON number, not a string | CrashEventTest |
| Crash DTOs | `LogCrashesPayload` exposes a single `crashes` array | CrashEventTest |
| Crash queue | `pendingCrashes()` returns empty list when the file is missing | CrashRepositoryTest |
| Crash queue | `pendingCrashes()` parses each JSON line in order | CrashRepositoryTest |
| Crash queue | malformed lines are silently skipped (half-write tolerance) | CrashRepositoryTest |
| Crash queue | `clearUploaded(ids)` removes only those ids, leaves the rest | CrashRepositoryTest |
| Crash queue | `clearUploaded(allIds)` deletes the file outright | CrashRepositoryTest |
| Crash queue | `truncateStackTrace()` cuts at 8 KB on a line boundary, appends marker | CrashRepositoryTest |
| Crash handler | uncaught-exception handler writes `crashes.log` then delegates to the previous handler | Manual smoke ("Crash captured + uploaded" below) |
| Crash sync | `pushPendingCrashes()` uploads and clears the file on success, leaves it on failure | Manual smoke ("Crash captured + uploaded" below) |

## Manual smoke checklist

These behaviours rely on Android framework code (Room, WorkManager,
Compose) so we exercise them by hand on the test phone before each
release. Run through them after any change touching the underlying
surface.

- [ ] **Cold start** — Launch app from off; first item list paints under ~1 s on a 2 GB phone
- [ ] **Add item** — code + name only saves; duplicate code is rejected with the Hinglish message
- [ ] **Add item with first purchase** — filling price on the add-item screen creates both the item and the entry in one tap
- [ ] **Edit item** — opening edit prefills code, name, unit from the existing row
- [ ] **Rename item code preserves history** — edit an item with existing entries, change only the **code**, save → opening detail under the new code shows every previous purchase entry; the old code no longer appears in the home list
- [ ] **Rename item code triggers re-sync** — after the rename, open Settings → pending count includes both the new item row and one row per repointed entry; running "Sync now" pushes them and the count returns to 0
- [ ] **Rename onto a previously-deleted code** — soft-delete item `OLD`, then edit a different item and rename its code to `OLD` → the rename succeeds (it revives the row with the renamed item's name/unit) and the entries from the source item are visible under `OLD`
- [ ] **Rename navigation lands on the new code** — full end-to-end:
  1. From Home, add item code `A` with one entry
  2. Tap the row → Item Detail for `A` opens (shows that one entry)
  3. Tap edit, change code to `B`, save
  4. After save you land on Item Detail for `B` (NOT a blank Item Detail for `A`); the previous entry is visible
  5. Tap the FAB and add another entry — it appears in `B`'s history
  6. Press back once → you land on Home (the stale `A` detail is NOT in the back stack)
  7. Trigger "Sync now"; only one item code (`B`) is in the `Items` sheet and every entry's `itemCode` is `B`
- [ ] **Stale Item Detail refuses writes** — open Item Detail for code `X` on one task / pop the back stack, then in another flow rename `X` → `Y` (or delete `X`). Re-foreground the original Item Detail for `X` → the screen shows the "Yeh item ab nahi raha" empty state and the FAB is gone, so no entry can be created against the dead code
- [ ] **Long values don't break layouts** — add an item with code = 50 chars, name = 100 chars, and a first-purchase price of 9999999.99. Confirm:
  - The Home row stays one line tall (no horizontal scroll, the trailing rate stays visible)
  - The Item Detail header card doesn't wrap into 4+ lines and the code chip ellipses
  - History rows stay one line tall — long suppliers / notes ellipsis instead of expanding the card
- [ ] **Edit entry** — opening edit prefills date, price, quantity, supplier, notes from the existing row
- [ ] **Delete item** — confirm dialog appears; entry history is wiped on confirm
- [ ] **Delete entry** — confirm dialog appears; entry disappears from item-detail history
- [ ] **Search** — typing partial code or name (Hinglish or English) filters the list within ~300 ms
- [ ] **Bidirectional sync via Home refresh** — On Phone A, add a new item, tap the Home top-right refresh button, watch for "Sync ho gaya — 1 rows upload" snackbar. On Phone B (same Sheet URL), tap refresh, watch for "Sync ho gaya — 1 nayi entries" snackbar and confirm the item now appears in the list. The "Last sync" timestamp under the search bar should update to "abhi abhi" / "1 minute pehle" on each phone. With both sides quiet, tapping refresh again on either phone should show "Sab kuch sync ho gaya — kuch naya nahi". When Phone A has unsynced edits AND new entries are waiting on the server, the snackbar should read "Sync ho gaya — N upload, M download".
- [ ] **Home refresh button feedback** — tap the refresh button; the icon swaps to a 24 dp spinner in the same slot, the rest of the top app bar (logo + Settings gear) does NOT shift horizontally, and the button is un-tappable until the sync resolves. Double-tapping during a sync does NOT spawn a second sync.
- [ ] **Home last-sync label** — on a fresh install (lastSyncedAt = 0) the line under the search bar reads "Abhi tak sync nahi hua". After the first successful sync it flips to "Last sync: abhi abhi"; navigate away for a few minutes and return — it now reads "Last sync: N minute pehle". The line is right-aligned, in `bodySmall` `onSurfaceVariant`, single line.
- [ ] **Sync now** — tapping the Sync button pulls server changes first, then pushes any locally-pending rows. Rows that already synced are NOT re-pushed (the new pull/push flow only sends genuinely pending edits, in contrast to the old "mark-all-pending" force-resync behaviour).
- [ ] **Sync now success/failure surfacing** — with a working URL the snackbar shows the bidirectional outcome (combined / push-only / pull-only / nothing-new) and `lastSyncedAt` updates on Settings; with a deliberately wrong URL the snackbar shows "Sync nahi hua: …" and the same error appears in red under "Pichhli error: …" on Settings (no silent failure path)
- [ ] **Sync now after empty mark** — tap "Sync now" twice in a row; the second tap should show the "kuch naya nahi" snackbar (zero pushed, zero pulled) and `lastSyncedAt` should still update — proving the success codepath fires even when both sides are quiet
- [ ] **Pending count visible** — after editing an item, the Settings screen shows "N rows sync hone baaki hain"; after a successful sync it switches to "Sab kuch sync ho gaya hai"
- [ ] **View details affordance** — tapping "Details dekhein" on Settings shows the human-formatted last-sync timestamp, pending count, and (if any) the verbatim last error in a wrap-friendly dialog
- [ ] **No auto sync after item add** — add a single item with the URL configured → wait 5+ minutes WITHOUT tapping any sync button. The pending count must stay at 1; `lastSyncedAt` must NOT update; the row must NOT appear in the sheet. (We dropped background sync to save battery — the user controls every push.) The instant the user taps Home refresh or Settings → "Sync now", the row goes through.
- [ ] **Bad URL surfaces in Settings** — set the URL to something that 404s, tap "Sync now" → the snackbar shows the failure, the red "Pichhli error" line is populated, and `lastSyncedAt` stays unchanged. The error stays put until the next successful sync (no background retries any more — fix the URL and tap "Sync now" again).
- [ ] **Reset local data via Settings** — with a non-empty local DB and a working URL, scroll to the bottom of the Sync settings group → tap "Sab data reset karein". The destructive confirm dialog appears with the title "Pakka reset karein?" and the destructive action labelled "Reset karein" in red. Tap Reset karein → a fullscreen scrim with an indeterminate spinner blocks the screen for the duration. On success, the snackbar reads "Reset ho gaya — N items, M entries fresh load" with non-zero counts pulled from the sheet; the local DB now mirrors the sheet exactly. Verify via `adb shell run-as in.santhaliastore.ratecard.debug sqlite3 databases/ratecard.db "SELECT COUNT(*) FROM items"` matches the sheet's active row count. The pending count drops to 0.
- [ ] **Reset local data — pull failure path** — Set the URL to a deliberately broken endpoint, then tap "Sab data reset karein" → confirm. The local wipe still runs (Home now shows the empty state), but the snackbar reads "Reset nahi hua: <error>" and the red "Pichhli error" line on Settings carries the same message. Fix the URL and tap "Sync now" — a clean full pull populates the DB without re-confirming the destructive dialog.
- [ ] **Reset local data — destructive button gating** — when the URL field is empty, the "Sab data reset karein" button is disabled (a reset against no URL would just no-op). When a sync is in flight, the button is disabled. When a reset is in flight, every other tappable control on the screen is occluded by the scrim and ignored.
- [ ] **Manual sheet clear → reset propagates** — On the sheet, manually delete every row from `Items` and `PurchaseEntries` (no `deleted=TRUE` tombstone, just remove). On the phone, tap Home refresh → nothing changes (by design — incremental pull only sees explicit changes). Now go to Settings → tap Sab data reset karein → confirm. The phone empties; the snackbar reports 0 items / 0 entries pulled (sheet is empty). The recovery path is the only honest fix for "manually-cleared sheet did not propagate".

### Soft-delete cross-device propagation

These exercise scenario 2d in the soft-delete audit. Run after any
change to `PullApplier`, the soft-delete DAO queries, or the home
queries' `deleted = 0` filter. Two phones (A and B) on the same
Sheet URL, both pre-populated with the relevant items.

- [ ] **Item soft-delete cascades to other devices** — On Phone A, item X has N entries. Tap delete on the item → confirm. Sync on A. Sync on B. Expected: X disappears from B's home (the home query filters `WHERE i.deleted = 0`). The N entries are NOT individually tombstoned — they remain alive locally with `itemCode = X`, but they are invisible because no list surfaces orphaned entries. If you navigate to a stale Item Detail bookmark for X on B, you should see the "Yeh item ab nahi raha" empty state.
- [ ] **Single entry soft-delete propagates** — On Phone A, item X has 3 entries (e1, e2, e3). Soft-delete e2. Sync on A. Sync on B. Expected: B's item-detail history for X shows e1 and e3 only; e2 is gone. The `latestForItem` and `pagedItemsWithLastEntry` queries should pick the next-most-recent live entry for the home row's last-rate display.
- [ ] **Atomic rename across two phones** — On Phone A, rename item X → Y (only the code changes). Sync on A. Sync on B. Expected: B's home now shows the item under code Y with all of X's purchase history visible; X is gone from the home list. The sequence on B's pull is items first (Y is created, X is tombstoned), then entries (their `itemCode` was already updated to Y by Phone A's atomic rename, so the FK is satisfied). If Phone B's local list still shows X for a beat, it's a pull-application bug — file it.
- [ ] **Delete-then-recreate same code** — On Phone A, soft-delete item X. Sync on A. Then on A, create a brand new item with code X (different name). Sync on A. The sheet should now have an X tombstone (older `serverUpdatedAt`) AND a live X (newer `serverUpdatedAt`) — the bulkSync `REPLACE on conflict` semantics overwrite the tombstone with the live row. On Phone B, sync. B sees the live X with the new name; the prior history is NOT attached (entries were never moved — they belong to the dead row).
- [ ] **Conflict — delete vs edit** — On Phone A (offline), soft-delete item X. On Phone B, edit X's name. Both come online. Whichever syncs second produces the final state by `updatedAt`. If B's edit timestamp is strictly newer than A's delete, the delete is undone server-side (B's live row replaces A's tombstone). This is last-writer-wins policy — document, do NOT special-case `deleted=true` to always win, that would silently destroy live local edits.

### Other smokes

- [ ] **Sync indicator** — pending icon flips to green check after successful sync; error state on a bad URL
- [ ] **Connection test** — Settings → Test connection shows green when the URL is good, red on a 404 / mismatched script
- [ ] **PIN lock** — enabling and entering a PIN gates the next cold start; disabling clears it
- [ ] **Rotation / config change** — adding-item form preserves typed values across orientation flip
- [ ] **Update install** — a freshly built APK installs in place over the previously installed one without uninstall (verifies the stable keystore)
- [ ] **Crash captured + uploaded** — Force a crash (e.g. a debug-only `throw RuntimeException("smoke")` button or `adb shell am crash`), confirm the system "App has stopped" dialog still appears (proves we delegated to the previous handler), reopen the app, confirm `<filesDir>/crashes.log` was written (`adb shell run-as in.santhaliastore.ratecard.debug cat files/crashes.log`), trigger a sync, then check that the Google Sheet's `Crashes` tab gained one row with the expected `crashId`, `appVersion`, `androidVersion`, `deviceModel`, `threadName`, `message`, and a non-empty `stackTrace`. After the sync, `crashes.log` should be absent (or only contain rows that are still pending). Re-trigger a sync without producing a new crash — the `Crashes` tab should NOT gain a duplicate row (server-side `crashId` dedup).

## Bidirectional sync (multi-device)

These checks rely on a real Apps Script web app + a second physical
phone (or two emulators) pointed at the same Sheet URL. Run them after
any change to `SyncRepository`, `PullApplier`, or the Apps Script
`pullChanges` action.

- [ ] **First-install pull populates the local DB** — Provision a clean install (Settings → "Clear data" or `adb shell pm clear in.santhaliastore.ratecard.debug`). Set the Sheet URL on the empty install. Tap Home refresh. The snackbar should report `M items + K entries downloaded`. Open Home — every active item from the sheet is in the list. Open one item — its full purchase history is visible. None of the pulled rows should be flagged "pending" (Settings pending count = 0). Sub-checks:
  - Pulled items / entries are written with `pendingSync = false` (verify via `adb shell run-as ... sqlite3 databases/ratecard.db "SELECT COUNT(*) FROM items WHERE pendingSync=1"` → must be 0).
  - Server-side soft-deleted rows (`deleted = 1` on the sheet) come down as tombstones — they don't appear in Home but they are present in the DB so a future re-add of the same code overwrites correctly.
- [ ] **Bidirectional sync via Home refresh** — Two phones (A and B) on the same Sheet URL, both already bootstrapped:
  1. On A, add item `Z` and one entry. Tap Home refresh → snackbar shows "1 upload" (or combined if there was anything on the server).
  2. On B, tap Home refresh → snackbar shows "1 nayi entries / 1 nayi item" (or combined). `Z` now appears in B's list; opening `Z` shows the entry from A.
  3. On B, edit `Z`'s name. Tap refresh → snackbar shows "1 upload".
  4. On A, tap refresh → name update lands on A.
  5. On A, soft-delete `Z`. Tap refresh.
  6. On B, tap refresh → `Z` disappears from the list (tombstone applied).
- [ ] **Conflict resolution: local edit wins / server edit wins** — Two phones, both bootstrapped, both with item `X`:
  1. Disable network on B. On B, edit `X`'s name to "B-name" (locally pending).
  2. On A, edit `X`'s name to "A-name". Tap refresh on A — pushes successfully.
  3. Re-enable network on B. Tap refresh on B.
  4. Expected: B's local "B-name" wins because B's `updatedAt` is strictly newer than A's (B edited last, regardless of which phone synced first). The pull skips A's older row, the push then sends "B-name" up. Verify on the sheet — final value is "B-name".
  5. Now on A, tap refresh again → A pulls "B-name" from the server. Both phones converge.
- [ ] **Pull failure aborts before push** — On a phone with one pending local edit, deliberately break the URL (point at a 404). Tap refresh. The snackbar must show the failure. Verify in the sheet that the local pending edit was NOT pushed (we abort before push to avoid clobbering server state we couldn't read). Pending count stays > 0; restoring the URL and re-tapping refresh resolves both sides.
- [ ] **Switching sheet URL re-pulls everything** — Phone is fully synced against Sheet 1 (some non-zero `pullCursor` is stored). On Settings, change the Sheet URL to a different sheet (Sheet 2 with different items). Tap Home refresh. The snackbar should report a fresh full-pull from Sheet 2 (items + entries counts equal whatever Sheet 2 contains). The local DB now reflects Sheet 2; Sheet 1's items that aren't in Sheet 2 are NOT removed (they're orphan locally — a fresh install would have been cleaner, but the cursor reset alone is enough for the user-visible "I changed sheets and pull works" smoke).
- [ ] **Cursor advances incrementally** — After a successful pull, immediately tap refresh again. The second pull should report `0 items + 0 entries` because the cursor advanced past everything. (If you see the same counts twice, the cursor isn't being persisted — bug in `SettingsRepository.setPullCursor` or the Apps Script's `pullChanges` cursor logic.)

## Stable signing keystore

The committed `android-app/keystore/santhalia.keystore` is the cert
every CI APK is signed with. Do **not** delete or rotate it — APKs
signed with a different cert cannot be installed in place, which forces
the user to uninstall and lose their data.

If the file is missing on a fresh checkout, run the **Bootstrap signing
keystore** workflow once via the GitHub Actions tab. It's idempotent:
re-running it after the keystore exists is a no-op.

Password is intentionally non-secret (`santhalia`) — this is a
sideload-only keystore, not for Play Store distribution. If we later
want to publish, we'll generate a separate release keystore stored in
GitHub Secrets and gate the release workflow on that.

## When you change something

1. Make your change.
2. Add or update the relevant test in `app/src/test/`.
3. Update the **Test matrix** in this file.
4. Push. CI will block the APK artifact on a green test job.
