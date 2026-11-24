import { describe, expect, it } from 'vitest';
import { declarationOffset } from './TextView';

describe("finding a type's declaration in the text", () => {
  const text = 'external Print(in: Int);\ndiagramtype PTrack {\n}\n  diagramtype P {\n}\n';

  it('finds the line that declares it', () => {
    expect(declarationOffset(text, 'PTrack')).toBe(text.indexOf('diagramtype PTrack'));
    // An indented declaration: the start of its line.
    expect(declarationOffset(text, 'P')).toBe(text.indexOf('  diagramtype P {'));
  });

  it('does not take a longer name for it, and returns -1 if there is none', () => {
    expect(declarationOffset('diagramtype PTrack {\n}\n', 'P')).toBe(-1);
    expect(declarationOffset(text, 'Main')).toBe(-1);
    expect(declarationOffset(text, 'Print')).toBe(-1); // not a diagram type
  });
});
