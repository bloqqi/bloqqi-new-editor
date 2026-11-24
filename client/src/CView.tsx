import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { EditorSelection, EditorState } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';
import { c } from './cLanguage';
import { cOutline, type OutlineSection } from './cOutline';
import type { CCodeMessage, ClientMessage, CVariant } from './protocol';
import type { Answer } from './useServer';

/**
 * The C view: the C code the Bloqqi compiler generates for the file, read-only.
 * As on the compiler's command line, it is generated for the diagram type
 * `Main` (which must have no parameters), only if the file has no errors, and
 * either as `--c=minimal` (the program only) or `--c` (with includes, helpers
 * and a `main()` loop). It is generated again whenever the file changes.
 */
export function CView({
  file,
  text,
  request,
}: {
  file: string;
  /** The file's text: the code is generated again when it changes. */
  text: string;
  request: (message: ClientMessage) => Promise<Answer>;
}) {
  const [variant, setVariant] = useState<CVariant>('minimal');
  const [result, setResult] = useState<CCodeMessage | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  /** Shown for a moment when someone tries to type in the code. */
  const [editHint, setEditHint] = useState(false);
  const hintTimer = useRef<number | undefined>(undefined);
  const showEditHint = useCallback(() => {
    setEditHint(true);
    window.clearTimeout(hintTimer.current);
    hintTimer.current = window.setTimeout(() => setEditHint(false), 4000);
  }, []);
  useEffect(() => () => window.clearTimeout(hintTimer.current), []);

  useEffect(() => {
    let current = true;
    void request({ type: 'generateC', file, variant }).then((answer) => {
      if (!current) return;
      if (answer.type === 'cCode') setResult(answer);
      else if (answer.type === 'error')
        setResult({ type: 'cCode', variant, code: null, problems: [{ line: 0, column: 0, message: answer.message }] });
    });
    return () => {
      current = false;
    };
  }, [file, text, variant, request]);

  const code = result?.code ?? null;
  const outline = useMemo(() => (code === null ? [] : cOutline(code)), [code]);
  const [editor, setEditor] = useState<EditorView | null>(null);
  /** The line the overview marks: see CodeEditor's onLine. */
  const [line, setLine] = useState(1);
  /** Shows a line of the code at the top (as far as it scrolls), with the cursor on it. */
  const goTo = useCallback(
    (line: number) => {
      if (!editor) return;
      const at = editor.state.doc.line(Math.min(line, editor.state.doc.lines)).from;
      editor.dispatch({
        selection: EditorSelection.cursor(at),
        effects: EditorView.scrollIntoView(at, { y: 'start', yMargin: 0 }),
      });
    },
    [editor],
  );
  const copy = async () => {
    if (code === null) return;
    try {
      await navigator.clipboard.writeText(code);
      setCopied('Copied');
    } catch {
      setCopied('Could not copy');
    }
    window.setTimeout(() => setCopied(null), 2000);
  };

  return (
    <div className="text-view c-view" data-testid="c-view">
      <div className="text-view__bar">
        <span className="text-view__file">{file}</span>
        <span
          className="c-view__readonly"
          data-testid="c-readonly"
          title="Generated from the file by the Bloqqi compiler: change the diagram or its text instead"
        >
          Read-only
        </span>
        <span className="text-view__state">
          C for <code>Main</code>, as <code>bloqqi-compiler --c{variant === 'minimal' ? '=minimal' : ''}</code>
        </span>
        <span className="text-view__spacer" />
        <label className="c-view__variant" title="Include the includes, helpers and a main() loop (--c)">
          <input
            type="checkbox"
            checked={variant === 'with-driver'}
            onChange={(e) => setVariant(e.target.checked ? 'with-driver' : 'minimal')}
          />{' '}
          With driver
        </label>
        <button type="button" onClick={() => void copy()} data-testid="c-copy" disabled={code === null}>
          {copied ?? 'Copy'}
        </button>
      </div>
      {editHint && (
        <div className="banner banner--info" role="status" data-testid="c-edit-hint">
          The C code is generated from the file and cannot be edited here: change the diagram or its text.
        </div>
      )}
      {result === null ? (
        <p className="empty">Generating…</p>
      ) : code === null ? (
        <section className="c-view__problems" data-testid="c-problems">
          <p>The compiler does not generate C for {file}:</p>
          <ul>
            {result.problems.map((p, i) => (
              <li key={i}>
                {p.line > 0 ? `${p.line}:${p.column} ` : ''}
                {p.message}
              </li>
            ))}
          </ul>
        </section>
      ) : (
        <div className="c-view__body">
          <Overview sections={outline} line={line} onGoTo={goTo} />
          <CodeEditor code={code} onEditAttempt={showEditHint} onView={setEditor} onLine={setLine} />
        </div>
      )}
    </div>
  );
}

