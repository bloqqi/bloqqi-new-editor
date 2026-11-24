import { describe, expect, it } from 'vitest';
import { annotationRanges } from './annotationFolding';

/** The text inside each range. */
const insides = (text: string) => annotationRanges(text).map((r) => text.slice(r.from, r.to));

describe('finding annotation blocks to fold', () => {
  it('finds the inside of each block, with nested parentheses', () => {
    const text = 'diagramtype A {\n}\nannotation(\n  x = (1, 2, 3, 4)\n)\n';
    expect(insides(text)).toEqual(['\n  x = (1, 2, 3, 4)\n']);
  });

  it('also when indented or spaced, but not after other text on the line', () => {
    const text = 'x annotation(no)\n  annotation (\n  y = (5, 6, 7, 8)\n)\n';
    expect(insides(text)).toEqual(['\n  y = (5, 6, 7, 8)\n']);
  });

  it('skips empty and unclosed blocks', () => {
    expect(insides('annotation()\nannotation(\n  z = (1, 2')).toEqual([]);
  });
});
