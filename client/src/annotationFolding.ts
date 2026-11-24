// Folding of `annotation( ... )` blocks in the text view: they hold the
// diagram's layout, which is normally not edited by hand, so they are folded
// whenever a text is loaded, shown as a plain grey `…` that only looks
// clickable when the mouse is on it (so that it does not draw attention). It,
// or the arrow in the gutter, unfolds one. Folding only changes what is shown,
// never the text.

import { codeFolding, foldEffect, foldGutter, foldService, foldedRanges, unfoldEffect } from '@codemirror/language';
import type { EditorState, Extension, StateEffect } from '@codemirror/state';
import type { EditorView } from '@codemirror/view';

const ANNOTATION = /(^|\n)[ \t]*annotation\s*\(/g;

/** The inside of the parentheses opened at `open`, or null if they are not closed. */
function inside(text: string, open: number): { from: number; to: number } | null {
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '(') depth++;
    else if (text[i] === ')' && --depth === 0) return { from: open + 1, to: i };
  }
  return null;
}

/** The inside of every `annotation( ... )` block of a text. */
export function annotationRanges(text: string): { from: number; to: number }[] {
  const ranges: { from: number; to: number }[] = [];
  for (const m of text.matchAll(ANNOTATION)) {
    const range = inside(text, m.index! + m[0].length - 1);
    if (range && range.to > range.from) ranges.push(range);
  }
  return ranges;
}

/** An annotation block that starts on the line, as a fold range. */
const annotationFold = foldService.of((state: EditorState, lineStart: number, lineEnd: number) => {
  const line = state.doc.sliceString(lineStart, lineEnd);
  const m = /^[ \t]*annotation\s*\(/.exec(line);
  if (!m) return null;
  const rest = state.doc.sliceString(lineStart);
  const range = inside(rest, m[0].length - 1);
  return range && range.to > range.from ? { from: lineStart + range.from, to: lineStart + range.to } : null;
});

/** Folding of annotation blocks: the fold service, a gutter with the arrows, and a plain `…`. */
export const annotationFolding: Extension = [
  annotationFold,
  codeFolding({
    placeholderDOM: (_view, onclick) => {
      const span = document.createElement('span');
      span.className = 'cm-foldPlaceholder';
      span.textContent = '…';
      span.title = 'Show the layout';
      span.setAttribute('aria-label', 'Show the layout');
      // On mouse down, not on click: the editor would otherwise move the
      // cursor there and redraw the …, and the click would be lost (so that
      // it took two clicks to unfold).
      span.onmousedown = (event) => {
        if (event.button !== 0) return;
        event.preventDefault();
        onclick(event);
      };
      return span;
    },
  }),
  foldGutter({ openText: '▾', closedText: '▸' }),
];

/** Folds every annotation block that is not folded yet. */
export function foldAnnotations(view: EditorView) {
  const folded = foldedRanges(view.state);
  const effects: StateEffect<unknown>[] = [];
  for (const r of annotationRanges(view.state.doc.toString())) {
    let already = false;
    folded.between(r.from, r.to, (from, to) => {
      if (from === r.from && to === r.to) already = true;
    });
    if (!already) effects.push(foldEffect.of(r));
  }
  if (effects.length > 0) view.dispatch({ effects });
}

/** Unfolds what hides the position (e.g. to show a problem there). */
export function unfoldAt(view: EditorView, pos: number) {
  const effects: StateEffect<unknown>[] = [];
  foldedRanges(view.state).between(pos, pos, (from, to) => {
    effects.push(unfoldEffect.of({ from, to }));
  });
  if (effects.length > 0) view.dispatch({ effects });
}
