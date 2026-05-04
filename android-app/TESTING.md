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
│   └── FtsQueryTest.kt      # FTS4 prefix-query escape rules
└── sync/
    └── SyncDtosTest.kt      # Moshi round-trip for every sync action
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

## Manual smoke checklist

These behaviours rely on Android framework code (Room, WorkManager,
Compose) so we exercise them by hand on the test phone before each
release. Run through them after any change touching the underlying
surface.

- [ ] **Cold start** — Launch app from off; first item list paints under ~1 s on a 2 GB phone
- [ ] **Add item** — code + name only saves; duplicate code is rejected with the Hinglish message
- [ ] **Add item with first purchase** — filling price on the add-item screen creates both the item and the entry in one tap
- [ ] **Edit item** — opening edit prefills code, name, unit from the existing row
- [ ] **Edit entry** — opening edit prefills date, price, quantity, supplier, notes from the existing row
- [ ] **Delete item** — confirm dialog appears; entry history is wiped on confirm
- [ ] **Delete entry** — confirm dialog appears; entry disappears from item-detail history
- [ ] **Search** — typing partial code or name (Hinglish or English) filters the list within ~300 ms
- [ ] **Sync now** — tapping the Sync button pushes every active item + entry to the sheet, even rows that synced previously
- [ ] **Sync indicator** — pending icon flips to green check after successful sync; error state on a bad URL
- [ ] **Connection test** — Settings → Test connection shows green when the URL is good, red on a 404 / mismatched script
- [ ] **PIN lock** — enabling and entering a PIN gates the next cold start; disabling clears it
- [ ] **Rotation / config change** — adding-item form preserves typed values across orientation flip
- [ ] **Update install** — a freshly built APK installs in place over the previously installed one without uninstall (verifies the stable keystore)

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
