# Santhalia Rate Card — Android app

Offline-first rate card / purchase price tracker for **Santhalia Store**, a kirana shop. Stores items and dated purchase prices on the phone (Room/SQLite) and syncs to a Google Sheet via the bundled Apps Script web app for cloud backup.

This module lives at `android-app/`. It is a single-project Android Studio app with no other modules.

---

## Prerequisites

| Tool | Version |
| --- | --- |
| JDK | 17 |
| Android Studio | Koala (2024.1) or newer |
| Android SDK Platform | 34 |
| Android SDK Build-Tools | 34.x |

You do not need a separate Gradle install — the wrapper handles that.

---

## Open in Android Studio (later)

1. Launch Android Studio.
2. Choose **Open** and pick the `android-app/` folder.
3. Let it sync. The first sync downloads Gradle 8.7 and the AGP 8.5 distribution; subsequent opens are quick.
4. Run on a device or emulator. Min SDK is 24 (Android 7.0 Nougat); target SDK is 34.

---

## Build via the Gradle wrapper

The wrapper sits at `android-app/gradlew` (POSIX) and `android-app/gradlew.bat` (Windows). All commands assume you're inside `android-app/`.

```bash
cd android-app
./gradlew tasks                  # list tasks
./gradlew assembleDebug          # build debug APK at app/build/outputs/apk/debug/
./gradlew assembleRelease        # minified release APK signed with the debug key
./gradlew lint                   # static checks
./gradlew test                   # JVM unit tests
```

The release build keeps the debug signing config so CI can produce an installable APK without per-store signing secrets. Replace with a real key before any Play Store upload.

---

## GitHub Actions APK download

The app is set up to build on push via the workflow at `.github/workflows/android.yml` (in the repo root). On every push the workflow:

1. Checks out the repo.
2. Sets up JDK 17.
3. Runs `./gradlew :app:assembleRelease` from `android-app/`.
4. Uploads the APK as a build artifact named `santhalia-rate-card-apk`.

To grab the APK:

1. Open the repo on GitHub → **Actions** tab.
2. Pick the latest workflow run on the **main** branch.
3. Scroll to **Artifacts** at the bottom.
4. Download `santhalia-rate-card-apk`. Unzip → `app-release.apk`.
5. Sideload onto an Android phone (allow installs from unknown sources).

If the workflow does not exist yet, create it in the parent repo — this README only describes the convention.

---

## Sync setup (one-time)

1. Open the spreadsheet that backs the rate card.
2. **Extensions → Apps Script** → paste `apps-script/Code.gs` from this repo.
3. **Deploy → New deployment → Web app**, set "Who has access" to **Anyone**.
4. Copy the deployment URL (looks like `https://script.google.com/macros/s/AKfyc.../exec`).
5. In the app, open **Settings → Sync settings → Google Sheet URL** and paste the URL.
6. Tap **Connection test karein** to confirm it's wired up. Tap **Abhi sync karein** to push.

The phone keeps everything locally regardless; sync is best-effort and runs on a WorkManager job with exponential backoff. You can use the app fully offline.

---

## Project layout

```
android-app/
├── settings.gradle.kts
├── build.gradle.kts                 # root, plugins block (no apply)
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml           # version catalog
│   └── wrapper/                     # gradle-wrapper.{jar,properties}
├── gradlew, gradlew.bat
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    ├── schemas/                     # Room exported schemas (one per version)
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/                     # vectors, themes, strings, colors
        └── java/in/santhaliastore/ratecard/
            ├── RateCardApp.kt       # Application + WorkManager config
            ├── di/AppContainer.kt   # manual DI (no Hilt)
            ├── data/
            │   ├── db/              # Room DB, entities, DAOs
            │   ├── repo/            # Item + Purchase repositories
            │   └── prefs/           # DataStore-backed settings
            ├── sync/
            │   ├── AppsScriptApi.kt # Retrofit interface
            │   ├── SyncDtos.kt      # request / response DTOs
            │   ├── SyncRepository.kt
            │   └── SyncWorker.kt    # CoroutineWorker
            ├── ui/
            │   ├── MainActivity.kt
            │   ├── nav/AppNavigation.kt
            │   ├── theme/           # Color, Type, Theme
            │   ├── components/      # EmptyState, ConfirmDialog, ...
            │   └── screens/
            │       ├── home/
            │       ├── item_detail/
            │       ├── add_item/
            │       ├── add_entry/
            │       ├── settings/
            │       └── lock/
            └── util/                # Time, Money, Result
```

---

## Tech notes

- **Single source of truth = Room.** UI observes Flow; Sync writes back into Room and flips `pendingSync = false` on ack.
- **FTS4** virtual table over `(code, name)` for fast prefix search. Search is debounced 300ms in `HomeViewModel`.
- **Paging 3** with Room paging integration; LazyColumn uses stable `key` per row.
- **Soft deletes** only — `deleted = true` + new `updatedAt`. The Apps Script honours this so re-syncs to other devices propagate the delete.
- **WorkManager** with exponential backoff (30s base) and `NetworkType.CONNECTED`. Unique work name `sync_pending` keeps duplicates out.
- **Manual DI** via `AppContainer` — no Hilt, faster cold start.
- **Java 17** + core library desugaring for `java.time` on API 24.
- **R8 minify + resource shrink** ON for release.

---

## Troubleshooting

- **First Gradle sync is slow.** It downloads the wrapper distribution (~150MB) — only happens once per machine.
- **Sync fails with "Sheet URL not set".** Open Settings → paste the Apps Script web app URL → tap **Connection test karein**.
- **PIN forgotten.** Uninstall + reinstall to clear local state. Cloud-backed data is restored on next sync (if configured).
