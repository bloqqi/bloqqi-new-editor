import { useEffect, useRef, useState } from 'react';
import type { Tool } from './Palette';
import { SIMPLE_TYPES, VARIABLE_KINDS, type Endpoint } from './protocol';

/** A form opened by dropping a tool: where it opens, and what it adds. */
export interface FormRequest {
  tool: Tool;
  /** Where the form appears (client coordinates of the drop). */
  screen: { x: number; y: number };
  /** Where a variable or parameter is placed (flow coordinates). */
  position: { x: number; y: number };
  /** The port a literal feeds. */
  target?: Endpoint;
  /** A suggested name for a variable or parameter. */
  suggestedName?: string;
}

export interface FormValues {
  value?: string;
  name?: string;
  typeName?: string;
  kind?: (typeof VARIABLE_KINDS)[number];
}

const TITLES: Record<Tool, string> = {
  literal: 'Literal',
  variable: 'Variable',
  inParameter: 'Input parameter',
  outParameter: 'Output parameter',
};

/**
 * The small form for a literal's value or a variable's/parameter's name and
 * type. Enter adds, Escape cancels. It stays open (showing the server's reason)
 * until the edit is accepted.
 */
export function AddForm({
  request,
  error,
  busy,
  onSubmit,
  onCancel,
}: {
  request: FormRequest;
  error: string | null;
  busy: boolean;
  onSubmit: (values: FormValues) => void;
  onCancel: () => void;
}) {
  const [value, setValue] = useState('');
  const [name, setName] = useState(request.suggestedName ?? '');
  const [typeName, setTypeName] = useState<string>('Int');
  const [kind, setKind] = useState<(typeof VARIABLE_KINDS)[number]>('var');
  const first = useRef<HTMLInputElement>(null);
  useEffect(() => {
    first.current?.focus();
    first.current?.select();
  }, []);

  const literal = request.tool === 'literal';
  const submit = () => onSubmit(literal ? { value } : { name, typeName, kind });

  // Keep the form inside the window.
  const left = Math.min(request.screen.x, window.innerWidth - 260);
  const top = Math.max(8, Math.min(request.screen.y, window.innerHeight - 250));

  return (
    <form
      className="add-form"
      data-testid="add-form"
      aria-label={`Add ${TITLES[request.tool].toLowerCase()}`}
      style={{ left, top }}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          onCancel();
        }
      }}
    >
      <h3 className="add-form__title">{TITLES[request.tool]}</h3>
      {literal ? (
        <label>
          Value
          <input
            ref={first}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder='3, 2.5, true or "text"'
            aria-label="Value"
          />
        </label>
      ) : (
        <>
          {request.tool === 'variable' && (
            <label>
              Kind
              <select value={kind} onChange={(e) => setKind(e.target.value as typeof kind)} aria-label="Kind">
                {VARIABLE_KINDS.map((k) => (
                  <option key={k} value={k}>
                    {k}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label>
            Name
            <input ref={first} value={name} onChange={(e) => setName(e.target.value)} aria-label="Name" />
          </label>
          <label>
            Type
            <select value={typeName} onChange={(e) => setTypeName(e.target.value)} aria-label="Type">
              {SIMPLE_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
        </>
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
          Add
        </button>
      </div>
    </form>
  );
}
