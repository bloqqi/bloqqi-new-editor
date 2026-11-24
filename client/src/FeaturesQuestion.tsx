import { useEffect, useRef } from 'react';

/**
 * Asked after a block of a type with optional features was added (as the old
 * editor did on creating such a block): select its features now? "No" (or
 * Escape) leaves a plain block; its features can be selected later with
 * "Features…".
 */
export function FeaturesQuestion({
  block,
  base,
  screen,
  onSelect,
  onDecline,
}: {
  block: string;
  base: string;
  screen: { x: number; y: number };
  onSelect: () => void;
  onDecline: () => void;
}) {
  const select = useRef<HTMLButtonElement>(null);
  useEffect(() => select.current?.focus(), []);
  const left = Math.min(screen.x, window.innerWidth - 300);
  const top = Math.max(8, Math.min(screen.y, window.innerHeight - 160));

  return (
    <form
      className="add-form add-form--wide"
      data-testid="features-question"
      aria-label="Select features"
      role="alertdialog"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onSelect();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onDecline();
        }
      }}
    >
      <h3 className="add-form__title">Features</h3>
      <p className="add-form__message">
        {base} has optional features. Select features for {block}?
      </p>
      <div className="add-form__buttons">
        <button type="button" onClick={onDecline}>
          No
        </button>
        <button type="submit" ref={select}>
          Select features…
        </button>
      </div>
    </form>
  );
}
