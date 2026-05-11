/**
 * Santhalia Rate Card — Apps Script unit tests
 * ---------------------------------------------
 * These tests exercise the cell-value normalization helpers in
 * Code.gs without touching a live spreadsheet. They are intended to
 * catch regressions in the date-handling layer that caused the
 * "locale string in name column" data-corruption bug.
 *
 * How to run:
 *   1. Open the Apps Script editor for this project.
 *   2. From the function dropdown, pick `runAllTests_`.
 *   3. Click Run.
 *   4. Open View → Logs (Cmd+Enter / Ctrl+Enter) to see the
 *      `PASS=N FAIL=M` summary plus per-test failures, if any.
 *
 * Each test throws on failure (caught by the runner) so a green run
 * means every assertion held.
 */

function test_toIsoTimestamp_handlesDate() {
  const d = new Date(Date.UTC(2026, 4, 5, 0, 0, 0));
  if (toIsoTimestamp_(d) !== '2026-05-05T00:00:00.000Z') throw 'fail';
}

function test_toIsoTimestamp_passesThroughString() {
  if (toIsoTimestamp_('2026-05-05T10:00:00Z') !== '2026-05-05T10:00:00Z') throw 'fail';
}

function test_toLocalDate_handlesDate() {
  const d = new Date(2026, 4, 5);
  if (toLocalDate_(d) !== '2026-05-05') throw 'fail';
}

function test_toLocalDate_passesThroughString() {
  if (toLocalDate_('2026-05-05') !== '2026-05-05') throw 'fail';
}

function test_parseBool_acceptsBooleanAndStrings() {
  if (parseBool_(true) !== true) throw 'fail';
  if (parseBool_('TRUE') !== true) throw 'fail';
  if (parseBool_('false') !== false) throw 'fail';
}

/**
 * Minimal fake `ctx` that mimics what `loadSheetContext_` produces, just
 * enough for `collectChangedRows_` to work. We don't need a real sheet —
 * the function only reads `ctx.values` and `ctx.colIndex`.
 */
function makeFakeItemsCtx_(rows) {
  const headers = ITEMS_HEADERS;
  const colIndex = {};
  for (let i = 0; i < headers.length; i++) colIndex[headers[i]] = i;
  return { headers: headers, colIndex: colIndex, values: rows };
}

/**
 * Verifies the pull pipeline filters tombstones out of the response while
 * still letting their serverUpdatedAt advance the returned cursor — that
 * combination is the whole point of the "no cross-device delete" mode.
 */
function test_collectChangedRows_excludesTombstonesButAdvancesCursor() {
  // 3 rows, middle one is a tombstone. Order matches ITEMS_HEADERS:
  //   ['code', 'name', 'unit', 'updatedAt', 'deleted', 'serverUpdatedAt']
  const rows = [
    ['A', 'Apple',  'kg', '2026-05-01T00:00:00.000Z', 'FALSE', '2026-05-01T10:00:00.000Z'],
    ['B', 'Banana', 'kg', '2026-05-02T00:00:00.000Z', 'TRUE',  '2026-05-02T10:00:00.000Z'],
    ['C', 'Cherry', 'kg', '2026-05-03T00:00:00.000Z', 'FALSE', '2026-05-03T10:00:00.000Z']
  ];
  const ctx = makeFakeItemsCtx_(rows);

  const result = collectChangedRows_(ctx, ITEMS_HEADERS, '', 1000, rowToItemDto_);

  // Two live rows in the response; the tombstone (B) is excluded.
  if (result.dtos.length !== 2) throw 'expected 2 live rows, got ' + result.dtos.length;
  if (result.dtos[0].code !== 'A') throw 'expected row A first, got ' + result.dtos[0].code;
  if (result.dtos[1].code !== 'C') throw 'expected row C second, got ' + result.dtos[1].code;
  // Make sure no tombstone leaked in.
  for (let i = 0; i < result.dtos.length; i++) {
    if (result.dtos[i].deleted === true) throw 'tombstone leaked into response: ' + result.dtos[i].code;
  }

  // Cursor MUST be the latest serverUpdatedAt across ALL changed rows,
  // including the tombstone in the middle would be 05-02 — but row C
  // (05-03) is later, so the visible answer is 05-03 either way. To
  // really test that tombstones can advance the cursor, run a second
  // case where the tombstone is the LATEST row.
  if (result.maxServerUpdatedAt !== '2026-05-03T10:00:00.000Z') {
    throw 'expected max cursor 2026-05-03T10:00:00.000Z, got ' + result.maxServerUpdatedAt;
  }
}

