/**
 * Lists of names (types, files) are shown sorted by name: case-insensitive,
 * numbers by value (`Add_2` before `Add_10`). Ties (names that differ only
 * in case) are broken by code point so the order is stable.
 */
const collator = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });

export function byName(a: string, b: string): number {
  return collator.compare(a, b) || (a < b ? -1 : a > b ? 1 : 0);
}

export function sortedNames(names: readonly string[]): string[] {
  return [...names].sort(byName);
}
