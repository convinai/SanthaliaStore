# Santhalia Store

Loyalty program website aur Android rate-card app for Santhalia Store — apni dukaan, apni technology.

---

## Website

A simple, mobile-first landing page for the shop's customer loyalty program. It includes the WhatsApp ordering numbers, the shop's Google Maps location, and a Google-Sheets–backed signup form.

- Live page: [`index.html`](./index.html)
- Open it directly in any browser, or host it via GitHub Pages / any static host.

## Android App — Santhalia Rate Card

An offline-first **purchase rate card** for the kirana shop owner. Use it on the counter to look up wholesale and retail rates for every item the shop stocks — even when the internet is slow or down. Rates sync from a Google Sheet so prices can be updated from anywhere without rebuilding the app.

### How to install the app (for the shop owner)

No technical knowledge needed. Follow these 3 steps:

1. **Open the Actions tab** on this repo's GitHub page in any browser.
2. **Click the latest successful "Build APK" workflow run** (look for the green tick).
3. **Scroll down to the "Artifacts" section** and download the file named `santhalia-rate-card-debug-apk-...`. It comes as a ZIP — unzip it to get `app-debug.apk`. Transfer that APK to your phone (WhatsApp / USB / Google Drive), tap to install, and if Android asks, **allow "Install from unknown sources"** for whichever app you used to open the file.

That's it — open "Santhalia Rate Card" from your app drawer.

### For developers

- App source, build instructions, and architecture notes: [`android-app/README.md`](./android-app/README.md)
- Backend (Google Sheet + Apps Script) setup: [`android-app/apps-script/README.md`](./android-app/apps-script/README.md)

---

Made with care for Santhalia Store.
