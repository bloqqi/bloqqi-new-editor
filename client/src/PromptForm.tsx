import { useEffect, useRef, useState } from 'react';

/** What a one-field form edits: the name of a node, or the value of a literal. */
export interface PromptRequest {
  kind: 'rename' | 'literalValue';
  /** The node (renamed) or literal (new value). */
  id: string;
  initial: string;
  /** Where the form appears (client coordinates). */
  screen: { x: number; y: number };
}

/**
 * A small one-field form (Rename…, Change value…). Enter applies, Escape
 * cancels; it stays open, showing the server's reason, until the edit is accepted.
 */
export function PromptForm({
  request,
  error,
  busy,
  onSubmit,
  onCancel,
}: {
  request: PromptRequest;
  error: string | null;
  busy: boolean;
  onSubmit: (value: string) => void;
  onCancel: () => void;
}) {
  const [value, setValue] = useState(request.initial);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    input.current?.focus();
    input.current?.select();
  }, []);
  const rename = request.kind === 'rename';
  const left = Math.min(request.screen.x, window.innerWidth - 260);
  const top = Math.max(8, Math.min(request.screen.y, window.innerHeight - 170));

  return (
    <form
      className="add-form"
      data-testid="prompt-form"
      aria-label={rename ? 'Rename' : 'Change value'}
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(value);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">{rename ? `Rename ${request.initial}` : 'Change value'}</h3>
      <label>
        {rename ? 'Name' : 'Value'}
        <input ref={input} value={value} onChange={(e) => setValue(e.target.value)} aria-label={rename ? 'Name' : 'Value'} />
      </label>
      {error && (
        <p className="add-form__error" role="alert">
          {error}
        </p>
      )}
      <div className="add-form__buttons">
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" disabled={busy}>
          {rename ? 'Rename' : 'Change'}
        </button>
      </div>
    </form>
  );
}
