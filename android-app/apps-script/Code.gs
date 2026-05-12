/**
 * Santhalia Rate Card — Google Apps Script Backend
 * --------------------------------------------------
 * This script is the cloud sync backend for the offline-first
 * "Santhalia Rate Card" Android app. The Android app stores all
 * data locally (SQLite/Room) and periodically POSTs changes here.
 *
 * Four tabs are auto-created in the bound spreadsheet:
 *   - Items            (rate card items)
 *   - PurchaseEntries  (price history per item, per date)
 *   - Bills            (purchase invoices with optional Drive image attachments)
 *   - Crashes          (uncaught-exception reports from the phone)
 *
 * Sync model:
 *   - Last-write-wins by `updatedAt` (ISO 8601 string)
 *   - Soft deletes (deleted=TRUE) so phones can re-sync deletes
 *   - bulkSync caps at 200 changes per request to stay within limits
 *
 * Author note: written for clarity. The shop owner may peek inside,
 * so comments are friendly and the logic is straightforward.
 */

// Bump on additive backend-schema changes. v4 adds the derived
// `imageLink` tail column to the Bills sheet — a clickable HYPERLINK
// formula computed from `imageFileIds` so the shop owner can open a
// bill photo straight from the Sheet without juggling Drive URLs.
// The bump is informational only; the Android app ignores the new
// column (wire contract is unchanged).
const SCHEMA_VERSION = 4;

// --- Sheet/tab names and headers (the source of truth for column order) ---
const ITEMS_SHEET = 'Items';
const ENTRIES_SHEET = 'PurchaseEntries';
const BILLS_SHEET = 'Bills';
const CRASHES_SHEET = 'Crashes';

// `serverUpdatedAt` is the bidirectional-sync cursor column. The server
// stamps it on every write (upsert OR soft-delete); clients never send
// it. Pull requests filter rows by `serverUpdatedAt > sinceCursor`, so
// using server time keeps things immune to client clock skew.
const ITEMS_HEADERS = ['code', 'name', 'unit', 'updatedAt', 'deleted', 'serverUpdatedAt'];
const ENTRIES_HEADERS = [
  'entryId',
  'itemCode',
  'date',
  'pricePerUnit',
  'quantity',
  'supplier',
  'notes',
  'updatedAt',
  'deleted',
  'serverUpdatedAt'
];
// Bills carry a purchase invoice — header date + optional supplier +
// optional total + free-text notes + a CSV of Drive file IDs (one per
// attached image). Mirrors ENTRIES_HEADERS shape so the same in-memory
// upsert / soft-delete helpers compose cleanly.
// `imageLink` is a display-only derived column. It holds a Google
// Sheets HYPERLINK formula computed from `imageFileIds` so the shop
// owner can click a bill row and open the photo directly. It is NOT
// part of the wire contract — clients neither read nor write it; we
// (re)compute it server-side on every upsert. Kept as the trailing
// column so the existing tail-append migration logic in
// `loadSheetContext_` picks it up cleanly on first run.
const BILLS_HEADERS = [
  'id',
  'date',
  'supplier',
  'totalAmount',
  'notes',
  'imageFileIds',
  'updatedAt',
  'deleted',
  'serverUpdatedAt',
  'imageLink'
];
// Crash log columns — order MUST match the Android CrashEvent DTO
// field order so the sheet stays readable even when columns are
// auto-created. crashId is the dedup key (first column).
const CRASHES_HEADERS = [
  'crashId',
  'timestamp',
  'appVersion',
  'appVersionCode',
  'androidVersion',
  'deviceModel',
  'threadName',
  'message',
  'stackTrace'
];

// Hard limit on a single bulkSync payload. Apps Script has a 6 minute
// execution cap; 200 rows finishes well within that even on a cold start.
const BULK_LIMIT = 200;
// Hard limit on a single logCrashes payload. The phone caps itself at
// 50 too; if the device has ever produced more than 50 crashes between
// syncs, the user has bigger problems than a slow sheet write.
const CRASHES_LIMIT = 50;

// Hard cap on how many rows pullChanges returns per sheet per call.
// The Apps Script response budget is large (~50 MB) so this is a
// defensive guard, not a real-world ceiling. The client should keep
// polling with the new cursor until it gets fewer than PULL_LIMIT
// rows back from BOTH sheets in the same response.
const PULL_LIMIT = 1000;

// How long to wait for the script lock (concurrent writes from multiple
// phones are possible). 30s is long enough to ride out a normal sync.
const LOCK_TIMEOUT_MS = 30 * 1000;

// Belt-and-suspenders against Google Sheets' silent string-to-Date
// auto-conversion. For the columns whose values must round-trip as
// strings (ISO 8601 timestamps, `YYYY-MM-DD` purchase dates), we set
// the cell number format to plain text (`@`) on first load. New writes
// land in `@`-formatted cells and stay strings; existing cells that
// already auto-converted to Date stay as Date in storage but are
// normalized on read by toIsoTimestamp_ / toLocalDate_.
const TEXT_FORMAT_COLS = {
  Items:           ['updatedAt', 'serverUpdatedAt'],
  // `quantity` is free-form (e.g. "5 kg", "1 packet") so we keep it
  // plain-text too. Without this, a value like "1.5" would be coerced
  // into a number on the way back, breaking the String|null contract.
  PurchaseEntries: ['date', 'quantity', 'updatedAt', 'serverUpdatedAt'],
  // `imageFileIds` is intentionally NOT pinned to `@` — it's a CSV of
  // opaque Drive UUIDs, none of which look date- or number-shaped, so
  // there's nothing for Sheets to silently coerce. `totalAmount` is a
  // real number column and must stay coercible.
  Bills:           ['date', 'updatedAt', 'serverUpdatedAt']
};


/* =========================================================================
 *  ENTRY POINTS
 * ========================================================================= */

/**
 * GET handler — used as a quick health check from a browser.
 * Just returns the same payload as `health`.
 */
function doGet(e) {
  return jsonResponse_({
    ok: true,
    action: 'health',
    processed: 0,
    schemaVersion: SCHEMA_VERSION,
    time: new Date().toISOString()
  });
}

/**
 * POST handler — the Android app calls this with a JSON body:
 *   { "action": "<name>", "payload": { ... } }
 *
 * We dispatch to the right handler based on `action`.
 */
function doPost(e) {
  let body = {};
  try {
    body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
  } catch (err) {
    return jsonResponse_({
      ok: false,
      action: 'unknown',
      processed: 0,
      errors: [{ index: -1, key: '', message: 'Invalid JSON body: ' + err.message }],
      time: new Date().toISOString()
    });
  }

  const action = String(body.action || '').trim();
  const payload = body.payload || {};

  try {
    switch (action) {
      case 'health':           return handleHealth_();
      case 'upsertItem':       return handleUpsertItem_(payload);
      case 'deleteItem':       return handleDeleteItem_(payload);
      case 'upsertEntry':      return handleUpsertEntry_(payload);
      case 'deleteEntry':      return handleDeleteEntry_(payload);
      case 'upsertBill':       return handleUpsertBill_(payload);
      case 'deleteBill':       return handleDeleteBill_(payload);
      case 'uploadBillImage':  return handleUploadBillImage_(payload);
      case 'deleteBillImage':  return handleDeleteBillImage_(payload);
      case 'bulkSync':         return handleBulkSync_(payload);
      case 'pullChanges':      return handlePullChanges_(payload);
      case 'logCrashes':       return handleLogCrashes_(payload);
      default:
        return jsonResponse_({
          ok: false,
          action: action || 'unknown',
          processed: 0,
          errors: [{ index: -1, key: '', message: 'Unknown action: ' + action }],
          time: new Date().toISOString()
        });
    }
  } catch (err) {
    // Last-resort safety net so the phone always sees a JSON reply.
    return jsonResponse_({
      ok: false,
      action: action || 'unknown',
      processed: 0,
      errors: [{ index: -1, key: '', message: 'Server error: ' + (err && err.message ? err.message : String(err)) }],
      time: new Date().toISOString()
    });
  }
}


/* =========================================================================
 *  ACTION HANDLERS
 * ========================================================================= */

function handleHealth_() {
  return jsonResponse_({
    ok: true,
    action: 'health',
    processed: 0,
    schemaVersion: SCHEMA_VERSION,
    time: new Date().toISOString()
  });
}

