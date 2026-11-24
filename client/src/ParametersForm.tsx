import { useEffect, useRef, useState } from 'react';

/** A parameter as listed in the form. */
export interface ParameterItem {
  name: string;
  typeName: string;
  /** The super type it is inherited from, or null for the type's own. */
  from: string | null;
}

/**
 * The form for the order of a type's parameters (old editor: Diagram
 * properties, Move up/Move down/Remove). The order is the order of the ports
 * of blocks of this type. Inherited parameters come first and are listed for
 * reference only; the type's own ones can be moved and removed (removing
 * also deletes their connections). New parameters are added from the palette.
 */
export function ParametersForm({
  typeName,
  inParameters,
  outParameters,
  screen,
  error,
  busy,
  onSubmit,
  onCancel,
}: {
  typeName: string;
  inParameters: ParameterItem[];
  outParameters: ParameterItem[];
  screen: { x: number; y: number };
  error: string | null;
  busy: boolean;
  onSubmit: (inParameters: string[], outParameters: string[]) => void;
  onCancel: () => void;
}) {
  const own = (list: ParameterItem[]) => list.filter((p) => !p.from).map((p) => p.name);
  const [ins, setIns] = useState<string[]>(() => own(inParameters));
  const [outs, setOuts] = useState<string[]>(() => own(outParameters));
  const form = useRef<HTMLFormElement>(null);
  useEffect(() => form.current?.querySelector<HTMLElement>('button:not(:disabled)')?.focus(), []);

  const changed = ins.join(',') !== own(inParameters).join(',') || outs.join(',') !== own(outParameters).join(',');
  const rows = 2 + inParameters.length + outParameters.length;
  const left = Math.min(screen.x, window.innerWidth - 320);
  const top = Math.max(8, Math.min(screen.y, window.innerHeight - 200 - rows * 30));

  return (
    <form
      ref={form}
      className="add-form add-form--wide"
      data-testid="parameters-form"
      aria-label="Parameters"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(ins, outs);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">Parameters of {typeName}</h3>
      <ParameterList title="In" testId="in-parameters" all={inParameters} order={ins} setOrder={setIns} />
      <ParameterList title="Out" testId="out-parameters" all={outParameters} order={outs} setOrder={setOuts} />
      <p className="add-form__message">
        Blocks of type {typeName} get their ports in this order. Inherited parameters come first. Removing a parameter
        also deletes its connections; add new ones from the palette.
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

function ParameterList({
  title,
  testId,
  all,
  order,
  setOrder,
}: {
  title: string;
  testId: string;
  all: ParameterItem[];
  /** The type's own parameters, in their new order. */
  order: string[];
  setOrder: (update: (order: string[]) => string[]) => void;
}) {
  const inherited = all.filter((p) => p.from);
  const byName = new Map(all.map((p) => [p.name, p]));
  const move = (i: number, by: number) =>
    setOrder((list) => {
      const next = [...list];
      [next[i], next[i + by]] = [next[i + by], next[i]];
      return next;
    });
  return (
    <>
      <h4 className="ordered-list__subtitle">{title}</h4>
      {inherited.length + order.length === 0 ? (
        <p className="add-form__none">None</p>
      ) : (
        <ol className="ordered-list" data-testid={testId}>
          {inherited.map((p) => (
            <li
              key={`${p.from}::${p.name}`}
              className="ordered-list__item ordered-list__item--fixed"
              title={`Inherited from ${p.from}`}
            >
              <span className="ordered-list__name">
                {p.name}
                <span className="ordered-list__type">: {p.typeName}</span>
              </span>
              <span>from {p.from}</span>
            </li>
          ))}
          {order.map((name, i) => (
            <li key={name} className="ordered-list__item">
              <span className="ordered-list__name">
                {name}
                <span className="ordered-list__type">: {byName.get(name)?.typeName}</span>
              </span>
              <button type="button" onClick={() => move(i, -1)} disabled={i === 0} aria-label={`Move ${name} up`}>
                ↑
              </button>
              <button
                type="button"
                onClick={() => move(i, 1)}
                disabled={i === order.length - 1}
                aria-label={`Move ${name} down`}
              >
                ↓
              </button>
              <button
                type="button"
                onClick={() => setOrder((list) => list.filter((x) => x !== name))}
                aria-label={`Remove ${name}`}
                title="Remove (with its connections)"
              >
                ×
              </button>
            </li>
          ))}
        </ol>
      )}
    </>
  );
}
