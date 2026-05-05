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

function runAllTests_() {
  const tests = [
    test_toIsoTimestamp_handlesDate,
    test_toIsoTimestamp_passesThroughString,
    test_toLocalDate_handlesDate,
    test_toLocalDate_passesThroughString,
    test_parseBool_acceptsBooleanAndStrings,
    test_collectChangedRows_excludesTombstonesButAdvancesCursor,
    test_collectChangedRows_tombstoneAdvancesCursorEvenWhenLatest,
    test_collectChangedRows_respectsCursorForTombstones
  ];
  let pass = 0, fail = 0;
  tests.forEach(function (t) {
    try { t(); pass++; }
    catch (e) { fail++; Logger.log(t.name + ' FAIL: ' + e); }
  });
  Logger.log('PASS=' + pass + ' FAIL=' + fail);
  return { pass: pass, fail: fail };
}
