// Syntax highlighting for Bloqqi in the text view (CodeMirror). Only tokens:
// the keywords and comments of the compiler's scanner
// (bloqqi-compiler/spec/scanner/bloqqi.flex), and numbers. The layout in
// `annotation( ... )` blocks is greyed out: it is written by the diagram, and
// is normally not changed by hand. The keyword itself is coloured like the
// others, so the block is still easy to find (and does not look disabled).

import { HighlightStyle, StreamLanguage, syntaxHighlighting, type StreamParser } from '@codemirror/language';
import { Tag } from '@lezer/highlight';

const KEYWORDS = new Set(
  (
    'abstract annotation before configuration connect default diagramtype else excludes extends external ' +
    'feature features function if import inline input intercept optional output package public recommendation ' +
    'redeclare replaceable return source statemachine struct super var while wiring with'
  ).split(' '),
);
const ATOMS = new Set(['true', 'false']);

/** What is in an `annotation( ... )` block, the parentheses included. */
const annotation = Tag.define();

interface State {
  /** Inside a block comment. */
  comment: boolean;
  /** After the keyword `annotation`, before its `(`. */
  annotationNext: boolean;
  /** Open parentheses of the annotation block we are in (0: not in one). */
  annotationDepth: number;
}

type Stream = Parameters<StreamParser<State>['token']>[0];

const parser: StreamParser<State> = {
  name: 'bloqqi',
  startState: () => ({ comment: false, annotationNext: false, annotationDepth: 0 }),
  copyState: (s) => ({ ...s }),
  token(stream, state) {
    if (state.comment) {
      if (stream.skipTo('*/')) {
        stream.next();
        stream.next();
        state.comment = false;
      } else {
        stream.skipToEnd();
      }
      return state.annotationDepth > 0 ? 'annotation' : 'comment';
    }
    if (stream.eatSpace()) return null;
    if (state.annotationNext || state.annotationDepth > 0) return annotationToken(stream, state);
    if (stream.match('//')) {
      stream.skipToEnd();
      return 'comment';
    }
    if (stream.match('/*')) {
      state.comment = true;
      return 'comment';
    }
    if (stream.match(/^\d+(\.\d+)?([eE][+-]?\d+)?/)) return 'number';
    if (stream.match(/^[A-Za-z_$][A-Za-z0-9_$]*/)) {
      const word = stream.current();
      if (word === 'annotation') {
        state.annotationNext = true;
        return 'keyword';
      }
      if (KEYWORDS.has(word)) return 'keyword';
      if (ATOMS.has(word)) return 'atom';
      // Names are not coloured: from the tokens alone, a type cannot be told
      // apart from, e.g., a parameter or block with a capitalised name.
      return 'variableName';
    }
    if (stream.match('=>') || stream.match('::')) return 'operator';
    stream.next();
    return null;
  },
  tokenTable: { annotation },
  languageData: {
    commentTokens: { line: '//', block: { open: '/*', close: '*/' } },
  },
};

/** A token in an annotation block (comments included), up to its closing parenthesis. */
function annotationToken(stream: Stream, state: State): string {
  if (stream.match('//')) {
    stream.skipToEnd();
  } else if (stream.match('/*')) {
    state.comment = true;
  } else {
    const ch = stream.next();
    if (ch === '(') {
      state.annotationDepth++;
      state.annotationNext = false;
    } else if (ch === ')') {
      state.annotationDepth = Math.max(0, state.annotationDepth - 1);
    } else if (state.annotationNext) {
      // Not `annotation(` after all.
      state.annotationNext = false;
    } else {
      stream.match(/^[^()\s/]*/);
    }
  }
  return 'annotation';
}

export const bloqqi = StreamLanguage.define(parser);

/** The grey of annotation blocks (comes before the default highlight style). */
export const bloqqiHighlighting = syntaxHighlighting(HighlightStyle.define([{ tag: annotation, color: '#9a9aa0' }]));