/**
 * The overview of the code: its sections (from the compiler's comments on
 * lines of their own). A click shows that section; the one being looked at is
 * marked.
 */
function Overview({
  sections,
  line,
  onGoTo,
}: {
  sections: OutlineSection[];
  /** The line whose section is marked. */
  line: number;
  onGoTo: (line: number) => void;
}) {
  // The section being looked at: the last one starting at or before the line.
  const current = [...sections].reverse().find((s) => s.line <= line);
  return (
    <nav className="c-view__outline" aria-label="Overview" data-testid="c-outline">
      <h2 className="c-view__outline-title">Overview</h2>
      <ul>
        {sections.map((s) => (
          <li key={`${s.line}:${s.title}`}>
            <button
              type="button"
              className="c-view__item"
              title={s.comment}
              aria-current={s === current ? 'location' : undefined}
              onClick={() => onGoTo(s.line)}
            >
              {s.title}
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/**
 * Read-only, highlighted code: it can be selected, searched and copied; an
 * attempt to type, paste or drop text is reported (`onEditAttempt`).
 */
function CodeEditor({
  code,
  onEditAttempt,
  onView,
  onLine,
}: {
  code: string;
  onEditAttempt: () => void;
  /** The editor, once created (null when it is gone): the overview scrolls it. */
  onView: (view: EditorView | null) => void;
  /**
   * The (1-based) line being looked at: the cursor's, while it is visible (a
   * click in the code or in the overview puts it there), otherwise the first
   * visible line.
   */
  onLine: (line: number) => void;
}) {
  const editAttempt = useRef(onEditAttempt);
  editAttempt.current = onEditAttempt;
  const reportLine = useRef(onLine);
  reportLine.current = onLine;
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const report = (v: EditorView) => {
    const scroller = v.scrollDOM;
    const top = v.lineBlockAtHeight(scroller.scrollTop + 2).from;
    const bottom = v.lineBlockAtHeight(scroller.scrollTop + scroller.clientHeight - 2).to;
    const head = v.state.selection.main.head;
    reportLine.current(v.state.doc.lineAt(head >= top && head <= bottom ? head : top).number);
  };
  useEffect(() => {
    const v = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: code,
        extensions: [
          lineNumbers(),
          c,
          syntaxHighlighting(defaultHighlightStyle),
          highlightSelectionMatches(),
          keymap.of(searchKeymap),
          EditorState.readOnly.of(true),
          EditorView.domEventHandlers({
            beforeinput: () => editAttempt.current(),
            paste: () => editAttempt.current(),
            drop: () => editAttempt.current(),
          }),
          EditorState.tabSize.of(2),
          EditorView.updateListener.of((u) => {
            if (u.selectionSet || u.docChanged || u.geometryChanged) report(u.view);
          }),
          EditorView.contentAttributes.of({
            'aria-label': 'Generated C code (read-only)',
            'aria-readonly': 'true',
            'data-testid': 'c-code',
          }),
        ],
      }),
    });
    view.current = v;
    const onScroll = () => report(v);
    v.scrollDOM.addEventListener('scroll', onScroll);
    report(v);
    onView(v);
    return () => {
      v.scrollDOM.removeEventListener('scroll', onScroll);
      onView(null);
      v.destroy();
      view.current = null;
    };
    // The code of later versions comes in through the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    const v = view.current;
    if (v && v.state.doc.toString() !== code) {
      v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: code } });
    }
  }, [code]);
  return <div className="text-view__editor c-view__code" ref={host} />;
}
