import { useEffect, useRef } from 'react';

/**
 * Asks before something that cannot be undone (e.g. deleting a file).
 * Escape or Cancel keeps things as they are.
 */
export function ConfirmForm({
  screen,
  title,
  message,
  action,
  error,
  busy,
  onConfirm,
  onCancel,
}: {
  screen: { x: number; y: number };
  title: string;
  message: string;
  /** The label of the confirming button, e.g. "Delete". */
  action: string;
  error: string | null;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const cancel = useRef<HTMLButtonElement>(null);
  useEffect(() => cancel.current?.focus(), []);
  const left = Math.min(screen.x, window.innerWidth - 280);
  const top = Math.max(8, screen.y);

  return (
    <form
      className="add-form add-form--wide"
      data-testid="confirm-form"
      aria-label={title}
      role="alertdialog"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onConfirm();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">{title}</h3>
      <p className="add-form__message">{message}</p>
      {error && (
        <p className="add-form__error" role="alert">
          {error}
        </p>
      )}
      <div className="add-form__buttons">
        <button type="button" ref={cancel} onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className="add-form__danger" disabled={busy}>
          {action}
        </button>
      </div>
    </form>
  );
}
