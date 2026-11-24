import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import {
  bracketMatching,
  defaultHighlightStyle,
  foldKeymap,
  indentOnInput,
  syntaxHighlighting,
} from '@codemirror/language';
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { Annotation, Compartment, EditorSelection, EditorState, Transaction, type Text } from '@codemirror/state';
import {
  drawSelection,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
} from '@codemirror/view';
import { annotationFolding, foldAnnotations, unfoldAt } from './annotationFolding';
import { bloqqi, bloqqiHighlighting } from './bloqqiLanguage';
import type { ClientMessage, Problem } from './protocol';
import { IS_MAC } from './shortcuts';
import type { Answer } from './useServer';

export const SAVE_KEYS = IS_MAC ? '⌘S' : 'Ctrl+S';

/** How long typing must pause before the text is checked for problems. */
const CHECK_DELAY_MS = 300;

/** Marks changes of the editor's text that come from the server, not from typing. */
const fromServer = Annotation.define<boolean>();

export interface TextViewHandle {
  /** Saves the text if it has changed; resolves true if it is saved (or there was nothing to save). */
  save: () => Promise<boolean>;
}

/**
 * The text view: the whole file in a text editor (CodeMirror). Typing is
 * checked for problems as you go (nothing is written); Save (⌘S/Ctrl+S)
 * writes the text, if the file has not changed elsewhere since it was
 * loaded. The text may have errors; the diagram is then read-only.
 *
 * When the file's text changes (another tab, a text editor, undo): without
 * unsaved changes the editor takes the new text; with them, a banner offers
 * to reload it or to keep the unsaved text (saving it then overwrites the
 * other change).
 */
export const TextView = forwardRef<
  TextViewHandle,
  {
    file: string;
    /** The file's text, as last sent by the server. */
    text: string;
    /** The shown diagram type: the editor scrolls to its declaration. */
    diagramType: string | null;
    /** The problems of `text`. */
    problems: Problem[];
    /** Connected: the text can be edited and saved. */
    editable: boolean;
    request: (message: ClientMessage) => Promise<Answer>;
    onDirtyChange: (dirty: boolean) => void;
  }
