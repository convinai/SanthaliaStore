# Santhalia Rate Card — Google Sheets Sync Backend

Yeh chhota Google Apps Script aapke **Santhalia Rate Card** Android app ko ek Google Sheet se connect karta hai. Phone offline kaam karta hai aur jab internet milta hai, app saara data is script ke through aapke sheet mein automatically save kar deta hai.

You only need to set this up **once**. After that, the phone takes care of everything.

---

## What you need

- A Google account (the same one you want the data saved under).
- About 5 minutes.
- The Android app installed on your phone.

---

## Step-by-step setup

### 1. Create a new Google Sheet

1. Open [https://sheets.google.com](https://sheets.google.com).
2. Click the big **+ Blank** card to create a new sheet.
3. At the top-left, click the file name (it says *Untitled spreadsheet*) and rename it to **Santhalia Rate Card**.

You do not need to add any tabs or columns by hand. The script will create the `Items`, `PurchaseEntries` and `Crashes` tabs automatically the first time the phone syncs.

### 2. Open the Apps Script editor

1. In the same sheet, click the menu **Extensions → Apps Script**.
2. A new tab opens with a code editor and a file called `Code.gs` already inside.

### 3. Paste the code

1. In the Apps Script editor, select **everything** in `Code.gs` (Ctrl+A / Cmd+A) and delete it.
2. Open the file `Code.gs` from this folder on your computer.
3. Copy all of its contents and paste into the Apps Script editor.
4. Click the floppy-disk **Save** icon (or Ctrl+S / Cmd+S).
5. At the top of the editor where it says *Untitled project*, click and rename to **Santhalia Rate Card Sync**.

### 4. Deploy as a Web app

1. Click the blue **Deploy** button (top-right) → **New deployment**.
2. Click the gear icon next to *Select type* and choose **Web app**.
3. Fill in:
   - **Description**: `v1` (or anything you like)
   - **Execute as**: **Me (your-email@gmail.com)**
   - **Who has access**: **Anyone**
     - This is required so the Android app can call the URL without a Google login. It does **not** mean random people can see your data — they would still need the secret URL.
4. Click **Deploy**.
5. Google will ask you to **Authorize access**. Click *Authorize*, pick your Google account, and on the warning screen click *Advanced → Go to Santhalia Rate Card Sync (unsafe)* → *Allow*. (It says "unsafe" because Google has not reviewed your personal script — it is fine, you wrote it yourself.)
6. Copy the **Web app URL** that appears. It looks like:
   ```
   https://script.google.com/macros/s/AKfyc.....abc/exec
   ```

### 5. Paste the URL into the Android app

1. Open the Santhalia Rate Card app on your phone.
2. Go to **Settings → Sync**.
3. Paste the Web app URL into the *Sync URL* field and tap **Save**.
4. Tap **Test connection**. If everything is good, it will say *Connected*.

That's it — the app will now sync automatically.

### 6. Updating the script later

If you ever edit `Code.gs` (for example, to fix a bug):

1. Save the file in the Apps Script editor.
2. Click **Deploy → Manage deployments**.
3. Click the pencil icon next to your existing deployment.
4. Under *Version*, choose **New version** and click **Deploy**.

Important: The URL stays the same — you do **not** need to update the phone.

---

## How sync works

The phone is the boss. Aap jo bhi entry karte ho, woh phone ke andar SQLite database mein turant save ho jaata hai — internet ki zaroorat nahin. Jab WiFi ya mobile data milta hai, app ek `bulkSync` request bhejta hai jisme last sync ke baad ke saare changes hote hain. Yeh script har row ko `updatedAt` ke basis pe compare karta hai (last-write-wins) aur sheet mein save kar deta hai. Deletes ko hum *soft delete* karte hain (column `deleted = TRUE`) taaki dusre phone bhi delete dekh saken.

---

## Troubleshooting

**1. "Authorization required" / sync fails with HTML response**
The deployment was not set to *Anyone*. Go to **Deploy → Manage deployments → pencil icon → Who has access: Anyone**, save, and try again.

**2. "Script function not found: doGet"**
You probably pasted the code into the wrong file or saved as a different file name. Make sure the contents are inside `Code.gs` and click Save.

**3. Sync says "Too many changes in one bulkSync"**
The script caps each request at 200 changes to stay within Google's time limits. Open the app, tap **Settings → Sync → Sync now** twice — the app will split the queue automatically.

**4. Some rows say "Missing required field: updatedAt" in the error log**
Older rows on the phone may not have an `updatedAt` value. Open those entries on the phone and re-save them once — the app will fill in the timestamp and they will sync next time.

**5. Sheet has duplicate columns or extra rows**
Do **not** rename or reorder the header row. If the headers got changed by accident, the script will rewrite them on the next sync, but any data in the wrong column will be lost. Always work in the app, not directly in the sheet.

---

## Sheet schema reference

The script auto-creates these tabs. You should not edit them by hand.

### Tab: `Items`

| Column | Type | Notes |
|---|---|---|
| `code` | TEXT | Unique key. Item ka short code, e.g. `RICE-1KG`. |
| `name` | TEXT | Display name, e.g. `Basmati Rice 1kg`. |
| `unit` | TEXT (optional) | e.g. `kg`, `pcs`, `litre`. |
| `updatedAt` | TEXT (ISO 8601) | When this row was last changed on any phone. |
| `deleted` | TEXT (`TRUE`/`FALSE`) | Soft-delete flag. |

### Tab: `PurchaseEntries`

| Column | Type | Notes |
|---|---|---|
| `entryId` | TEXT (UUID) | Unique key for the entry. |
| `itemCode` | TEXT | Foreign key — must match a `code` in `Items`. |
| `date` | TEXT (`YYYY-MM-DD`) | Purchase date. |
| `pricePerUnit` | NUMBER | Price for one unit on that date. |
| `quantity` | NUMBER (optional) | How many units bought. |
| `supplier` | TEXT (optional) | Vendor / shop name. |
| `notes` | TEXT (optional) | Free-form notes. |
| `updatedAt` | TEXT (ISO 8601) | Last edit timestamp. |
| `deleted` | TEXT (`TRUE`/`FALSE`) | Soft-delete flag. |

### Tab: `Crashes`

App ke andar koi crash hota hai toh phone us ka detail ek file mein save kar leta hai aur next sync pe is tab mein automatically chala jaata hai. Aap ko kuch karne ki zaroorat nahin — agar rows yahan dikhne lagein, ka matlab hai phone ne kuch crashes pakde hain. Developer ko sheet ka link bhej dijiye, woh `stackTrace` column dekh ke fix kar dega.

| Column | Type | Notes |
|---|---|---|
| `crashId` | TEXT (UUID) | Unique key. Phone-side dedup so re-uploads don't double-row. |
| `timestamp` | TEXT (ISO 8601) | When the crash happened, in UTC. |
| `appVersion` | TEXT | App version name (e.g. `1.0.0`). |
| `appVersionCode` | NUMBER | App version code from `BuildConfig`. |
| `androidVersion` | TEXT | e.g. `13 (API 33)`. |
| `deviceModel` | TEXT | e.g. `samsung SM-A125F`. |
| `threadName` | TEXT | The thread that died. Usually `main`. |
| `message` | TEXT | The exception's top-level message. |
| `stackTrace` | TEXT | Full Java stack trace. Capped at 8 KB. |

---

## A note on safety

- The Web app URL is the only secret. Anyone with this URL can write to your sheet, so do not paste it in WhatsApp groups or screenshots.
- Your data lives in **your** Google Sheet. You can export it to Excel anytime via **File → Download → Microsoft Excel (.xlsx)**.
- If you ever want to wipe everything, just delete the rows in the sheet (keep the header row) and the next sync will work again.
