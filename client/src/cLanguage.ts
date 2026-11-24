// Syntax highlighting for the generated C code in the C view (CodeMirror):
// tokens only, like bloqqiLanguage.ts.

import { StreamLanguage, type StreamParser } from '@codemirror/language';

const KEYWORDS = new Set(
  (
    'auto break case const continue default do else enum extern for goto if inline register restrict return ' +
    'sizeof static struct switch typedef union volatile while'
  ).split(' '),
);
const TYPES = new Set(
  'bool char double float int long short signed unsigned void size_t uint64_t int64_t Int Real Bool String'.split(' '),
);
const ATOMS = new Set(['true', 'false', 'NULL']);

interface State {
  comment: boolean;
}

const parser: StreamParser<State> = {
  name: 'c',
  startState: () => ({ comment: false }),
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
      return 'comment';
    }
    if (stream.sol() && stream.match(/^\s*#/)) {
      stream.skipToEnd();
      return 'meta';
    }
    if (stream.eatSpace()) return null;
    if (stream.match('//')) {
      stream.skipToEnd();
      return 'comment';
    }
    if (stream.match('/*')) {
      state.comment = true;
      return 'comment';
    }
    if (stream.match(/^"(?:[^"\\]|\\.)*"?/) || stream.match(/^'(?:[^'\\]|\\.)*'?/)) return 'string';
    if (stream.match(/^(0x[0-9a-fA-F]+|\d+(\.\d+)?([eE][+-]?\d+)?)[uUlLfF]*/)) return 'number';
    if (stream.match(/^[A-Za-z_][A-Za-z0-9_]*/)) {
      const word = stream.current();
      if (KEYWORDS.has(word)) return 'keyword';
      if (TYPES.has(word)) return 'typeName';
      if (ATOMS.has(word)) return 'atom';
      return 'variableName';
    }
    stream.next();
    return null;
  },
  languageData: {
    commentTokens: { line: '//', block: { open: '/*', close: '*/' } },
  },
};

export const c = StreamLanguage.define(parser);