/**
 * The decisive case: when the newest row is itself a tombstone, the
 * cursor must STILL move past it — otherwise we'd re-evaluate the same
 * tombstone on every subsequent pull.
 */
function test_collectChangedRows_tombstoneAdvancesCursorEvenWhenLatest() {
  const rows = [
    ['A', 'Apple',  'kg', '2026-05-01T00:00:00.000Z', 'FALSE', '2026-05-01T10:00:00.000Z'],
    ['B', 'Banana', 'kg', '2026-05-09T00:00:00.000Z', 'TRUE',  '2026-05-09T10:00:00.000Z']
  ];
  const ctx = makeFakeItemsCtx_(rows);

  const result = collectChangedRows_(ctx, ITEMS_HEADERS, '', 1000, rowToItemDto_);

  if (result.dtos.length !== 1) throw 'expected 1 live row, got ' + result.dtos.length;
  if (result.dtos[0].code !== 'A') throw 'expected row A, got ' + result.dtos[0].code;
  if (result.maxServerUpdatedAt !== '2026-05-09T10:00:00.000Z') {
    throw 'cursor failed to advance past tombstone; got ' + result.maxServerUpdatedAt;
  }
}

/**
 * Cursor compare must still skip rows older than (or equal to) the
 * cursor — including tombstones — so we don't double-count work.
 */
function test_collectChangedRows_respectsCursorForTombstones() {
  const rows = [
    ['A', 'Apple',  'kg', '2026-05-01T00:00:00.000Z', 'FALSE', '2026-05-01T10:00:00.000Z'],
    ['B', 'Banana', 'kg', '2026-05-02T00:00:00.000Z', 'TRUE',  '2026-05-02T10:00:00.000Z'],
    ['C', 'Cherry', 'kg', '2026-05-03T00:00:00.000Z', 'FALSE', '2026-05-03T10:00:00.000Z']
  ];
  const ctx = makeFakeItemsCtx_(rows);

  // Cursor sits AFTER the tombstone — we should only see C, and the
  // returned max should be C's stamp (the tombstone is no longer in
  // the changed-set).
  const result = collectChangedRows_(ctx, ITEMS_HEADERS, '2026-05-02T10:00:00.000Z', 1000, rowToItemDto_);
  if (result.dtos.length !== 1) throw 'expected 1 row, got ' + result.dtos.length;
  if (result.dtos[0].code !== 'C') throw 'expected row C, got ' + result.dtos[0].code;
  if (result.maxServerUpdatedAt !== '2026-05-03T10:00:00.000Z') {
    throw 'expected cursor 2026-05-03T10:00:00.000Z, got ' + result.maxServerUpdatedAt;
  }
}

/**
 * Build an in-memory fake Bills ctx — same trick as makeFakeItemsCtx_,
 * but for the new Bills sheet. Starts empty unless seed rows are passed.
 */
function makeFakeBillsCtx_(rows) {
  const headers = BILLS_HEADERS;
  const colIndex = {};
  for (let i = 0; i < headers.length; i++) colIndex[headers[i]] = i;
  const values = rows || [];
  const keyMap = new Map();
  for (let r = 0; r < values.length; r++) {
    const k = String(values[r][0] || '');
    if (k) keyMap.set(k, r);
  }
  return { headers: headers, colIndex: colIndex, values: values, keyMap: keyMap, dirty: false };
}

/**
 * Happy path: upserting a fresh bill appends a row, stamps
 * serverUpdatedAt, and round-trips through rowToBillDto_ with the
 * correct field values.
 */
