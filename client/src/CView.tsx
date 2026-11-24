import { useEffect, useRef, useState } from 'react';
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { highlightSelectionMatches, searchKeymap } from '@codemirror/search';
import { EditorState } from '@codemirror/state';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';
import { c } from './cLanguage';
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
        <CodeEditor code={code} />
      )}
    </div>
  );
}

/** Read-only, highlighted code (text can be selected and searched). */
function CodeEditor({ code }: { code: string }) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
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
          EditorState.tabSize.of(2),
          EditorView.contentAttributes.of({ 'aria-label': 'Generated C code', 'data-testid': 'c-code' }),
        ],
      }),
    });
    view.current = v;
    return () => {
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
  return <div className="text-view__editor" ref={host} />;
}
