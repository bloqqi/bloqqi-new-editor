import { useEffect, useRef, useState } from 'react';
import { sortedNames } from './names';

/**
 * The form for a diagram type's super types, i.e. its `extends A, B` clause.
 * The order matters: the compiler inherits from the super types in the order
 * written (each with its own super types first), so A's parameters, blocks
 * and connections come before B's (e.g. the order of the type's ports), and
 * B's interceptions apply after A's. The chosen super types are therefore an
 * ordered list (move up/down, remove); others are added at the end.
 */
export function SuperTypesForm({
  typeName,
  candidates,
  current,
  screen,
  error,
  busy,
  onSubmit,
  onCancel,
}: {
  typeName: string;
  /** The file's other diagram types. */
  candidates: string[];
  current: string[];
  screen: { x: number; y: number };
  error: string | null;
  busy: boolean;
  onSubmit: (superTypes: string[]) => void;
  onCancel: () => void;
}) {
  const [chosen, setChosen] = useState<string[]>(current);
  // Sorted, as in the old editor (DiagramTypePropertiesPage.getPossibleSuperTypes).
  // The chosen ones keep their order: it matters.
  const addable = sortedNames(candidates.filter((t) => !chosen.includes(t)));
  const [toAdd, setToAdd] = useState<string>(addable[0] ?? '');
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => form.current?.querySelector<HTMLElement>('button, select')?.focus(), []);
  // Keep the "add" choice valid as the list changes.
  useEffect(() => {
    if (!addable.includes(toAdd)) setToAdd(addable[0] ?? '');
  }, [addable, toAdd]);

  const move = (i: number, by: number) =>
    setChosen((list) => {
      const next = [...list];
      [next[i], next[i + by]] = [next[i + by], next[i]];
      return next;
    });
  const changed = chosen.join(',') !== current.join(',');
  const left = Math.min(screen.x, window.innerWidth - 300);
  const top = Math.max(8, Math.min(screen.y, window.innerHeight - 220 - chosen.length * 30));

  return (
    <form
      ref={form}
      className="add-form add-form--wide"
      data-testid="super-types-form"
      aria-label="Super types"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(chosen);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">{typeName} extends</h3>
      {chosen.length === 0 ? (
        <p className="add-form__none">No super types</p>
      ) : (
        <ol className="super-types" data-testid="super-types">
          {chosen.map((t, i) => (
            <li key={t} className="super-types__item">
              <span className="super-types__name">{t}</span>
              <button
                type="button"
                onClick={() => move(i, -1)}
                disabled={i === 0}
                aria-label={`Move ${t} up`}
                title="Earlier: inherited before the ones below"
              >
                ↑
              </button>
              <button
                type="button"
                onClick={() => move(i, 1)}
                disabled={i === chosen.length - 1}
                aria-label={`Move ${t} down`}
                title="Later: inherited after the ones above"
              >
                ↓
              </button>
              <button
                type="button"
                onClick={() => setChosen((list) => list.filter((x) => x !== t))}
                aria-label={`Remove ${t}`}
                title={`No longer extend ${t}`}
              >
                ×
              </button>
            </li>
          ))}
        </ol>
      )}
      {addable.length > 0 && (
        <div className="super-types__add">
          <select value={toAdd} onChange={(e) => setToAdd(e.target.value)} aria-label="Super type to add">
            {addable.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
          <button type="button" onClick={() => toAdd && setChosen((list) => [...list, toAdd])}>
            Add
          </button>
        </div>
      )}
      <p className="add-form__message">
        The order matters: {typeName} inherits from the first one first, e.g. its parameters come first and its
        interceptions apply before those of the later ones.
      </p>
      {error && (
        <p className="add-form__error" role="alert">
          {error}
        </p>
      )}
      <div className="add-form__buttons">
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" disabled={busy || !changed}>
          Apply
        </button>
      </div>
    </form>
  );
}