>(function TextView({ file, text, diagramType, problems: savedProblems, editable, request, onDirtyChange }, ref) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  /** The file's text that the editor's text is based on: `expected` when saving. */
  const base = useRef(text);
  /** The text of a save on its way. */
  const saving = useRef<string | null>(null);
  const [dirty, setDirty] = useState(false);
  /** A newer text of the file, which arrived while there were unsaved changes. */
  const [conflict, setConflict] = useState<string | null>(null);
  const [problems, setProblems] = useState<Problem[]>(savedProblems);
  const [busy, setBusy] = useState(false);
  const editability = useRef(new Compartment());

  const latest = useRef({ file, request, onDirtyChange, savedProblems });
  latest.current = { file, request, onDirtyChange, savedProblems };

  const showProblems = useCallback((list: Problem[]) => {
    setProblems(list);
    const v = view.current;
    if (v) v.dispatch(setDiagnostics(v.state, toDiagnostics(v.state.doc, list)));
  }, []);

  const markDirty = useCallback((value: boolean) => {
    setDirty(value);
    latest.current.onDirtyChange(value);
  }, []);

  // Checking the text while typing: the latest check wins.
  const checkTimer = useRef<number | undefined>(undefined);
  const checkCount = useRef(0);
  const scheduleCheck = useCallback(
    (doc: string) => {
      window.clearTimeout(checkTimer.current);
      const n = ++checkCount.current;
      checkTimer.current = window.setTimeout(async () => {
        const answer = await latest.current.request({
          type: 'checkText',
          file: latest.current.file,
          text: doc,
        });
        if (answer.type === 'problems' && n === checkCount.current) showProblems(answer.problems);
      }, CHECK_DELAY_MS);
    },
    [showProblems],
  );

  /** Puts the server's text into the editor (not undoable there). */
  const replaceText = useCallback(
    (newText: string, newProblems: Problem[]) => {
      const v = view.current;
      if (!v) return;
      window.clearTimeout(checkTimer.current);
      checkCount.current++;
      const head = Math.min(v.state.selection.main.head, newText.length);
      v.dispatch({
        changes: { from: 0, to: v.state.doc.length, insert: newText },
        selection: EditorSelection.cursor(head),
        annotations: [fromServer.of(true), Transaction.addToHistory.of(false)],
      });
      foldAnnotations(v);
      base.current = newText;
      markDirty(false);
      setConflict(null);
      showProblems(newProblems);
    },
    [markDirty, showProblems],
  );

  const save = useCallback(async () => {
    const v = view.current;
    if (!v) return true;
    const doc = v.state.doc.toString();
    if (doc === base.current) return true;
    if (saving.current !== null) return false; // one save at a time
    saving.current = doc;
    setBusy(true);
    const answer = await latest.current.request({
      type: 'setText',
      file: latest.current.file,
      diagramType,
      text: doc,
      expected: base.current,
    });
    setBusy(false);
    if (answer.type !== 'diagram') saving.current = null;
    return answer.type === 'diagram';
  }, [diagramType]);
  useImperativeHandle(ref, () => ({ save }), [save]);
  const saveRef = useRef(save);
  saveRef.current = save;

  // The editor, created once per file.
  useEffect(() => {
    const v = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: text,
        extensions: [
          lineNumbers(),
          highlightActiveLineGutter(),
          lintGutter(),
          annotationFolding,
          history(),
          drawSelection(),
          indentOnInput(),
          bracketMatching(),
          highlightActiveLine(),
          highlightSelectionMatches(),
          bloqqi,
          bloqqiHighlighting,
          syntaxHighlighting(defaultHighlightStyle),
          EditorState.tabSize.of(2),
          // (⌘S/Ctrl+S is handled for the whole window, below.)
          keymap.of([indentWithTab, ...defaultKeymap, ...historyKeymap, ...searchKeymap, ...foldKeymap]),
          editability.current.of(EditorView.editable.of(true)),
          EditorView.updateListener.of((update) => {
            if (!update.docChanged || update.transactions.some((t) => t.annotation(fromServer))) return;
            const doc = update.state.doc.toString();
            markDirty(doc !== base.current);
            scheduleCheck(doc);
          }),
          EditorView.contentAttributes.of({
            'aria-label': 'File text',
            'data-testid': 'text-editor',
          }),
        ],
      }),
    });
    view.current = v;
    foldAnnotations(v);
    v.dispatch(setDiagnostics(v.state, toDiagnostics(v.state.doc, latest.current.savedProblems)));
    return () => {
      window.clearTimeout(checkTimer.current);
      v.destroy();
      view.current = null;
    };
    // The text of later versions comes in through the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    view.current?.dispatch({
      effects: editability.current.reconfigure(EditorView.editable.of(editable)),
    });
  }, [editable]);

  // A new text of the file from the server.
  useEffect(() => {
    const v = view.current;
    if (!v || text === base.current) return;
    const doc = v.state.doc.toString();
    if (text === saving.current) {
      // The answer to our save (maybe typed on since).
      saving.current = null;
      base.current = text;
      markDirty(doc !== text);
      if (doc === text) showProblems(savedProblems);
    } else if (doc === base.current || doc === text) {
      replaceText(text, savedProblems);
    } else {
      setConflict(text);
    }
  }, [text, savedProblems, markDirty, replaceText, showProblems]);

  // Show the declaration of the shown diagram type (on opening, and when it changes).
  useEffect(() => {
    const v = view.current;
    if (!v || !diagramType) return;
    const at = declarationOffset(v.state.doc.toString(), diagramType);
    if (at < 0) return;
    v.dispatch({
      selection: EditorSelection.cursor(at),
      effects: EditorView.scrollIntoView(at, { y: 'start', yMargin: 8 }),
    });
  }, [diagramType]);

  // ⌘S/Ctrl+S also when the focus is outside the editor; warn before leaving the page.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((IS_MAC ? e.metaKey : e.ctrlKey) && !e.altKey && e.key.toLowerCase() === 's') {
        e.preventDefault();
        void saveRef.current();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  useEffect(() => () => latest.current.onDirtyChange(false), []);
  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const goTo = (p: Problem) => {
    const v = view.current;
    if (!v) return;
    const [d] = toDiagnostics(v.state.doc, [p]);
    unfoldAt(v, d.from);
    v.dispatch({
      selection: EditorSelection.range(d.from, d.to),
      scrollIntoView: true,
    });
    v.focus();
  };

  return (
    <div className="text-view" data-testid="text-view">
      <div className="text-view__bar">
        <span className="text-view__file">{file}</span>
        <span className={`text-view__state${dirty ? ' text-view__state--dirty' : ''}`} data-testid="text-state">
          {busy ? 'Saving…' : dirty ? 'Unsaved changes' : 'Saved'}
        </span>
        <span className="text-view__spacer" />
        <button
          type="button"
          onClick={() => replaceText(base.current, latest.current.savedProblems)}
          disabled={!dirty || busy}
          title="Discard the unsaved changes"
        >
          Revert
        </button>
        <button
          type="button"
          className="text-view__save"
          onClick={() => void save()}
          disabled={!dirty || busy || !editable}
          title={`Save the file (${SAVE_KEYS})`}
        >
          Save
        </button>
      </div>
      {conflict !== null && (
        <div className="banner banner--error text-view__conflict" role="alert" data-testid="text-conflict">
          <span>{file} was changed outside this tab while you have unsaved changes.</span>
          <button type="button" onClick={() => replaceText(conflict, latest.current.savedProblems)}>
            Reload (discard mine)
          </button>
          <button
            type="button"
            onClick={() => {
              base.current = conflict;
              setConflict(null);
            }}
            title="Saving will then overwrite the other change"
          >
            Keep mine
          </button>
        </div>
      )}
      <div className="text-view__editor" ref={host} />
      <section className="text-view__problems" data-testid="text-problems" aria-label="Problems">
        {problems.length === 0 ? (
          <span className="text-view__ok">No problems</span>
        ) : (
          <ul>
            {problems.map((p, i) => (
              <li key={i}>
                <button type="button" onClick={() => goTo(p)}>
                  {p.line > 0 ? `${p.line}:${p.column} ` : ''}
                  {p.message}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
});

/** Where `diagramtype <name>` is declared in the text, or -1. */
export function declarationOffset(text: string, name: string): number {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const m = new RegExp(`(^|\\n)[ \\t]*diagramtype[ \\t]+${escaped}(?![A-Za-z0-9_$])`).exec(text);
  return m ? m.index + m[1].length : -1;
}

/** The server's problems (1-based line and column) as editor diagnostics, each underlining a word. */
function toDiagnostics(doc: Text, problems: Problem[]): Diagnostic[] {
  return problems.map((p) => {
    if (p.line <= 0 || doc.length === 0) {
      return {
        from: 0,
        to: Math.min(1, doc.length),
        severity: 'error',
        message: p.message,
      };
    }
    const line = doc.line(Math.min(p.line, doc.lines));
    const from = Math.min(line.from + Math.max(p.column - 1, 0), line.to);
    const word = /^[A-Za-z0-9_$]+/.exec(doc.sliceString(from, line.to));
    let to = from + (word ? word[0].length : 1);
    if (to > doc.length) to = doc.length;
    return {
      from: Math.min(from, to),
      to,
      severity: 'error',
      message: p.message,
    };
  });
}
