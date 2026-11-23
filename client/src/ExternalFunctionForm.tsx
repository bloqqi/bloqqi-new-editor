import { useEffect, useRef, useState } from 'react';
import { SIMPLE_TYPES, type ParameterSpec } from './protocol';

/**
 * The form for a new external function: its name, zero or more input
 * parameters and an optional output parameter. Enter adds it, Escape cancels;
 * it stays open, showing the server's reason, until the edit is accepted.
 */
export function ExternalFunctionForm({
  screen,
  error,
  busy,
  onSubmit,
  onCancel,
}: {
  screen: { x: number; y: number };
  error: string | null;
  busy: boolean;
  onSubmit: (name: string, inputs: ParameterSpec[], output: ParameterSpec | null) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState('');
  const [inputs, setInputs] = useState<ParameterSpec[]>([]);
  const [hasOutput, setHasOutput] = useState(false);
  const [output, setOutput] = useState<ParameterSpec>({ name: 'out', typeName: 'Int' });
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => first.current?.focus(), []);

  const setInput = (i: number, change: Partial<ParameterSpec>) =>
    setInputs((all) => all.map((p, j) => (i === j ? { ...p, ...change } : p)));
  const addInput = () =>
    setInputs((all) => {
      const used = new Set(all.map((p) => p.name));
      let n = all.length + 1;
      while (used.has(`in${n}`)) n++;
      return [...all, { name: `in${n}`, typeName: 'Int' }];
    });

  const left = Math.min(screen.x, window.innerWidth - 320);
  const top = Math.max(8, screen.y);

  return (
    <form
      className="add-form add-form--wide"
      data-testid="external-function-form"
      aria-label="New external function"
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(name.trim(), inputs, hasOutput ? output : null);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">New external function</h3>
      <label>
        Name
        <input ref={first} value={name} onChange={(e) => setName(e.target.value)} aria-label="Name" />
      </label>

      <fieldset className="add-form__params">
        <legend>Inputs</legend>
        {inputs.length === 0 && <p className="add-form__none">None</p>}
        {inputs.map((p, i) => (
          <div className="add-form__param" key={i}>
            <input
              value={p.name}
              onChange={(e) => setInput(i, { name: e.target.value })}
              aria-label={`Input ${i + 1} name`}
            />
            <select
              value={p.typeName}
              onChange={(e) => setInput(i, { typeName: e.target.value })}
              aria-label={`Input ${i + 1} type`}
            >
              {SIMPLE_TYPES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            <button
              type="button"
              className="add-form__remove"
              aria-label={`Remove input ${i + 1}`}
              title="Remove this input"
              onClick={() => setInputs((all) => all.filter((_, j) => j !== i))}
            >
              ×
            </button>
          </div>
        ))}
        <button type="button" className="add-form__add" onClick={addInput}>
          + Add input
        </button>
      </fieldset>

      <fieldset className="add-form__params">
        <legend>
          <label className="add-form__check">
            <input type="checkbox" checked={hasOutput} onChange={(e) => setHasOutput(e.target.checked)} />
            Output
          </label>
        </legend>
        {hasOutput ? (
          <div className="add-form__param">
            <input
              value={output.name}
              onChange={(e) => setOutput({ ...output, name: e.target.value })}
              aria-label="Output name"
            />
            <select
              value={output.typeName}
              onChange={(e) => setOutput({ ...output, typeName: e.target.value })}
              aria-label="Output type"
            >
              {SIMPLE_TYPES.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
          </div>
        ) : (
          <p className="add-form__none">None</p>
        )}
      </fieldset>

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