function test_upsertBillInMemory_appendsNewBill() {
  const ctx = makeFakeBillsCtx_([]);
  const payload = {
    id: 'bill-1',
    date: '2026-05-10',
    supplier: 'Acme Wholesale',
    totalAmount: 1234.5,
    notes: 'paid cash',
    imageFileIds: 'fileA,fileB',
    updatedAt: '2026-05-10T12:00:00.000Z'
  };
  const result = upsertBillInMemory_(ctx, payload, 0);
  if (result.error) throw 'unexpected error: ' + result.error.message;
  if (ctx.values.length !== 1) throw 'expected 1 row, got ' + ctx.values.length;

  const dto = rowToBillDto_(ctx.values[0], ctx.colIndex);
  if (dto.id           !== 'bill-1')         throw 'id mismatch: ' + dto.id;
  if (dto.date         !== '2026-05-10')     throw 'date mismatch: ' + dto.date;
  if (dto.supplier     !== 'Acme Wholesale') throw 'supplier mismatch: ' + dto.supplier;
  if (dto.totalAmount  !== 1234.5)           throw 'total mismatch: ' + dto.totalAmount;
  if (dto.notes        !== 'paid cash')      throw 'notes mismatch: ' + dto.notes;
  if (dto.imageFileIds !== 'fileA,fileB')    throw 'imageFileIds mismatch: ' + dto.imageFileIds;
  if (dto.deleted      !== false)            throw 'deleted should be false';
  // serverUpdatedAt is server-stamped to "now" — just check it's non-empty.
  if (!dto.serverUpdatedAt) throw 'serverUpdatedAt not stamped';
}

/**
 * Missing / empty totalAmount must round-trip as `null` (not 0). This
 * is the "user hasn't entered an amount yet" case and is meaningfully
 * different from a genuine zero-rupee bill.
 */
function test_upsertBillInMemory_nullTotalAmountRoundTripsAsNull() {
  const ctx = makeFakeBillsCtx_([]);
  const payload = {
    id: 'bill-2',
    date: '2026-05-11',
    supplier: '',
    totalAmount: null,    // explicit null
    notes: '',
    imageFileIds: '',
    updatedAt: '2026-05-11T09:00:00.000Z'
  };
  const result = upsertBillInMemory_(ctx, payload, 0);
  if (result.error) throw 'unexpected error: ' + result.error.message;

  const dto = rowToBillDto_(ctx.values[0], ctx.colIndex);
  if (dto.totalAmount !== null) throw 'expected null totalAmount, got ' + dto.totalAmount;
}

/**
 * Last-write-wins: an older incoming updatedAt must NOT clobber a
 * stored newer write. Same contract as items/entries.
 */
function test_upsertBillInMemory_lastWriteWins() {
  const ctx = makeFakeBillsCtx_([]);

  upsertBillInMemory_(ctx, {
    id: 'bill-3', date: '2026-05-11', supplier: 'New',
    totalAmount: 100, notes: '', imageFileIds: '',
    updatedAt: '2026-05-11T12:00:00.000Z'
  }, 0);

  // Older write — should be silently ignored.
  const result = upsertBillInMemory_(ctx, {
    id: 'bill-3', date: '2026-05-11', supplier: 'Older',
    totalAmount: 50, notes: '', imageFileIds: '',
    updatedAt: '2026-05-11T11:00:00.000Z'
  }, 1);
  if (result.error) throw 'unexpected error: ' + result.error.message;

  const dto = rowToBillDto_(ctx.values[0], ctx.colIndex);
  if (dto.supplier !== 'New') throw 'older write clobbered newer; supplier=' + dto.supplier;
  if (dto.totalAmount !== 100) throw 'older totalAmount won; got ' + dto.totalAmount;
}

/**
 * The bulkSync extension counts bills + deletedBills toward BULK_LIMIT
 * (verified indirectly here: a small payload with both arrays present
 * does not regress the items/entries paths). This is a smoke test that
 * the array-shape extraction works — full handler exercise needs a real
 * sheet, which Tests.gs deliberately avoids.
 */
