import { useEffect, useRef, useState } from 'react';

/** What a one-field form edits: a node's name, a literal's value, or a new diagram type's name. */
export interface PromptRequest {
  kind: 'rename' | 'literalValue' | 'newDiagramType';
  /** The node (renamed) or literal (new value); unused for a new diagram type. */
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
  const texts = {
    rename: { title: `Rename ${request.initial}`, label: 'Name', button: 'Rename', aria: 'Rename' },
    literalValue: { title: 'Change value', label: 'Value', button: 'Change', aria: 'Change value' },
    newDiagramType: { title: 'New diagram type', label: 'Name', button: 'Create', aria: 'New diagram type' },
  }[request.kind];
  const left = Math.min(request.screen.x, window.innerWidth - 260);
  const top = Math.max(8, Math.min(request.screen.y, window.innerHeight - 170));

  return (
    <form
      className="add-form"
      data-testid="prompt-form"
      aria-label={texts.aria}
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
      <h3 className="add-form__title">{texts.title}</h3>
      <label>
        {texts.label}
        <input ref={input} value={value} onChange={(e) => setValue(e.target.value)} aria-label={texts.label} />
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
          {texts.button}
        </button>
      </div>
    </form>
  );
}
