import { useEffect, useRef, useState } from 'react';

/**
 * The form for a new file: its name (".dia" is added if missing) and the name
 * of a first, empty diagram type (none if left empty). Enter creates it,
 * Escape cancels; it stays open, showing the server's reason, until accepted.
 */
export function NewFileForm({
  screen,
  error,
  busy,
  onSubmit,
  onCancel,
}: {
  screen: { x: number; y: number };
  error: string | null;
  busy: boolean;
  onSubmit: (file: string, diagramType: string | null) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState('');
  const [diagramType, setDiagramType] = useState('Main');
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => first.current?.focus(), []);

  const left = Math.min(screen.x, window.innerWidth - 280);
  const top = Math.max(8, screen.y);

  return (
    <form
      className="add-form add-form--wide"
      data-testid="new-file-form"
      aria-label="New file"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        const trimmed = name.trim();
        onSubmit(trimmed.endsWith('.dia') ? trimmed : `${trimmed}.dia`, diagramType.trim() || null);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">New file</h3>
      <label>
        Name
        <input
          ref={first}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Controllers.dia"
          aria-label="Name"
        />
      </label>
      <label>
        Type
        <input
          value={diagramType}
          onChange={(e) => setDiagramType(e.target.value)}
          placeholder="(none)"
          aria-label="First diagram type"
          title="The name of a first, empty diagram type in the file (leave empty for none)"
        />
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
          Create
        </button>
      </div>
    </form>
  );
}