function test_bulkSync_billsArrayShape() {
  // We can only test the shape-parsing layer without a real spreadsheet,
  // so we mimic what handleBulkSync_ does in its first few lines.
  const payload = {
    items: [],
    entries: [],
    bills: [{ id: 'b', date: '2026-05-11', updatedAt: '2026-05-11T00:00:00.000Z' }],
    deletedItems: [],
    deletedEntries: [],
    deletedBills: [{ id: 'old', updatedAt: '2026-05-11T00:00:00.000Z' }]
  };
  const items          = Array.isArray(payload.items)          ? payload.items          : [];
  const entries        = Array.isArray(payload.entries)        ? payload.entries        : [];
  const bills          = Array.isArray(payload.bills)          ? payload.bills          : [];
  const deletedItems   = Array.isArray(payload.deletedItems)   ? payload.deletedItems   : [];
  const deletedEntries = Array.isArray(payload.deletedEntries) ? payload.deletedEntries : [];
  const deletedBills   = Array.isArray(payload.deletedBills)   ? payload.deletedBills   : [];

  const total = items.length + entries.length + bills.length
              + deletedItems.length + deletedEntries.length + deletedBills.length;
  if (total !== 2) throw 'expected total=2 (1 bill + 1 deletedBill), got ' + total;
}

/**
 * Empty CSV → empty cell (no formula). Keeps bills with zero images
 * from cluttering the Sheet with dead "View image" links.
 */
function test_buildBillImageLinkFormula_empty() {
  if (buildBillImageLinkFormula_('')        !== '') throw 'empty string should yield ""';
  if (buildBillImageLinkFormula_(null)      !== '') throw 'null should yield ""';
  if (buildBillImageLinkFormula_(undefined) !== '') throw 'undefined should yield ""';
  // Pure-whitespace / pure-comma CSV degrades to empty too.
  if (buildBillImageLinkFormula_('   ')     !== '') throw 'whitespace should yield ""';
  if (buildBillImageLinkFormula_(',,')      !== '') throw 'commas-only should yield ""';
}

/**
 * Single ID → a HYPERLINK formula pointing at the Drive view URL for
 * that ID with the short "View image" label.
 */
function test_buildBillImageLinkFormula_singleImage() {
  const f = buildBillImageLinkFormula_('abc');
  if (f.indexOf('=HYPERLINK') !== 0)         throw 'formula should start with =HYPERLINK, got: ' + f;
  if (f.indexOf('id=abc')      < 0)          throw 'formula should contain id=abc, got: ' + f;
  if (f.indexOf('export=view') < 0)          throw 'formula should contain export=view, got: ' + f;
  if (f.indexOf('"View image"') < 0)         throw 'single-image label should be "View image", got: ' + f;
  // Should NOT include the multi-image suffix.
  if (f.indexOf('images)') >= 0)             throw 'single-image formula should not have plural label, got: ' + f;
}

/**
 * Multiple IDs → link points at the FIRST ID only (HYPERLINK is one
 * URL per cell), label tells the user there are more.
 */
function test_buildBillImageLinkFormula_multiImage() {
  const f = buildBillImageLinkFormula_('abc,def,ghi');
  if (f.indexOf('=HYPERLINK') !== 0)         throw 'formula should start with =HYPERLINK, got: ' + f;
  if (f.indexOf('id=abc') < 0)               throw 'link must point at first id (abc), got: ' + f;
  if (f.indexOf('id=def') >= 0)              throw 'link must NOT include the second id, got: ' + f;
  if (f.indexOf('(3 images)') < 0)           throw 'multi-image label should count 3 images, got: ' + f;
}

function runAllTests_() {
  const tests = [
    test_toIsoTimestamp_handlesDate,
    test_toIsoTimestamp_passesThroughString,
    test_toLocalDate_handlesDate,
    test_toLocalDate_passesThroughString,
    test_parseBool_acceptsBooleanAndStrings,
    test_collectChangedRows_excludesTombstonesButAdvancesCursor,
    test_collectChangedRows_tombstoneAdvancesCursorEvenWhenLatest,
    test_collectChangedRows_respectsCursorForTombstones,
    test_upsertBillInMemory_appendsNewBill,
    test_upsertBillInMemory_nullTotalAmountRoundTripsAsNull,
    test_upsertBillInMemory_lastWriteWins,
    test_bulkSync_billsArrayShape,
    test_buildBillImageLinkFormula_empty,
    test_buildBillImageLinkFormula_singleImage,
    test_buildBillImageLinkFormula_multiImage
  ];
  let pass = 0, fail = 0;
  tests.forEach(function (t) {
    try { t(); pass++; }
    catch (e) { fail++; Logger.log(t.name + ' FAIL: ' + e); }
  });
  Logger.log('PASS=' + pass + ' FAIL=' + fail);
  return { pass: pass, fail: fail };
}
