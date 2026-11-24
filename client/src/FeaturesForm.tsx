import { useEffect, useRef, useState } from 'react';
import type { FeatureOption, FeaturesMessage } from './protocol';

/**
 * The order in which selected features are written (and applied): a feature
 * comes before those its `before` rules name; otherwise by name. As the
 * compiler orders them (FeatureSelection: topological sort, ties by name).
 */
export function featureOrder(options: FeatureOption[], selected: Set<string>): string[] {
  const chosen = options.filter((o) => selected.has(o.name));
  const order: string[] = [];
  const done = new Set<string>();
  while (order.length < chosen.length) {
    const ready = chosen
      .filter((o) => !done.has(o.name))
      // Ready when no chosen feature that is still to come must come before it.
      .filter((o) => !chosen.some((p) => !done.has(p.name) && p.name !== o.name && p.before.includes(o.name)))
      .map((o) => o.name)
      .sort();
    const next = ready[0] ?? chosen.find((o) => !done.has(o.name))!.name; // (a cycle: the compiler reports it)
    order.push(next);
    done.add(next);
  }
  return order;
}

/**
 * Feature selection for a block (old editor: the "Change features…" wizard):
 * the optional features of its type, as checkboxes. Selecting a feature
 * unselects those it excludes (and says so); the order in which the selected
 * features will be written is shown.
 */
export function FeaturesForm({
  block,
  features,
  screen,
  error,
  busy,
  onSubmit,
  onCancel,
}: {
  /** The block's name. */
  block: string;
  /** The block's features, or null while they are being fetched. */
  features: FeaturesMessage | null;
  screen: { x: number; y: number };
  error: string | null;
  busy: boolean;
  onSubmit: (features: string[]) => void;
  onCancel: () => void;
}) {
  const options = features?.options ?? [];
  const initial = new Set(options.filter((o) => o.selected).map((o) => o.name));
  const [selected, setSelected] = useState<Set<string> | null>(null);
  const current = selected ?? initial;
  const [note, setNote] = useState<string | null>(null);
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (features) form.current?.querySelector<HTMLElement>('input')?.focus();
  }, [features]);

  const toggle = (option: FeatureOption, on: boolean) => {
    const next = new Set(current);
    if (on) {
      const unselected = option.excludes.filter((f) => next.has(f));
      unselected.forEach((f) => next.delete(f));
      next.add(option.name);
      setNote(unselected.length > 0 ? `${option.name} excludes ${unselected.join(', ')}, so it was unselected.` : null);
    } else {
      next.delete(option.name);
      setNote(null);
    }
    setSelected(next);
  };

  const order = featureOrder(options, current);
  const changed = order.join(',') !== featureOrder(options, initial).join(',');
  const left = Math.min(screen.x, window.innerWidth - 320);
  const top = Math.max(8, Math.min(screen.y, window.innerHeight - 240 - options.length * 26));

  return (
    <form
      ref={form}
      className="add-form add-form--wide"
      data-testid="features-form"
      aria-label="Features"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(order);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">
        Features of {block}
        {features && <span className="features__base"> ({features.base})</span>}
      </h3>
      {!features ? (
        <p className="add-form__none">{error ? '' : 'Loading…'}</p>
      ) : (
        <ul className="features" data-testid="features">
          {options.map((o) => (
            <li key={o.name}>
              <label
                title={o.excludes.length > 0 ? `Cannot be used together with ${o.excludes.join(', ')}` : undefined}
              >
                <input
                  type="checkbox"
                  aria-label={o.name}
                  aria-description={`${o.typeName}${o.excludes.length > 0 ? `; excludes ${o.excludes.join(', ')}` : ''}`}
                  checked={current.has(o.name)}
                  onChange={(e) => toggle(o, e.target.checked)}
                />
                <span className="features__name">{o.name}</span>
                <span className="features__type">: {o.typeName}</span>
                {o.excludes.length > 0 && <span className="features__excludes">excludes {o.excludes.join(', ')}</span>}
              </label>
            </li>
          ))}
        </ul>
      )}
      {note && (
        <p className="add-form__message" role="status">
          {note}
        </p>
      )}
      {features && (
        <p className="add-form__message" data-testid="features-order">
          {order.length === 0
            ? 'No features: a plain block of ' + features.base + '.'
            : 'In this order: ' + order.join(', ')}
        </p>
      )}
      {error && (
        <p className="add-form__error" role="alert">
          {error}
        </p>
      )}
      <div className="add-form__buttons">
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" disabled={busy || !features || !changed}>
          Apply
        </button>
      </div>
    </form>
  );
}