function handleUpsertItem_(payload) {
  const errors = [];
  let processed = 0;

  withLock_(function () {
    const ctx = loadSheetContext_(ITEMS_SHEET, ITEMS_HEADERS);
    const result = upsertItemInMemory_(ctx, payload, 0);
    if (result.error) {
      errors.push(result.error);
    } else {
      processed = 1;
    }
    flushSheet_(ctx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'upsertItem',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}

function handleDeleteItem_(payload) {
  const errors = [];
  let processed = 0;

  withLock_(function () {
    const ctx = loadSheetContext_(ITEMS_SHEET, ITEMS_HEADERS);
    const result = softDeleteInMemory_(ctx, 'code', payload, 0);
    if (result.error) {
      errors.push(result.error);
    } else {
      processed = 1;
    }
    flushSheet_(ctx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'deleteItem',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}

function handleUpsertEntry_(payload) {
  const errors = [];
  let processed = 0;

  withLock_(function () {
    const ctx = loadSheetContext_(ENTRIES_SHEET, ENTRIES_HEADERS);
    const result = upsertEntryInMemory_(ctx, payload, 0);
    if (result.error) {
      errors.push(result.error);
    } else {
      processed = 1;
    }
    flushSheet_(ctx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'upsertEntry',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}

function handleDeleteEntry_(payload) {
  const errors = [];
  let processed = 0;

  withLock_(function () {
    const ctx = loadSheetContext_(ENTRIES_SHEET, ENTRIES_HEADERS);
    const result = softDeleteInMemory_(ctx, 'entryId', payload, 0);
    if (result.error) {
      errors.push(result.error);
    } else {
      processed = 1;
    }
    flushSheet_(ctx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'deleteEntry',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}

function handleUpsertBill_(payload) {
  const errors = [];
  let processed = 0;

  withLock_(function () {
    const ctx = loadSheetContext_(BILLS_SHEET, BILLS_HEADERS);
    const result = upsertBillInMemory_(ctx, payload, 0);
    if (result.error) {
      errors.push(result.error);
    } else {
      processed = 1;
    }
    flushSheet_(ctx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'upsertBill',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}

function handleDeleteBill_(payload) {
  const errors = [];
  let processed = 0;

  withLock_(function () {
    const ctx = loadSheetContext_(BILLS_SHEET, BILLS_HEADERS);
    const result = softDeleteInMemory_(ctx, 'id', payload, 0);
    if (result.error) {
      errors.push(result.error);
    } else {
      processed = 1;
    }
    flushSheet_(ctx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'deleteBill',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}

/**
 * Upload one bill image to Drive. Payload:
 *   { billId, fileName, mimeType, dataBase64 }
 *
 * The phone holds bill metadata in SQLite and uploads each image as a
 * separate Drive file — the Bills row only stores a CSV of file IDs so
 * we don't blow the per-row size budget on base64 blobs. Files live in
 * "SanthaliaStore Bills" in My Drive (auto-created on first use).
 *
 * Response shape is intentionally NOT the standard SyncResponse — the
 * phone needs the Drive file ID + a viewable URL back in the same call
 * so it can update its local row without a follow-up roundtrip:
 *   { ok, fileId, viewUrl, time }
 * Errors still come back as { ok:false, errors:[...], time } so the
 * client can use the same error-decoding path.
 */
function handleUploadBillImage_(payload) {
  try {
    const billId     = requireString_(payload && payload.billId,     'billId');
    const fileName   = requireString_(payload && payload.fileName,   'fileName');
    const mimeType   = requireString_(payload && payload.mimeType,   'mimeType');
    const dataBase64 = requireString_(payload && payload.dataBase64, 'dataBase64');

    // Derive a sensible extension. We prefer the suffix the phone sent
    // (it picked the right one when reading from the gallery / camera),
    // but fall back to the mime type so we never end up with no suffix.
    const dot = fileName.lastIndexOf('.');
    let ext = (dot > 0 && dot < fileName.length - 1) ? fileName.substring(dot + 1) : '';
    if (!ext) {
      // crude mime → ext fallback; only the image types the phone sends.
      if      (mimeType === 'image/jpeg') ext = 'jpg';
      else if (mimeType === 'image/png')  ext = 'png';
      else if (mimeType === 'image/webp') ext = 'webp';
      else                                ext = 'bin';
    }

    // Filename pattern locks each image to its bill and makes the
    // Drive folder sortable by upload time without needing metadata.
    const stamp = nowIso_().replace(/[:.]/g, '-');
    const safeBillId = billId.replace(/[^A-Za-z0-9._-]/g, '_');
    const driveName = safeBillId + '_' + stamp + '.' + ext;

    const folder = getOrCreateBillsFolder_();
    const blob = Utilities.newBlob(Utilities.base64Decode(dataBase64), mimeType, driveName);
    const file = folder.createFile(blob);

    // Open the file to "anyone with the link, view-only" so other
    // devices — which fetch the image as an unauthenticated HTTP
    // request through Coil, no Google sign-in cookie attached — can
    // actually render it. Without this step the upload appears to
    // succeed but the second phone hits a Drive login wall on the
    // `uc?id=...&export=view` URL and the Bills detail screen shows
    // a broken-image placeholder (the symptom that surfaced once the
    // app started being installed on more than one phone).
    //
    // Privacy note: Drive file IDs are 33-char URL-safe randoms with
    // UUID-grade entropy — they are not guessable. The IDs flow only
    // through the owner's private Sheet, so "anyone with link" in
    // practice means "anyone the owner has shared the sheet with",
    // which IS the trust boundary we want.
    //
    // Best-effort: a sharing failure does NOT abort the upload. The
    // file is on Drive and the row will sync; the user can re-run
    // `repairBillImageSharing` from the editor to retroactively
    // open every existing image in the bills folder.
    try {
      file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
    } catch (shareErr) {
      // Don't fail the upload over a sharing API hiccup — the bytes
      // are safely in Drive. Log so the script owner can see it in
      // Execution log.
      try {
        Logger.log('Sharing failed for ' + file.getId() + ': ' + (shareErr && shareErr.message));
      } catch (_) { /* logger unavailable in some contexts */ }
    }

    const fileId = file.getId();
    // `uc?export=view` is the documented direct-view URL for an image
    // in Drive. The phone uses it as the <img src> after pulling.
    const viewUrl = 'https://drive.google.com/uc?id=' + fileId + '&export=view';

    return jsonResponse_({
      ok: true,
      action: 'uploadBillImage',
      fileId: fileId,
      viewUrl: viewUrl,
      time: nowIso_()
    });
  } catch (err) {
    return jsonResponse_({
      ok: false,
      action: 'uploadBillImage',
      processed: 0,
      errors: [{ index: 0, key: (payload && payload.billId) ? String(payload.billId) : '', message: err && err.message ? err.message : String(err) }],
      time: new Date().toISOString()
    });
  }
}

/**
 * Move a bill image to the `_deleted/` recovery sub-folder. Payload:
 *   { fileId }
 *
 * Idempotent: if the file is already gone (phone retrying after a
 * dropped response, say) we return ok=true with processed=0 so the
 * client treats the delete as successful and stops retrying.
 */
function handleDeleteBillImage_(payload) {
  try {
    const fileId = requireString_(payload && payload.fileId, 'fileId');

    let file = null;
    try {
      file = DriveApp.getFileById(fileId);
    } catch (lookupErr) {
      // DriveApp throws on missing / inaccessible IDs. Treat as already-deleted.
      return jsonResponse_({
        ok: true,
        action: 'deleteBillImage',
        processed: 0,
        time: new Date().toISOString()
      });
    }

    // We don't actually delete — moving to `_deleted/` keeps the file
    // recoverable if the user later realises they wanted that photo.
    // The shop owner can rescue images via Drive UI without our help.
    const parent = getOrCreateBillsFolder_();
    const deletedFolder = getOrCreateDeletedSubfolder_(parent);
    file.moveTo(deletedFolder);

    return jsonResponse_({
      ok: true,
      action: 'deleteBillImage',
      processed: 1,
      time: new Date().toISOString()
    });
  } catch (err) {
    return jsonResponse_({
      ok: false,
      action: 'deleteBillImage',
      processed: 0,
      errors: [{ index: 0, key: (payload && payload.fileId) ? String(payload.fileId) : '', message: err && err.message ? err.message : String(err) }],
      time: new Date().toISOString()
    });
  }
}

/**
 * Bulk sync. Accepts up to BULK_LIMIT total changes split across:
 *   payload.items          — items to upsert
 *   payload.entries        — entries to upsert
 *   payload.bills          — bills to upsert
 *   payload.deletedItems   — items to soft-delete (by code)
 *   payload.deletedEntries — entries to soft-delete (by entryId)
 *   payload.deletedBills   — bills to soft-delete (by id)
 *
 * We process every row inside a try/catch so one bad row does not
 * poison the whole batch. The response lists per-row errors with
 * the original index and key so the phone can retry just those.
 */
function handleBulkSync_(payload) {
  const items          = Array.isArray(payload.items)          ? payload.items          : [];
  const entries        = Array.isArray(payload.entries)        ? payload.entries        : [];
  const bills          = Array.isArray(payload.bills)          ? payload.bills          : [];
  const deletedItems   = Array.isArray(payload.deletedItems)   ? payload.deletedItems   : [];
  const deletedEntries = Array.isArray(payload.deletedEntries) ? payload.deletedEntries : [];
  const deletedBills   = Array.isArray(payload.deletedBills)   ? payload.deletedBills   : [];

  const total = items.length + entries.length + bills.length
              + deletedItems.length + deletedEntries.length + deletedBills.length;

  if (total > BULK_LIMIT) {
    return jsonResponse_({
      ok: false,
      action: 'bulkSync',
      processed: 0,
      errors: [{
        index: -1,
        key: '',
        message: 'Too many changes in one bulkSync (' + total + '). Max allowed is ' + BULK_LIMIT + '. Please split into smaller batches.'
      }],
      time: new Date().toISOString()
    });
  }

  const errors = [];
  let processed = 0;

  withLock_(function () {
    // Load each sheet only once, mutate in memory, write once at the end.
    const itemsCtx   = loadSheetContext_(ITEMS_SHEET, ITEMS_HEADERS);
    const entriesCtx = loadSheetContext_(ENTRIES_SHEET, ENTRIES_HEADERS);
    const billsCtx   = loadSheetContext_(BILLS_SHEET, BILLS_HEADERS);

    // 1) Item upserts
    for (let i = 0; i < items.length; i++) {
      const r = upsertItemInMemory_(itemsCtx, items[i], i);
      if (r.error) errors.push(r.error); else processed++;
    }

    // 2) Item deletes
    for (let i = 0; i < deletedItems.length; i++) {
      const r = softDeleteInMemory_(itemsCtx, 'code', deletedItems[i], items.length + i);
      if (r.error) errors.push(r.error); else processed++;
    }

    // 3) Entry upserts
    for (let i = 0; i < entries.length; i++) {
      const r = upsertEntryInMemory_(entriesCtx, entries[i], items.length + deletedItems.length + i);
      if (r.error) errors.push(r.error); else processed++;
    }

    // 4) Entry deletes
    for (let i = 0; i < deletedEntries.length; i++) {
      const r = softDeleteInMemory_(
        entriesCtx,
        'entryId',
        deletedEntries[i],
        items.length + deletedItems.length + entries.length + i
      );
      if (r.error) errors.push(r.error); else processed++;
    }

    // 5) Bill upserts
    const billsUpsertBase = items.length + deletedItems.length + entries.length + deletedEntries.length;
    for (let i = 0; i < bills.length; i++) {
      const r = upsertBillInMemory_(billsCtx, bills[i], billsUpsertBase + i);
      if (r.error) errors.push(r.error); else processed++;
    }

    // 6) Bill deletes
    const billsDeleteBase = billsUpsertBase + bills.length;
    for (let i = 0; i < deletedBills.length; i++) {
      const r = softDeleteInMemory_(billsCtx, 'id', deletedBills[i], billsDeleteBase + i);
      if (r.error) errors.push(r.error); else processed++;
    }

    // Single batched write per sheet — much faster than row-by-row.
    flushSheet_(itemsCtx);
    flushSheet_(entriesCtx);
    flushSheet_(billsCtx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'bulkSync',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}


/**
 * Pull changes from the server, newer than `sinceCursor`.
 *
 * Request payload:
 *   { sinceCursor: "<ISO 8601 string or empty/missing>" }
 *
 * Behaviour:
 *   - Empty / missing / unparseable cursor → return everything (capped).
 *   - Otherwise return rows whose `serverUpdatedAt` is strictly greater
 *     than the cursor. Comparison is lexicographic on ISO 8601 UTC `Z`
 *     strings, which matches numeric time order as long as the server
 *     keeps using `nowIso_()` for every stamp.
 *   - Rows are sorted ascending by `serverUpdatedAt` so the client can
 *     apply them in chronological order.
 *   - Soft-deleted rows are NOT included in the response. The client
 *     explicitly opted out of cross-device delete propagation; each
 *     phone manages its own deletes locally. The cursor still advances
 *     past tombstone events so we don't keep re-evaluating them on
 *     every pull. To bring a phone back in line with the sheet, the
 *     user runs the in-app "Sab data reset karein" action.
 *   - Per-sheet cap of PULL_LIMIT rows. If we hit the cap, the next
 *     poll with the new cursor will pick up where we left off.
 *
 * NOTE on locking: this handler takes the script lock ONLY around the
 * sheet load (loadSheetContext_). That is not a "write lock" in the
 * spirit of the requirement — we hold it only long enough to (a) safely
 * run the v1→v2 column migration if needed, and (b) snapshot a
 * consistent in-memory view of the sheet. Filtering, sorting and DTO
 * projection happen AFTER the lock is released, in pure JS, so a pull
 * never blocks concurrent writes for more than the snapshot duration
 * (a few hundred ms even for thousands of rows). Without this lock the
 * one-shot migration could race with a concurrent writer and produce
 * partially-written headers; with it, the migration is safely
 * serialised against writers that also use withLock_.
 */
function handlePullChanges_(payload) {
  const rawCursor = (payload && payload.sinceCursor !== undefined && payload.sinceCursor !== null)
    ? String(payload.sinceCursor)
    : '';
  const cursor = parseCursor_(rawCursor); // '' means "send everything"

  let itemsCtx, entriesCtx, billsCtx;
  withLock_(function () {
    itemsCtx   = loadSheetContext_(ITEMS_SHEET, ITEMS_HEADERS);
    entriesCtx = loadSheetContext_(ENTRIES_SHEET, ENTRIES_HEADERS);
    billsCtx   = loadSheetContext_(BILLS_SHEET, BILLS_HEADERS);
  });

  // Filter / sort / project happen OUTSIDE the lock — they only touch
  // the in-memory snapshot and never the spreadsheet.
  //
  // collectChangedRows_ returns BOTH the live (non-tombstone) DTOs AND
  // the max serverUpdatedAt observed across ALL changed rows (tombstones
  // included). We need the latter so the cursor still advances past
  // soft-delete events on the server — otherwise the same tombstone
  // would be re-evaluated on every subsequent pull.
  const itemsResult   = collectChangedRows_(itemsCtx,   ITEMS_HEADERS,   cursor, PULL_LIMIT, rowToItemDto_);
  const entriesResult = collectChangedRows_(entriesCtx, ENTRIES_HEADERS, cursor, PULL_LIMIT, rowToEntryDto_);
  const billsResult   = collectChangedRows_(billsCtx,   BILLS_HEADERS,   cursor, PULL_LIMIT, rowToBillDto_);

  // New cursor = max serverUpdatedAt seen across ALL sheets, including
  // tombstone rows that were filtered out of the response. If nothing
  // changed at all we hand the client back the cursor it sent us —
  // never null — so it can keep polling without re-loading everything.
  let newCursor = cursor || '';
  if (itemsResult.maxServerUpdatedAt   > newCursor) newCursor = itemsResult.maxServerUpdatedAt;
  if (entriesResult.maxServerUpdatedAt > newCursor) newCursor = entriesResult.maxServerUpdatedAt;
  if (billsResult.maxServerUpdatedAt   > newCursor) newCursor = billsResult.maxServerUpdatedAt;

  return jsonResponse_({
    ok: true,
    action: 'pullChanges',
    items: itemsResult.dtos,
    entries: entriesResult.dtos,
    bills: billsResult.dtos,
    cursor: newCursor,
    schemaVersion: SCHEMA_VERSION,
    time: nowIso_()
  });
}


/**
 * Crash log appender. Accepts up to CRASHES_LIMIT crash events and
 * writes them to the `Crashes` tab. Idempotent on `crashId` — if a
 * row with the same crashId already exists we leave it alone (a
 * re-upload from a phone that thought the previous call failed is
 * a no-op, so the user never sees duplicate crash rows).
 *
 * Each crash row carries the full stack trace (already truncated to
 * 8 KB on the phone) so the shop owner can show the sheet to a
 * developer without exporting anything.
 */
function handleLogCrashes_(payload) {
  const crashes = Array.isArray(payload && payload.crashes) ? payload.crashes : [];

  if (crashes.length > CRASHES_LIMIT) {
    return jsonResponse_({
      ok: false,
      action: 'logCrashes',
      processed: 0,
      errors: [{
        index: -1,
        key: '',
        message: 'Too many crashes in one logCrashes (' + crashes.length + '). Max allowed is ' + CRASHES_LIMIT + '.'
      }],
      time: new Date().toISOString()
    });
  }

  const errors = [];
  let processed = 0;

  withLock_(function () {
    const ctx = loadSheetContext_(CRASHES_SHEET, CRASHES_HEADERS);
    for (let i = 0; i < crashes.length; i++) {
      const r = upsertCrashInMemory_(ctx, crashes[i], i);
      if (r.error) errors.push(r.error); else processed++;
    }
    flushSheet_(ctx);
  });

  return jsonResponse_({
    ok: errors.length === 0,
    action: 'logCrashes',
    processed: processed,
    errors: errors.length ? errors : undefined,
    time: new Date().toISOString()
  });
}


/* =========================================================================
 *  IN-MEMORY UPSERT / DELETE LOGIC
 *
 *  These functions never touch the sheet directly. They mutate the
 *  in-memory `ctx.values` 2D array, then `flushSheet_` writes it back.
 * ========================================================================= */

/**
 * Upsert an item by `code`. Last-write-wins on updatedAt.
 * Each call is wrapped in try/catch so bulk batches keep going.
 */
function upsertItemInMemory_(ctx, payload, index) {
  try {
    const code = requireString_(payload && payload.code, 'code');
    const name = String((payload && payload.name) || '');
    const unit = String((payload && payload.unit) || '');
    const updatedAt = requireString_(payload && payload.updatedAt, 'updatedAt');
    // Server-side cursor stamp. ALWAYS overwritten on a write — never
    // taken from the client payload. UTC `Z` so ISO strings sort
    // lexicographically the same way they sort by time.
    const serverUpdatedAt = nowIso_();

    const existingRow = ctx.keyMap.get(code); // 0-based row index inside ctx.values (excluding header), or undefined

    const newRow = buildRowFromHeaders_(ctx.headers, {
      code: code,
      name: name,
      unit: unit,
      updatedAt: updatedAt,
      deleted: 'FALSE',
      serverUpdatedAt: serverUpdatedAt
    });

    if (existingRow === undefined) {
      // Append
      ctx.values.push(newRow);
      ctx.keyMap.set(code, ctx.values.length - 1);
      ctx.dirty = true;
    } else {
      // Compare updatedAt — keep the newer one. Equal timestamps still write
      // (so a re-send with deleted=FALSE will resurrect a soft-deleted row).
      // toIsoTimestamp_ defends against Sheets having auto-converted the
      // stored ISO string into a Date object (which would otherwise lex-
      // compare as a locale string and silently break this branch).
      const stored = ctx.values[existingRow];
      const storedUpdatedAt = toIsoTimestamp_(stored[ctx.colIndex.updatedAt]);
      if (updatedAt >= storedUpdatedAt) {
        ctx.values[existingRow] = newRow;
        ctx.dirty = true;
      }
      // else: incoming is older — silently ignore (still counts as processed).
      // We deliberately do NOT bump serverUpdatedAt in the ignore-branch:
      // nothing actually changed, so the pull cursor should not move.
    }

    return { ok: true };
  } catch (err) {
    return {
      error: {
        index: index,
        key: (payload && payload.code) ? String(payload.code) : '',
        message: err && err.message ? err.message : String(err)
      }
    };
  }
}

/**
 * Upsert a purchase entry by `entryId`. Last-write-wins on updatedAt.
 */
function upsertEntryInMemory_(ctx, payload, index) {
  try {
    const entryId   = requireString_(payload && payload.entryId, 'entryId');
    const itemCode  = requireString_(payload && payload.itemCode, 'itemCode');
    const date      = requireString_(payload && payload.date, 'date');
    const pricePerUnit = requireNumber_(payload && payload.pricePerUnit, 'pricePerUnit');
    // Quantity is free-form text (e.g. "5 kg", "1 packet") — keep
    // strings verbatim. Legacy numeric payloads from older clients
    // still arrive as JSON numbers; coerce them to strings so the
    // sheet column stays uniformly typed.
    const quantity  = (payload && payload.quantity !== undefined && payload.quantity !== null && payload.quantity !== '')
                        ? String(payload.quantity) : '';
    const supplier  = String((payload && payload.supplier) || '');
    const notes     = String((payload && payload.notes) || '');
    const updatedAt = requireString_(payload && payload.updatedAt, 'updatedAt');
    const serverUpdatedAt = nowIso_();

    const existingRow = ctx.keyMap.get(entryId);

    const newRow = buildRowFromHeaders_(ctx.headers, {
      entryId: entryId,
      itemCode: itemCode,
      date: date,
      pricePerUnit: pricePerUnit,
      quantity: quantity,
      supplier: supplier,
      notes: notes,
      updatedAt: updatedAt,
      deleted: 'FALSE',
      serverUpdatedAt: serverUpdatedAt
    });

    if (existingRow === undefined) {
      ctx.values.push(newRow);
      ctx.keyMap.set(entryId, ctx.values.length - 1);
      ctx.dirty = true;
    } else {
      const stored = ctx.values[existingRow];
      // See note in upsertItemInMemory_: stored cell may be a Date.
      const storedUpdatedAt = toIsoTimestamp_(stored[ctx.colIndex.updatedAt]);
      if (updatedAt >= storedUpdatedAt) {
        ctx.values[existingRow] = newRow;
        ctx.dirty = true;
      }
      // Older incoming write: skip; do not bump serverUpdatedAt.
    }

    return { ok: true };
  } catch (err) {
    return {
      error: {
        index: index,
        key: (payload && payload.entryId) ? String(payload.entryId) : '',
        message: err && err.message ? err.message : String(err)
      }
    };
  }
}

/**
 * Upsert a bill by `id`. Last-write-wins on updatedAt.
 *
 * `totalAmount` is optional — the user may save a bill before they've
 * tallied the final number. Empty / null payload values go to the sheet
 * as '' (not 0!) so a "no amount yet" bill round-trips as `null` and
 * does not get confused with a genuine zero-rupee bill on the way back.
 * `supplier`, `notes`, `imageFileIds` are free-text strings (empty if
 * absent). `imageFileIds` is a CSV of Drive file IDs the phone built
 * up via uploadBillImage; we never parse it server-side, just
 * preserve it verbatim.
 */
function upsertBillInMemory_(ctx, payload, index) {
  try {
    const id        = requireString_(payload && payload.id,        'id');
    const date      = requireString_(payload && payload.date,      'date');
    const updatedAt = requireString_(payload && payload.updatedAt, 'updatedAt');

    // Nullable total. Treat '', null, undefined identically as "no amount
    // entered yet" — store as empty string so rowToBillDto_ emits null.
    const rawTotal = payload && payload.totalAmount;
    let totalAmount = '';
    if (rawTotal !== undefined && rawTotal !== null && rawTotal !== '') {
      const n = Number(rawTotal);
      if (isNaN(n)) throw new Error('Field is not a number: totalAmount');
      totalAmount = n;
    }

    const supplier     = String((payload && payload.supplier)     || '');
    const notes        = String((payload && payload.notes)        || '');
    const imageFileIds = String((payload && payload.imageFileIds) || '');
    const serverUpdatedAt = nowIso_();

    // Derived clickable column. `setValues()` interprets a leading `=`
    // as a formula on write, so we just drop the formula string into
    // the row's `imageLink` slot via the normal buildRowFromHeaders_
    // path — no separate setFormula call required. Recomputed on every
    // upsert so a change to imageFileIds (image added or removed)
    // propagates to the link without any extra plumbing.
    const imageLink = buildBillImageLinkFormula_(imageFileIds);

    const existingRow = ctx.keyMap.get(id);

    const newRow = buildRowFromHeaders_(ctx.headers, {
      id: id,
      date: date,
      supplier: supplier,
      totalAmount: totalAmount,
      notes: notes,
      imageFileIds: imageFileIds,
      updatedAt: updatedAt,
      deleted: 'FALSE',
      serverUpdatedAt: serverUpdatedAt,
      imageLink: imageLink
    });

    if (existingRow === undefined) {
      ctx.values.push(newRow);
      ctx.keyMap.set(id, ctx.values.length - 1);
      ctx.dirty = true;
    } else {
      const stored = ctx.values[existingRow];
      // See note in upsertItemInMemory_: stored cell may be a Date.
      const storedUpdatedAt = toIsoTimestamp_(stored[ctx.colIndex.updatedAt]);
      if (updatedAt >= storedUpdatedAt) {
        ctx.values[existingRow] = newRow;
        ctx.dirty = true;
      }
      // Older incoming write: skip; do not bump serverUpdatedAt.
    }

    return { ok: true };
  } catch (err) {
    return {
      error: {
        index: index,
        key: (payload && payload.id) ? String(payload.id) : '',
        message: err && err.message ? err.message : String(err)
      }
    };
  }
}

/**
 * Soft delete: set deleted=TRUE and bump updatedAt. If the row does not
 * exist yet (phone deleted something we never saw), we insert a tombstone.
 *
 * @param {Object} ctx       Sheet context.
 * @param {string} keyName   'code' (Items), 'entryId' (PurchaseEntries), or 'id' (Bills).
 * @param {Object} payload   { <keyName>, updatedAt }.
 * @param {number} index     Original index in the bulk request, for error reporting.
 */
function softDeleteInMemory_(ctx, keyName, payload, index) {
  try {
    const key = requireString_(payload && payload[keyName], keyName);
    const updatedAt = requireString_(payload && payload.updatedAt, 'updatedAt');
    const serverUpdatedAt = nowIso_();

    const existingRow = ctx.keyMap.get(key);

    if (existingRow === undefined) {
      // Insert a tombstone row so future syncs see the delete.
      const seed = {};
      ctx.headers.forEach(function (h) { seed[h] = ''; });
      seed[keyName]         = key;
      seed.updatedAt        = updatedAt;
      seed.deleted          = 'TRUE';
      seed.serverUpdatedAt  = serverUpdatedAt;
      const tombstone = buildRowFromHeaders_(ctx.headers, seed);
      ctx.values.push(tombstone);
      ctx.keyMap.set(key, ctx.values.length - 1);
      ctx.dirty = true;
    } else {
      const stored = ctx.values[existingRow];
      // See note in upsertItemInMemory_: stored cell may be a Date.
      const storedUpdatedAt = toIsoTimestamp_(stored[ctx.colIndex.updatedAt]);
      if (updatedAt >= storedUpdatedAt) {
        stored[ctx.colIndex.deleted]         = 'TRUE';
        stored[ctx.colIndex.updatedAt]       = updatedAt;
        stored[ctx.colIndex.serverUpdatedAt] = serverUpdatedAt;
        ctx.dirty = true;
      }
      // Older incoming delete: skip; do not bump serverUpdatedAt.
    }

    return { ok: true };
  } catch (err) {
    return {
      error: {
        index: index,
        key: (payload && payload[keyName]) ? String(payload[keyName]) : '',
        message: err && err.message ? err.message : String(err)
      }
    };
  }
}


/**
 * Append a crash record. Dedup is by `crashId` (first column).
 *
 * Unlike items / entries we do NOT overwrite on conflict — a crash
 * record is immutable history. If the same crashId is already on
 * the sheet the call is a no-op (still counts as processed so the
 * phone clears its local copy).
 */
function upsertCrashInMemory_(ctx, payload, index) {
  try {
    const crashId = requireString_(payload && payload.crashId, 'crashId');

    if (ctx.keyMap.has(crashId)) {
      // Already logged — idempotent no-op. We still report success
      // so the phone removes the line from its local crashes.log.
      return { ok: true };
    }

    const newRow = buildRowFromHeaders_(ctx.headers, {
      crashId:        crashId,
      timestamp:      String((payload && payload.timestamp) || ''),
      appVersion:     String((payload && payload.appVersion) || ''),
      appVersionCode: (payload && payload.appVersionCode !== undefined && payload.appVersionCode !== null && payload.appVersionCode !== '')
                        ? Number(payload.appVersionCode) : '',
      androidVersion: String((payload && payload.androidVersion) || ''),
      deviceModel:    String((payload && payload.deviceModel) || ''),
      threadName:     String((payload && payload.threadName) || ''),
      message:        String((payload && payload.message) || ''),
      stackTrace:     String((payload && payload.stackTrace) || '')
    });

    ctx.values.push(newRow);
    ctx.keyMap.set(crashId, ctx.values.length - 1);
    ctx.dirty = true;

    return { ok: true };
  } catch (err) {
    return {
      error: {
        index: index,
        key: (payload && payload.crashId) ? String(payload.crashId) : '',
        message: err && err.message ? err.message : String(err)
      }
    };
  }
}


/* =========================================================================
 *  SHEET I/O HELPERS
 * ========================================================================= */

/**
 * Read a sheet into memory, build a header map and a key->rowIndex map.
 * If the sheet does not exist, create it with the canonical header row.
 *
 * Returned context shape:
 *   {
 *     sheet:    Sheet,
 *     headers:  string[],          // canonical header order from the sheet
 *     colIndex: { [name]: number}, // header name -> 0-based column index
 *     values:   any[][],           // data rows only (no header), mutable
 *     keyMap:   Map<string,number>,// keyValue -> index into `values`
 *     dirty:    boolean            // set true when we change `values`
 *   }
 */
function loadSheetContext_(sheetName, expectedHeaders) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(sheetName);

  // Auto-create the tab on first call, with the canonical header row.
  if (!sheet) {
    sheet = ss.insertSheet(sheetName);
    sheet.getRange(1, 1, 1, expectedHeaders.length).setValues([expectedHeaders]);
    sheet.setFrozenRows(1);
  }

  // Make sure there is a header row. If the sheet is empty, write headers.
  const lastRow = sheet.getLastRow();

  if (lastRow === 0) {
    sheet.getRange(1, 1, 1, expectedHeaders.length).setValues([expectedHeaders]);
    sheet.setFrozenRows(1);
  }

  // Read everything in one go — single getValues call per sheet per request.
  // We deliberately read at least `expectedHeaders.length` columns so a v1
  // sheet (missing the trailing `serverUpdatedAt` column) still gives us a
  // padded array we can write back into.
  const physicalLastRow = Math.max(sheet.getLastRow(), 1);
  const physicalLastCol = Math.max(sheet.getLastColumn(), expectedHeaders.length);
  const all = sheet.getRange(1, 1, physicalLastRow, physicalLastCol).getValues();

  let headerRow = all[0].map(function (h) { return String(h || '').trim(); });

  // ---- v1 -> v2 migration -------------------------------------------------
  // Detect the case where the sheet has the old schema (everything except
  // the trailing `serverUpdatedAt` column). We do NOT clear the header row
  // — that would risk wiping the data column underneath. Instead we just
  // append the missing column and backfill it from `updatedAt`. This is
  // the same shape as `expectedHeaders` minus the last entry.
  const newColumns = [];
  for (let i = 0; i < expectedHeaders.length; i++) {
    if (headerRow[i] !== expectedHeaders[i]) {
      // Column at position i is wrong/missing. If it is purely a tail-append
      // (everything before matches), we treat it as a migration; otherwise
      // we fall through to the existing "rewrite header" safety net below.
      newColumns.push({ index: i, name: expectedHeaders[i] });
    }
  }
  // If every mismatched column is at the end (i.e. they form a contiguous
  // tail) AND the prefix matches, it's a clean additive migration.
  let isCleanTailAppend = newColumns.length > 0;
  for (let i = 0; i < newColumns.length; i++) {
    if (newColumns[i].index !== expectedHeaders.length - newColumns.length + i) {
      isCleanTailAppend = false;
      break;
    }
  }
  // Also require that the prefix (everything before the first new column)
  // matches expectedHeaders so we don't accidentally migrate a corrupted
  // sheet.
  if (isCleanTailAppend) {
    const firstNewIdx = newColumns[0].index;
    for (let i = 0; i < firstNewIdx; i++) {
      if (headerRow[i] !== expectedHeaders[i]) { isCleanTailAppend = false; break; }
    }
  }

  if (isCleanTailAppend) {
    // Write the new header cells in-place (don't touch the prefix).
    const startCol = newColumns[0].index + 1; // 1-based
    const newHeaderNames = newColumns.map(function (c) { return c.name; });
    sheet.getRange(1, startCol, 1, newHeaderNames.length).setValues([newHeaderNames]);

    // Backfill the new column(s) for every existing data row.
    // For `serverUpdatedAt` specifically, the best fallback for legacy
    // data is the row's existing `updatedAt` — that lets the cursor work
    // sensibly even before a row is ever re-touched.
    const updatedAtIdx = expectedHeaders.indexOf('updatedAt');
    for (let r = 1; r < all.length; r++) {
      // Pad the in-memory row out to expectedHeaders.length first.
      while (all[r].length < expectedHeaders.length) all[r].push('');
      newColumns.forEach(function (c) {
        if (c.name === 'serverUpdatedAt') {
          // toIsoTimestamp_ in case Sheets converted the legacy
          // `updatedAt` cell into a Date — backfilling the locale
          // string into serverUpdatedAt would poison the cursor.
          const existingUpdatedAt = (updatedAtIdx >= 0)
            ? toIsoTimestamp_(all[r][updatedAtIdx])
            : '';
          all[r][c.index] = existingUpdatedAt || nowIso_();
        } else if (c.name === 'imageLink') {
          // Backfill from the existing imageFileIds column (also
          // detected by index lookup) so previously-uploaded bills
          // become clickable without waiting for a re-upsert.
          const fileIdsIdx = expectedHeaders.indexOf('imageFileIds');
          const csv = (fileIdsIdx >= 0) ? toPlainText_(all[r][fileIdsIdx]) : '';
          all[r][c.index] = buildBillImageLinkFormula_(csv);
        } else {
          all[r][c.index] = '';
        }
      });
    }

    // Push the backfilled values back to the sheet so the migration
    // sticks even if no upserts happen this call.
    if (all.length > 1) {
      const dataRows = [];
      for (let r = 1; r < all.length; r++) {
        dataRows.push(all[r].slice(0, expectedHeaders.length));
      }
      sheet.getRange(2, 1, dataRows.length, expectedHeaders.length).setValues(dataRows);
    }

    // Refresh the in-memory header view to the canonical layout.
    headerRow = expectedHeaders.slice();
    all[0] = headerRow.slice();
  } else {
    // Either headers already match, or they are corrupted in a way we
    // can't migrate safely. Fall back to the original "rewrite header
    // row" behaviour, which is the same blunt-force fix the script has
    // always had for non-tail-append corruption.
    let headersOk = headerRow.length >= expectedHeaders.length;
    if (headersOk) {
      for (let i = 0; i < expectedHeaders.length; i++) {
        if (headerRow[i] !== expectedHeaders[i]) { headersOk = false; break; }
      }
    }
    if (!headersOk) {
      sheet.getRange(1, 1, 1, expectedHeaders.length).setValues([expectedHeaders]);
      sheet.setFrozenRows(1);
      headerRow = expectedHeaders.slice();
      if (all.length > 0) all[0] = headerRow.slice();
    }
  }

  const headers = expectedHeaders.slice();
  const colIndex = {};
  for (let i = 0; i < headers.length; i++) colIndex[headers[i]] = i;

  // Force plain-text (`@`) number format on the columns whose values
  // must round-trip as strings. This stops Sheets from auto-converting
  // ISO 8601 / YYYY-MM-DD strings into Date objects on subsequent
  // reads. It's a one-shot per-session call and idempotent — re-applying
  // `@` to an already-`@`-formatted column is a no-op as far as the
  // sheet is concerned. Existing rows whose cells already got auto-
  // converted to Date stay as Date in the cell; the read-side helpers
  // (toIsoTimestamp_ / toLocalDate_) handle that case.
  const textFormatCols = TEXT_FORMAT_COLS[sheetName];
  if (textFormatCols) {
    const dataRowCount = Math.max(sheet.getMaxRows() - 1, 1);
    textFormatCols.forEach(function (name) {
      const idx = headers.indexOf(name);
      if (idx >= 0) {
        sheet.getRange(2, idx + 1, dataRowCount, 1).setNumberFormat('@');
      }
    });
  }

  // Data rows (everything below the header).
  const values = [];
  for (let r = 1; r < all.length; r++) {
    // Pad / trim row to header length so downstream code is simple.
    const row = all[r].slice(0, headers.length);
    while (row.length < headers.length) row.push('');
    values.push(row);
  }

  // Bills' `imageLink` is a derived HYPERLINK formula. `getValues()`
  // returns the EVALUATED text of formula cells (e.g. "View image"),
  // not the formula itself — so if we let those displayed strings
  // round-trip through flushSheet_, the next setValues() call would
  // overwrite the formula with plain text and the link would die.
  // Recompute the formula from the row's `imageFileIds` cell on every
  // load so the in-memory snapshot always carries a fresh formula.
  // Cheap (string concat per row), idempotent, and self-healing — if
  // a user manually clears the cell, the next sync restores it.
  if (sheetName === BILLS_SHEET) {
    const fileIdsIdx = colIndex.imageFileIds;
    const linkIdx    = colIndex.imageLink;
    if (typeof fileIdsIdx === 'number' && typeof linkIdx === 'number') {
      for (let r = 0; r < values.length; r++) {
        const csv = toPlainText_(values[r][fileIdsIdx]);
        values[r][linkIdx] = buildBillImageLinkFormula_(csv);
      }
    }
  }

  // Build key -> rowIndex map. Key column is always the FIRST header.
  // toPlainText_ defends against the (rare) case where a user typed a
  // date-shaped value as a code/entryId and Sheets auto-converted it
  // to a Date — without this, `String(dateObj)` would be the locale
  // string and the keyMap entry would never line up with the same key
  // sent by the phone.
  const keyCol = colIndex[headers[0]];
  const keyMap = new Map();
  for (let r = 0; r < values.length; r++) {
    const k = toPlainText_(values[r][keyCol]);
    if (k) keyMap.set(k, r);
  }

  return {
    sheet: sheet,
    headers: headers,
    colIndex: colIndex,
    values: values,
    keyMap: keyMap,
    dirty: false
  };
}

/**
 * Write the in-memory data back to the sheet in a single setValues call.
 * Skips the write entirely if nothing changed.
 */
function flushSheet_(ctx) {
  if (!ctx.dirty) return;

  const sheet = ctx.sheet;
  const headers = ctx.headers;
  const values = ctx.values;

  // Clear any rows past header (in case rows shrank — currently we never
  // remove rows, but this keeps things safe if logic changes later).
  const existingDataRows = sheet.getLastRow() - 1;
  if (existingDataRows > 0) {
    sheet.getRange(2, 1, existingDataRows, headers.length).clearContent();
  }

  if (values.length > 0) {
    sheet.getRange(2, 1, values.length, headers.length).setValues(values);
  }
}


/* =========================================================================
 *  SMALL UTILITIES
 * ========================================================================= */

/**
 * Run `fn` while holding the script lock. The lock prevents two phones
 * from racing to write the sheet at the same time. We wait up to 30s
 * (LOCK_TIMEOUT_MS) before giving up — that is generous for a single sync.
 */
function withLock_(fn) {
  const lock = LockService.getScriptLock();
  lock.waitLock(LOCK_TIMEOUT_MS);
  try {
    fn();
  } finally {
    try { lock.releaseLock(); } catch (e) { /* ignore */ }
  }
}

/**
 * Build a sheet row (array) by reading values from `obj` in header order.
 * Anything missing becomes an empty string so we never write `undefined`.
 */
function buildRowFromHeaders_(headers, obj) {
  const row = new Array(headers.length);
  for (let i = 0; i < headers.length; i++) {
    const v = obj[headers[i]];
    row[i] = (v === undefined || v === null) ? '' : v;
  }
  return row;
}

function requireString_(v, fieldName) {
  if (v === undefined || v === null || String(v).length === 0) {
    throw new Error('Missing required field: ' + fieldName);
  }
  return String(v);
}

function requireNumber_(v, fieldName) {
  if (v === undefined || v === null || v === '') {
    throw new Error('Missing required field: ' + fieldName);
  }
  const n = Number(v);
  if (isNaN(n)) {
    throw new Error('Field is not a number: ' + fieldName);
  }
  return n;
}

/**
 * Always return JSON. Apps Script does not support setting CORS headers
 * directly on a web-app deployment, but the Android app calls this from
 * a native HTTP client so CORS is irrelevant here.
 */
function jsonResponse_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Single source of truth for the server "now" stamp used by both
 * `serverUpdatedAt` (per-row) and the `time` field on responses.
 * UTC `Z` form so lexicographic compare equals time compare.
 */
function nowIso_() {
  return new Date().toISOString();
}

/**
 * Sanitise the incoming pull cursor. Anything we can't parse becomes
 * '' which means "send everything". We accept both an ISO 8601 string
 * (the normal case) and an empty/missing value. We only reject values
 * that JS's Date can't parse, because then the lexicographic compare
 * would silently misbehave.
 */
function parseCursor_(raw) {
  if (raw === '' || raw === null || raw === undefined) return '';
  const s = String(raw);
  const t = Date.parse(s);
  if (isNaN(t)) return '';   // unparseable: full re-pull
  return s;                  // keep the original string for lex-compare
}

/**
 * Pull-only helper. Walks `ctx.values`, filters rows whose
 * `serverUpdatedAt > cursor`, sorts ascending, caps at `limit`,
 * and projects each row through `toDto`.
 *
 * Soft-deleted rows (tombstones) are dropped from the response BUT still
 * contribute to `maxServerUpdatedAt`, so the cursor advances past delete
 * events on the server. Without that, a tombstone would re-show up on
 * every pull forever (we'd compare-and-skip it again and again, and the
 * cursor would never move past it). The client explicitly does not want
 * tombstones in the payload — see `handlePullChanges_` for the rationale.
 *
 * Returns:
 *   {
 *     dtos:                Object[],   // live (non-tombstone) rows, projected
 *     maxServerUpdatedAt:  string      // max serverUpdatedAt across ALL changed
 *                                      // rows including tombstones, or '' if none
 *   }
 *
 * Note: the Crashes sheet has no `deleted` column. This helper is only
 * called for Items and PurchaseEntries; Crashes uses a different
 * (write-only) path entirely. We still defensively guard against a
 * missing `deleted` column so a hand-edit on the sheet can't crash a
 * pull — `parseBool_(undefined)` returns false (a v1 sheet pre-migration
 * with an empty `deleted` cell is treated as "not deleted", which is
 * the correct fallback semantic).
 */
function collectChangedRows_(ctx, expectedHeaders, cursor, limit, toDto) {
  const sIdx = ctx.colIndex.serverUpdatedAt;
  const dIdx = ctx.colIndex.deleted; // may be undefined on a sheet without the column
  const candidates = [];
  let maxServerUpdatedAt = '';

  for (let r = 0; r < ctx.values.length; r++) {
    const row = ctx.values[r];
    // toIsoTimestamp_ here (not String(... || '')) so that if the cell
    // came back as a Date object, the lex-compare against the cursor
    // (which is an ISO string) is apples-to-apples. Without this the
    // cursor compare silently breaks the moment Sheets auto-converts
    // a serverUpdatedAt cell to a Date.
    const sUpdated = toIsoTimestamp_(row[sIdx]);
    // Empty serverUpdatedAt should not happen post-migration, but if
    // it does we treat it as "older than any cursor" so the client
    // doesn't get an undefined-shaped row.
    if (!sUpdated) continue;
    if (cursor && sUpdated <= cursor) continue;

    // This row is "changed" relative to the cursor. Bump the cursor
    // ceiling regardless of whether it's a tombstone — that's how we
    // make sure the cursor advances past delete events too.
    if (sUpdated > maxServerUpdatedAt) maxServerUpdatedAt = sUpdated;

    // Drop tombstones from the response itself. Done AFTER the cursor
    // ceiling update so we still acknowledge the delete on the cursor.
    const isDeleted = (typeof dIdx === 'number') ? parseBool_(row[dIdx]) : false;
    if (isDeleted) continue;

    candidates.push(row);
  }

  // Ascending by serverUpdatedAt, ties stable.
  candidates.sort(function (a, b) {
    const av = toIsoTimestamp_(a[sIdx]);
    const bv = toIsoTimestamp_(b[sIdx]);
    if (av < bv) return -1;
    if (av > bv) return 1;
    return 0;
  });

  // Apply the per-sheet cap. Slice + map is fine at 1000 rows.
  const capped = (candidates.length > limit) ? candidates.slice(0, limit) : candidates;
  const dtos = capped.map(function (row) { return toDto(row, ctx.colIndex); });
  return { dtos: dtos, maxServerUpdatedAt: maxServerUpdatedAt };
}

/** Sheet row → Items DTO sent to the client. */
function rowToItemDto_(row, colIndex) {
  return {
    code:            toPlainText_(row[colIndex.code]),
    name:            toPlainText_(row[colIndex.name]),
    unit:            toPlainText_(row[colIndex.unit]),
    updatedAt:       toIsoTimestamp_(row[colIndex.updatedAt]),
    deleted:         parseBool_(row[colIndex.deleted]),
    serverUpdatedAt: toIsoTimestamp_(row[colIndex.serverUpdatedAt])
  };
}

/** Sheet row → Bills DTO sent to the client. */
function rowToBillDto_(row, colIndex) {
  // totalAmount is nullable. An empty cell means "user hasn't entered
  // an amount yet" — meaningfully different from a zero-rupee bill, so
  // we emit `null` (not 0) when the cell is empty.
  const rawTotal = row[colIndex.totalAmount];
  const totalAmount = (rawTotal === '' || rawTotal === null || rawTotal === undefined)
    ? null
    : Number(rawTotal);

  return {
    id:              toPlainText_(row[colIndex.id]),
    date:            toLocalDate_(row[colIndex.date]),
    supplier:        toPlainText_(row[colIndex.supplier]),
    totalAmount:     totalAmount,
    notes:           toPlainText_(row[colIndex.notes]),
    imageFileIds:    toPlainText_(row[colIndex.imageFileIds]),
    updatedAt:       toIsoTimestamp_(row[colIndex.updatedAt]),
    deleted:         parseBool_(row[colIndex.deleted]),
    serverUpdatedAt: toIsoTimestamp_(row[colIndex.serverUpdatedAt])
  };
}

/** Sheet row → PurchaseEntries DTO sent to the client. */
function rowToEntryDto_(row, colIndex) {
  // Quantity is free-form text on the wire. The cell may still hold
  // a numeric value left over from older writes — coerce to a plain
  // string in that case so clients always see a String|null.
  const rawQty = row[colIndex.quantity];
  const quantity = (rawQty === '' || rawQty === null || rawQty === undefined)
    ? null
    : String(rawQty);

  const rawPrice = row[colIndex.pricePerUnit];
  const pricePerUnit = (rawPrice === '' || rawPrice === null || rawPrice === undefined)
    ? 0
    : Number(rawPrice);

  return {
    entryId:         toPlainText_(row[colIndex.entryId]),
    itemCode:        toPlainText_(row[colIndex.itemCode]),
    date:            toLocalDate_(row[colIndex.date]),
    pricePerUnit:    pricePerUnit,
    quantity:        quantity,
    supplier:        toPlainText_(row[colIndex.supplier]),
    notes:           toPlainText_(row[colIndex.notes]),
    updatedAt:       toIsoTimestamp_(row[colIndex.updatedAt]),
    deleted:         parseBool_(row[colIndex.deleted]),
    serverUpdatedAt: toIsoTimestamp_(row[colIndex.serverUpdatedAt])
  };
}

/* =========================================================================
 *  CELL VALUE NORMALIZATION
 *
 *  Google Sheets silently coerces strings that look like dates (ISO 8601
 *  timestamps, `YYYY-MM-DD`, even partial forms like `5 Jan`) into native
 *  Date objects when stored in a cell. `getValues()` then returns those
 *  cells as `Date` instances, and `String(d)` calls Date.prototype.toString
 *  which yields a locale-formatted string like:
 *    "Tue May 05 2026 00:00:00 GMT+0530 (India Standard Time)"
 *
 *  The Android client expects either ISO 8601 (for `updatedAt` /
 *  `serverUpdatedAt`) or `YYYY-MM-DD` (for purchase `date`), so we must
 *  normalize before serializing the row to JSON. These helpers are the
 *  single choke point for that conversion.
 * ========================================================================= */

/**
 * Normalize a cell that should round-trip as an ISO 8601 timestamp
 * (`updatedAt`, `serverUpdatedAt`). For Date objects we emit
 * `YYYY-MM-DDTHH:mm:ss.sssZ` via `Date.toISOString()`; everything else
 * is stringified and trimmed. Empty / null / undefined → `''`.
 */
function toIsoTimestamp_(v) {
  if (v instanceof Date) return v.toISOString();
  return String(v || '').trim();
}

/**
 * Normalize a cell that should round-trip as a `YYYY-MM-DD` local date
 * (the `date` column on PurchaseEntries). For Date objects we read the
 * sheet-local Y/M/D directly — converting to UTC and slicing risks a
 * one-day shift in IST when the cell is midnight local. Strings pass
 * through trimmed.
 */
function toLocalDate_(v) {
  if (v instanceof Date) {
    const yyyy = v.getFullYear();
    const mm   = String(v.getMonth() + 1).padStart(2, '0');
    const dd   = String(v.getDate()).padStart(2, '0');
    return yyyy + '-' + mm + '-' + dd;
  }
  return String(v || '').trim();
}

/**
 * Normalize a cell that is conceptually free-text (item `name`, `code`,
 * `unit`, `supplier`, `notes`, etc). Defensive: even non-date columns
 * can come back as a Date if the user typed something date-like as the
 * value (e.g. "5 Jan" → Sheets converts to a Date). For that case we
 * fall back to the ISO form so at least the value is not the long
 * locale string. Strings pass through.
 */
function toPlainText_(v) {
  if (v instanceof Date) return v.toISOString();
  return String(v || '');
}

/**
 * Tolerant boolean parse for the `deleted` column. The script writes
 * the strings `'TRUE'` / `'FALSE'` but a user editing the sheet by
 * hand could end up with the spreadsheet boolean (`true` / `false`)
 * or even `1` / `0`. Be lenient on read.
 */
function parseBool_(v) {
  if (v === true) return true;
  if (v === false) return false;
  if (v === 1 || v === '1') return true;
  if (v === 0 || v === '0') return false;
  const s = String(v || '').trim().toUpperCase();
  return s === 'TRUE';
}


/* =========================================================================
 *  DRIVE HELPERS  (bill image attachments)
 *
 *  Bill images live as standalone files under My Drive →
 *  "SanthaliaStore Bills". Only the Drive file ID is stored on the Bills
 *  row (CSV in `imageFileIds`). Deletes are recoverable — the file is
 *  moved to a `_deleted/` sub-folder instead of being trashed, so the
 *  shop owner can still get a photo back from the Drive UI.
 * ========================================================================= */

const BILLS_FOLDER_NAME      = 'SanthaliaStore Bills';
const BILLS_FOLDER_PROP      = 'bills_folder_id';   // ScriptProperties cache key
const BILLS_DELETED_SUBNAME  = '_deleted';

/**
 * ONE-OFF SETUP — RUN THIS FROM THE APPS SCRIPT EDITOR.
 *
 * 1. Open this script in the Apps Script editor.
 * 2. In the function dropdown at the top of the toolbar, pick
 *    `setupBillsDriveFolder`.
 * 3. Click the ▶ Run button.
 * 4. On first run Google shows a permission prompt: "This app
 *    wants access to your Google Drive". Tap Continue → choose
 *    your Google account → Allow.
 * 5. After it finishes, open the Execution log (View → Logs, or
 *    the bottom panel that pops up). You should see the folder
 *    URL — that's your bills bucket.
 *
 * What this does:
 *   - Forces the Drive OAuth prompt, which is the usual reason
 *     uploads silently fail right after a redeploy. The deployment
 *     uses the script owner's scopes, and they have to be granted
 *     once for the new DriveApp calls in this file.
 *   - Creates the "SanthaliaStore Bills" folder in your My Drive
 *     if it doesn't already exist, so the first phone upload
 *     doesn't race with the folder-create step.
 *
 * Re-running this is harmless and idempotent — the folder is
 * looked up by id (cached in ScriptProperties) before any create
 * attempt, and no duplicates are produced.
 */
function setupBillsDriveFolder() {
  const folder = getOrCreateBillsFolder_();
  const url = folder.getUrl();
  const id = folder.getId();
  Logger.log('Bills folder created / found.');
  Logger.log('  Name: ' + folder.getName());
  Logger.log('  ID:   ' + id);
  Logger.log('  URL:  ' + url);
  // Returning the URL makes the value pop up in the editor's
  // run-result toast so the user gets a visible confirmation
  // even before they open the log panel.
  return url;
}

/**
 * One-off repair: open "anyone with link, view-only" sharing on every
 * existing bill image already in the Drive folder.
 *
 * Why this exists: pre-this-change, `handleUploadBillImage_` created
 * Drive files without setting permissions, so they defaulted to
 * "only the script owner can view". Other phones in the household
 * — which fetch the image as an unauthenticated HTTP request — hit
 * a Drive login wall and the Bills detail screen rendered a
 * broken-image placeholder.
 *
 * New uploads from now on share themselves automatically. This
 * function walks the bills folder once to fix the backlog.
 *
 * How to run:
 *   1. Open Code.gs in the Apps Script editor.
 *   2. In the function dropdown, pick `repairBillImageSharing`.
 *   3. Click ▶ Run. Approve the Drive permission prompt if asked.
 *   4. Watch the Execution log — it prints a running count and a
 *      final summary line.
 *
 * Safe to re-run. setSharing on an already-shared file is a no-op
 * (sharing state is idempotent), and the sub-folder for
 * soft-deleted images is intentionally NOT touched — those files
 * are tombstoned, no point exposing them.
 *
 * Returns the count of files whose sharing was (re-)applied.
 */
function repairBillImageSharing() {
  const folder = getOrCreateBillsFolder_();
  const files = folder.getFiles();
  let ok = 0;
  let failed = 0;
  while (files.hasNext()) {
    const f = files.next();
    try {
      f.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
      ok++;
    } catch (e) {
      failed++;
      Logger.log('  setSharing failed for ' + f.getId() + ': ' +
                 (e && e.message ? e.message : String(e)));
    }
  }
  Logger.log('Bill image sharing repair done.');
  Logger.log('  Shared:  ' + ok);
  Logger.log('  Failed:  ' + failed);
  return ok;
}

/**
 * Get (or create on first use) the Drive Folder that holds all bill
 * images. We cache the resolved folder ID in ScriptProperties so we
 * don't scan My Drive on every uploadBillImage call — DriveApp folder
 * lookups by name are O(N) over root entries and can be slow once the
 * shop owner's Drive has grown.
 *
 * If the cached ID points to a trashed or otherwise-missing folder
 * (the user manually deleted it from Drive, for instance) we fall
 * back to creating a new one. The cache is then refreshed.
 */
function getOrCreateBillsFolder_() {
  const props = PropertiesService.getScriptProperties();
  const cachedId = props.getProperty(BILLS_FOLDER_PROP);

  if (cachedId) {
    try {
      const folder = DriveApp.getFolderById(cachedId);
      // Trashed folders still resolve by ID but isTrashed() flags them;
      // recreate in that case so new uploads land somewhere live.
      if (folder && !folder.isTrashed()) return folder;
    } catch (e) {
      // Folder was hard-deleted or we lost access. Fall through to recreate.
    }
  }

  // No usable cache. Look the folder up by name first — re-using an
  // existing one is much friendlier than silently creating "SanthaliaStore
  // Bills (1)" alongside it. Only create if nothing matches.
  let folder = null;
  const it = DriveApp.getRootFolder().getFoldersByName(BILLS_FOLDER_NAME);
  while (it.hasNext()) {
    const candidate = it.next();
    if (!candidate.isTrashed()) { folder = candidate; break; }
  }
  if (!folder) {
    folder = DriveApp.getRootFolder().createFolder(BILLS_FOLDER_NAME);
  }

  props.setProperty(BILLS_FOLDER_PROP, folder.getId());
  return folder;
}

/**
 * Get or create the `_deleted/` sub-folder used for soft-deleted bill
 * images. Lives directly under the bills folder so a user browsing
 * Drive sees recovery photos in one obvious place.
 */
function getOrCreateDeletedSubfolder_(parent) {
  const it = parent.getFoldersByName(BILLS_DELETED_SUBNAME);
  while (it.hasNext()) {
    const candidate = it.next();
    if (!candidate.isTrashed()) return candidate;
  }
  return parent.createFolder(BILLS_DELETED_SUBNAME);
}

/**
 * Build a Google Sheets HYPERLINK formula string for the Bills sheet's
 * derived `imageLink` column from a CSV of Drive file IDs.
 *
 *   - 0 IDs   → '' (empty cell)
 *   - 1 ID    → =HYPERLINK("https://drive.google.com/uc?id=<id>&export=view", "View image")
 *   - N IDs   → =HYPERLINK(<first id's view URL>, "View (N images)")
 *
 * The link points at the FIRST image only because HYPERLINK supports
 * exactly one URL per cell; the label tells the shop owner how many
 * more images are attached so they know to open the bill in the app
 * if they need the rest.
 *
 * Label is intentionally English ("View image" / "View (N images)")
 * even though the rest of the app uses Hinglish. The Sheet UI shows
 * the label on a single, narrow line and short English labels scan
 * faster there than a transliterated Hinglish equivalent would.
 *
 * Defensive: any `"` inside an ID is escaped (Drive IDs are alpha-
 * numeric + `-` + `_` in practice, but the formula breaks badly if a
 * stray quote ever sneaks in, so the guard is cheap insurance).
 *
 * @param {string} imageFileIdsCsv  Raw CSV from the Bills sheet's
 *                                  `imageFileIds` column.
 * @return {string}                 Formula string starting with `=`,
 *                                  or '' when there are no IDs.
 */
function buildBillImageLinkFormula_(imageFileIdsCsv) {
  const raw = String(imageFileIdsCsv || '');
  if (raw.length === 0) return '';

  // Split, trim, drop empties. Same parsing the Android client uses.
  const ids = [];
  const parts = raw.split(',');
  for (let i = 0; i < parts.length; i++) {
    const t = String(parts[i] || '').trim();
    if (t.length > 0) ids.push(t);
  }
  if (ids.length === 0) return '';

  // Escape any embedded quotes so the formula stays well-formed.
  // Apps Script HYPERLINK formulas use the standard `""` escape for
  // a literal `"` inside a string argument.
  const first = ids[0].replace(/"/g, '""');
  const url = 'https://drive.google.com/uc?id=' + first + '&export=view';
  const label = (ids.length === 1) ? 'View image' : ('View (' + ids.length + ' images)');
  return '=HYPERLINK("' + url + '", "' + label + '")';
}
