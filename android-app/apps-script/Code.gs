/**
 * Santhalia Rate Card — Google Apps Script Backend
 * --------------------------------------------------
 * This script is the cloud sync backend for the offline-first
 * "Santhalia Rate Card" Android app. The Android app stores all
 * data locally (SQLite/Room) and periodically POSTs changes here.
 *
 * Two tabs are auto-created in the bound spreadsheet:
 *   - Items            (rate card items)
 *   - PurchaseEntries  (price history per item, per date)
 *
 * Sync model:
 *   - Last-write-wins by `updatedAt` (ISO 8601 string)
 *   - Soft deletes (deleted=TRUE) so phones can re-sync deletes
 *   - bulkSync caps at 200 changes per request to stay within limits
 *
 * Author note: written for clarity. The shop owner may peek inside,
 * so comments are friendly and the logic is straightforward.
 */

const SCHEMA_VERSION = 1;

// --- Sheet/tab names and headers (the source of truth for column order) ---
const ITEMS_SHEET = 'Items';
const ENTRIES_SHEET = 'PurchaseEntries';

const ITEMS_HEADERS = ['code', 'name', 'unit', 'updatedAt', 'deleted'];
const ENTRIES_HEADERS = [
  'entryId',
  'itemCode',
  'date',
  'pricePerUnit',
  'quantity',
  'supplier',
  'notes',
  'updatedAt',
  'deleted'
];

// Hard limit on a single bulkSync payload. Apps Script has a 6 minute
// execution cap; 200 rows finishes well within that even on a cold start.
const BULK_LIMIT = 200;

// How long to wait for the script lock (concurrent writes from multiple
// phones are possible). 30s is long enough to ride out a normal sync.
const LOCK_TIMEOUT_MS = 30 * 1000;


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

    const existingRow = ctx.keyMap.get(code); // 0-based row index inside ctx.values (excluding header), or undefined

    const newRow = buildRowFromHeaders_(ctx.headers, {
      code: code,
      name: name,
      unit: unit,
      updatedAt: updatedAt,
      deleted: 'FALSE'
    });

    if (existingRow === undefined) {
      // Append
      ctx.values.push(newRow);
      ctx.keyMap.set(code, ctx.values.length - 1);
      ctx.dirty = true;
    } else {
      // Compare updatedAt — keep the newer one. Equal timestamps still write
      // (so a re-send with deleted=FALSE will resurrect a soft-deleted row).
      const stored = ctx.values[existingRow];
      const storedUpdatedAt = String(stored[ctx.colIndex.updatedAt] || '');
      if (updatedAt >= storedUpdatedAt) {
        ctx.values[existingRow] = newRow;
        ctx.dirty = true;
      }
      // else: incoming is older — silently ignore (still counts as processed)
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
      deleted: 'FALSE'
    });

    if (existingRow === undefined) {
      ctx.values.push(newRow);
      ctx.keyMap.set(entryId, ctx.values.length - 1);
      ctx.dirty = true;
    } else {
      const stored = ctx.values[existingRow];
      const storedUpdatedAt = String(stored[ctx.colIndex.updatedAt] || '');
      if (updatedAt >= storedUpdatedAt) {
        ctx.values[existingRow] = newRow;
        ctx.dirty = true;
      }
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

    const existingRow = ctx.keyMap.get(key);

    if (existingRow === undefined) {
      // Insert a tombstone row so future syncs see the delete.
      const seed = {};
      ctx.headers.forEach(function (h) { seed[h] = ''; });
      seed[keyName]    = key;
      seed.updatedAt   = updatedAt;
      seed.deleted     = 'TRUE';
      const tombstone = buildRowFromHeaders_(ctx.headers, seed);
      ctx.values.push(tombstone);
      ctx.keyMap.set(key, ctx.values.length - 1);
      ctx.dirty = true;
    } else {
      const stored = ctx.values[existingRow];
      const storedUpdatedAt = String(stored[ctx.colIndex.updatedAt] || '');
      if (updatedAt >= storedUpdatedAt) {
        stored[ctx.colIndex.deleted]   = 'TRUE';
        stored[ctx.colIndex.updatedAt] = updatedAt;
        ctx.dirty = true;
      }
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
  const lastCol = Math.max(sheet.getLastColumn(), expectedHeaders.length);
  const lastRow = sheet.getLastRow();

  if (lastRow === 0) {
    sheet.getRange(1, 1, 1, expectedHeaders.length).setValues([expectedHeaders]);
    sheet.setFrozenRows(1);
  }

  // Read everything in one go — single getValues call per sheet per request.
  const all = sheet.getRange(1, 1, Math.max(sheet.getLastRow(), 1), Math.max(sheet.getLastColumn(), expectedHeaders.length)).getValues();

  let headerRow = all[0].map(function (h) { return String(h || '').trim(); });

  // If the existing header row is missing or wrong, rewrite it. We do not
  // try to be clever here — the schema is fixed, and the user has been told
  // not to rename columns.
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

  const headers = expectedHeaders.slice();
  const colIndex = {};
  for (let i = 0; i < headers.length; i++) colIndex[headers[i]] = i;

  // Data rows (everything below the header).
  const values = [];
  for (let r = 1; r < all.length; r++) {
    // Pad / trim row to header length so downstream code is simple.
    const row = all[r].slice(0, headers.length);
    while (row.length < headers.length) row.push('');
    values.push(row);
  }

  // Build key -> rowIndex map. Key column is always the FIRST header.
  const keyCol = colIndex[headers[0]];
  const keyMap = new Map();
  for (let r = 0; r < values.length; r++) {
    const k = String(values[r][keyCol] || '');
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
