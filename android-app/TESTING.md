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
    ├── SyncDtosTest.kt      # Moshi round-trip for every sync action
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
| Sync API | Retrofit can install the interface (no wildcard / annotation regression) | AppsScriptApiTest |
| Sync API | `envelope()` builds correct JSON for `health`, `upsertItem`, `bulkSync` | AppsScriptApiTest |
| Item repo | renaming an item's code repoints purchase history atomically (FK satisfied at every step, repointed entries flagged `pendingSync = 1`) | Manual smoke (needs Room, see "Rename item code…" entries below) |
| Add/Edit item nav | after a rename, the Edit screen hands the new code back so the navigator pops the stale Item Detail and pushes a fresh one at the new code | Manual smoke ("Rename navigation lands on the new code" below) |
| Item Detail safety | a stale Item Detail (code now soft-deleted via rename or delete) shows the "Yeh item ab nahi raha" state and hides the FAB, so writes cannot orphan against a dead code | Manual smoke ("Stale Item Detail refuses writes" below) |
| Layout containment | Home row, Item Detail header, and history rows stay single-line on extreme code/name/price values | Manual smoke ("Long values don't break layouts" below) |
| SyncRepository | `runFullSyncNow()` writes lastSyncedAt on success, lastSyncError on failure | Manual smoke ("Sync now success/failure surfacing" below) |
| SyncRepository | `runFullSyncNow()` returns `AppResult.Ok(0)` when nothing pending and still stamps lastSyncedAt | Manual smoke ("Sync now after empty mark" below) |
| SyncWorker | success path stamps lastSyncedAt and clears lastSyncError | Manual smoke ("Auto sync after item add" below) |
| SyncWorker | failure path writes lastSyncError (visible on Settings) | Manual smoke ("Bad URL surfaces in Settings" below) |
| SyncWorker | retries are capped at MAX_ATTEMPTS so a bad URL doesn't bounce forever | Manual smoke ("Bad URL surfaces in Settings" below) |
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
- [ ] **Sync now** — tapping the Sync button pushes every active item + entry to the sheet, even rows that synced previously
- [ ] **Sync now success/failure surfacing** — with a working URL the snackbar shows "Sync ho gaya — N rows" and `lastSyncedAt` updates on Settings; with a deliberately wrong URL the snackbar shows "Sync nahi hua: …" and the same error appears in red under "Pichhli error: …" on Settings (no silent failure path)
- [ ] **Sync now after empty mark** — tap "Sync now" twice in a row; the second tap should still show "Sync ho gaya — kuch naya nahi" (zero processed) and `lastSyncedAt` should still update — proving the success codepath fires even when there's nothing pending
- [ ] **Pending count visible** — after editing an item, the Settings screen shows "N rows sync hone baaki hain"; after a successful sync it switches to "Sab kuch sync ho gaya hai"
- [ ] **View details affordance** — tapping "Details dekhein" on Settings shows the human-formatted last-sync timestamp, pending count, and (if any) the verbatim last error in a wrap-friendly dialog
- [ ] **Auto sync after item add** — add a single item with the URL configured → within 30 s the worker picks up the change, the pending count drops to 0, `lastSyncedAt` updates, and the row appears in the sheet
- [ ] **Bad URL surfaces in Settings** — set the URL to something that 404s, tap "Sync now" → the snackbar shows the failure, the red "Pichhli error" line is populated, and `lastSyncedAt` stays unchanged. After a few minutes (≤ MAX_ATTEMPTS retries via the worker) WorkManager stops bouncing the work — the "Pichhli error" remains the last reported failure
- [ ] **Sync indicator** — pending icon flips to green check after successful sync; error state on a bad URL
- [ ] **Connection test** — Settings → Test connection shows green when the URL is good, red on a 404 / mismatched script
- [ ] **PIN lock** — enabling and entering a PIN gates the next cold start; disabling clears it
- [ ] **Rotation / config change** — adding-item form preserves typed values across orientation flip
- [ ] **Update install** — a freshly built APK installs in place over the previously installed one without uninstall (verifies the stable keystore)
- [ ] **Crash captured + uploaded** — Force a crash (e.g. a debug-only `throw RuntimeException("smoke")` button or `adb shell am crash`), confirm the system "App has stopped" dialog still appears (proves we delegated to the previous handler), reopen the app, confirm `<filesDir>/crashes.log` was written (`adb shell run-as in.santhaliastore.ratecard.debug cat files/crashes.log`), trigger a sync, then check that the Google Sheet's `Crashes` tab gained one row with the expected `crashId`, `appVersion`, `androidVersion`, `deviceModel`, `threadName`, `message`, and a non-empty `stackTrace`. After the sync, `crashes.log` should be absent (or only contain rows that are still pending). Re-trigger a sync without producing a new crash — the `Crashes` tab should NOT gain a duplicate row (server-side `crashId` dedup).

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
