import { useEffect, useRef, useState } from 'react';

/**
 * The form for a diagram type's super types (its `extends` clause): one
 * checkbox per other diagram type of the file. The current super types keep
 * their order; newly ticked ones are added after them, in file order.
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
  const [checked, setChecked] = useState(new Set(current));
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => form.current?.querySelector<HTMLInputElement>('input')?.focus(), []);

  const result = () => [
    ...current.filter((t) => checked.has(t)),
    ...candidates.filter((t) => checked.has(t) && !current.includes(t)),
  ];
  const left = Math.min(screen.x, window.innerWidth - 260);
  const top = Math.max(8, Math.min(screen.y, window.innerHeight - 120 - candidates.length * 24));

  return (
    <form
      ref={form}
      className="add-form"
      data-testid="super-types-form"
      aria-label="Super types"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(result());
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">{typeName} extends</h3>
      {candidates.length === 0 ? (
        <p className="add-form__none">There are no other diagram types in the file.</p>
      ) : (
        <div className="add-form__checks">
          {candidates.map((t) => (
            <label key={t} className="add-form__check">
              <input
                type="checkbox"
                checked={checked.has(t)}
                onChange={(e) =>
                  setChecked((s) => {
                    const next = new Set(s);
                    if (e.target.checked) next.add(t);
                    else next.delete(t);
                    return next;
                  })
                }
              />
              {t}
            </label>
          ))}
        </div>
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
        <button type="submit" disabled={busy}>
          Apply
        </button>
      </div>
    </form>
  );
}
