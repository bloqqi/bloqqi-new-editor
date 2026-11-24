import { describe, expect, it } from 'vitest';
import { byName, sortedNames } from './names';

describe('sorting names', () => {
  it('ignores case and compares numbers by value', () => {
    expect(sortedNames(['Add_10', 'add_2', 'Add_1', 'Acc'])).toEqual(['Acc', 'Add_1', 'add_2', 'Add_10']);
  });

  it('breaks ties between names that differ only in case, so the order is stable', () => {
    expect(sortedNames(['b', 'B'])).toEqual(['B', 'b']);
    expect(byName('B', 'b')).toBeLessThan(0);
    expect(byName('x', 'x')).toBe(0);
  });

  it('does not change the list it is given', () => {
    const names = ['b', 'a'];
    sortedNames(names);
    expect(names).toEqual(['b', 'a']);
  });
});
