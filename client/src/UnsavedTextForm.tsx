import { useEffect, useRef } from 'react';
import { useEscape } from './shortcuts';

/**
 * Asks what to do with the text view's unsaved changes before leaving it
 * (switching to the diagram or to another file): save them, discard them,
 * or stay (Cancel or Escape).
 */
export function UnsavedTextForm({
  file,
  busy,
  onSave,
  onDiscard,
  onCancel,
}: {
  file: string;
  busy: boolean;
  onSave: () => void;
  onDiscard: () => void;
  onCancel: () => void;
}) {
  const save = useRef<HTMLButtonElement>(null);
  useEffect(() => save.current?.focus(), []);
  useEscape(!busy, onCancel);
  const left = Math.max(8, window.innerWidth / 2 - 160);

  return (
    <form
      className="add-form add-form--wide"
      data-testid="unsaved-text-form"
      aria-label="Unsaved changes"
      role="alertdialog"
      style={{ left, top: 72 }}
      onSubmit={(e) => {
        e.preventDefault();
        onSave();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">Unsaved changes</h3>
      <p className="add-form__message">The text of {file} has changes that are not saved.</p>
      <div className="add-form__buttons">
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
        <button type="button" onClick={onDiscard} disabled={busy}>
          Discard
        </button>
        <button type="submit" ref={save} disabled={busy}>
          Save
        </button>
      </div>
    </form>
  );
}
