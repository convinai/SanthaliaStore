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

function runAllTests_() {
  const tests = [
    test_toIsoTimestamp_handlesDate,
    test_toIsoTimestamp_passesThroughString,
    test_toLocalDate_handlesDate,
    test_toLocalDate_passesThroughString,
    test_parseBool_acceptsBooleanAndStrings
  ];
  let pass = 0, fail = 0;
  tests.forEach(function (t) {
    try { t(); pass++; }
    catch (e) { fail++; Logger.log(t.name + ' FAIL: ' + e); }
  });
  Logger.log('PASS=' + pass + ' FAIL=' + fail);
  return { pass: pass, fail: fail };
}
