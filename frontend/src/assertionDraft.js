export function assertionDrafts(checks = []) {
  return checks.map(c => ({ kind: c.kind, target: c.target, value: c.kind === 'HEADER_EQUALS' || c.kind === 'JSON_TYPE' ? c.expected : JSON.stringify(c.expected ?? null) }));
}
export function parseAssertions(rows) {
  return rows.map((row, index) => {
    let expected = null;
    if (row.kind === 'HEADER_EQUALS' || row.kind === 'JSON_TYPE') expected = row.value;
    else if (row.kind !== 'JSON_EXISTS') {
      try { expected = JSON.parse(row.value); } catch { throw new Error(`Check ${index + 1}: enter a valid JSON value. Quote text, for example "Keyboard".`); }
    }
    if (['JSON_GTE','JSON_LTE'].includes(row.kind) && typeof expected !== 'number') throw new Error(`Check ${index + 1}: enter a number.`);
    return { kind: row.kind, target: row.target, expected };
  });
}
