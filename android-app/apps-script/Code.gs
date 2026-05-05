/**
 * Santhalia Rate Card — Google Apps Script Backend
 * --------------------------------------------------
 * This script is the cloud sync backend for the offline-first
 * "Santhalia Rate Card" Android app. The Android app stores all
 * data locally (SQLite/Room) and periodically POSTs changes here.
 *
 * Three tabs are auto-created in the bound spreadsheet:
 *   - Items            (rate card items)
 *   - PurchaseEntries  (price history per item, per date)
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

const SCHEMA_VERSION = 2;

// --- Sheet/tab names and headers (the source of truth for column order) ---
const ITEMS_SHEET = 'Items';
const ENTRIES_SHEET = 'PurchaseEntries';
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
  PurchaseEntries: ['date', 'updatedAt', 'serverUpdatedAt']
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
      case 'health':       return handleHealth_();
      case 'upsertItem':   return handleUpsertItem_(payload);
      case 'deleteItem':   return handleDeleteItem_(payload);
      case 'upsertEntry':  return handleUpsertEntry_(payload);
      case 'deleteEntry':  return handleDeleteEntry_(payload);
      case 'bulkSync':     return handleBulkSync_(payload);
      case 'pullChanges':  return handlePullChanges_(payload);
      case 'logCrashes':   return handleLogCrashes_(payload);
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

/**
 * Bulk sync. Accepts up to BULK_LIMIT total changes split across:
 *   payload.items          — items to upsert
 *   payload.entries        — entries to upsert
 *   payload.deletedItems   — items to soft-delete (by code)
 *   payload.deletedEntries — entries to soft-delete (by entryId)
 *
 * We process every row inside a try/catch so one bad row does not
 * poison the whole batch. The response lists per-row errors with
 * the original index and key so the phone can retry just those.
 */
function handleBulkSync_(payload) {
  const items          = Array.isArray(payload.items)          ? payload.items          : [];
  const entries        = Array.isArray(payload.entries)        ? payload.entries        : [];
  const deletedItems   = Array.isArray(payload.deletedItems)   ? payload.deletedItems   : [];
  const deletedEntries = Array.isArray(payload.deletedEntries) ? payload.deletedEntries : [];

  const total = items.length + entries.length + deletedItems.length + deletedEntries.length;

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

    // Single batched write per sheet — much faster than row-by-row.
    flushSheet_(itemsCtx);
    flushSheet_(entriesCtx);
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

  let itemsCtx, entriesCtx;
  withLock_(function () {
    itemsCtx   = loadSheetContext_(ITEMS_SHEET, ITEMS_HEADERS);
    entriesCtx = loadSheetContext_(ENTRIES_SHEET, ENTRIES_HEADERS);
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

  // New cursor = max serverUpdatedAt seen across BOTH sheets, including
  // tombstone rows that were filtered out of the response. If nothing
  // changed at all we hand the client back the cursor it sent us —
  // never null — so it can keep polling without re-loading everything.
  let newCursor = cursor || '';
  if (itemsResult.maxServerUpdatedAt   > newCursor) newCursor = itemsResult.maxServerUpdatedAt;
  if (entriesResult.maxServerUpdatedAt > newCursor) newCursor = entriesResult.maxServerUpdatedAt;

  return jsonResponse_({
    ok: true,
    action: 'pullChanges',
    items: itemsResult.dtos,
    entries: entriesResult.dtos,
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
    const quantity  = (payload && payload.quantity !== undefined && payload.quantity !== null && payload.quantity !== '')
                        ? Number(payload.quantity) : '';
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
 * Soft delete: set deleted=TRUE and bump updatedAt. If the row does not
 * exist yet (phone deleted something we never saw), we insert a tombstone.
 *
 * @param {Object} ctx       Sheet context.
 * @param {string} keyName   Either 'code' (Items) or 'entryId' (PurchaseEntries).
 * @param {Object} payload   { code|entryId, updatedAt }.
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

/** Sheet row → PurchaseEntries DTO sent to the client. */
function rowToEntryDto_(row, colIndex) {
  const rawQty = row[colIndex.quantity];
  const quantity = (rawQty === '' || rawQty === null || rawQty === undefined)
    ? null
    : Number(rawQty);

  const rawPrice = row[colIndex.pricePerUnit];
  const pricePerUnit = (rawPrice === '' || rawPrice === null || rawPrice === undefined)
    ? 0
    : Number(rawPrice);

  return {
    entryId:         toPlainText_(row[colIndex.entryId]),
    itemCode:        toPlainText_(row[colIndex.itemCode]),
    date:            toLocalDate_(row[colIndex.date]),
    pricePerUnit:    pricePerUnit,
    quantity:        (quantity === null || isNaN(quantity)) ? null : quantity,
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
