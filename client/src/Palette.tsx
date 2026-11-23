import type { PaletteEntry } from './protocol';

/** The drag-and-drop data type of a palette entry; its value is the type name. */
export const PALETTE_MIME = 'application/x-bloqqi-type';

/** Shown next to the less common kinds (most entries are diagram types or functions). */
const KIND_LABELS: Record<PaletteEntry['kind'], string> = {
  diagramType: '',
  function: '',
  externalFunction: 'external',
  stateMachine: 'state machine',
};

/**
 * The palette (docs/editor-spec.md §3.1): the types a block can be added of.
 * Drag an entry onto the diagram to add a block. The file's own types are
 * listed open; the standard library is collapsed, as in the old editor.
 */
export function Palette({ entries, editable }: { entries: PaletteEntry[]; editable: boolean }) {
  const local = entries.filter((e) => e.group === 'local');
  const standard = entries.filter((e) => e.group === 'standard');
  return (
    <aside
      className={`palette${editable ? '' : ' palette--disabled'}`}
      data-testid="palette"
      aria-label="Block types"
      title={editable ? undefined : 'This diagram cannot be edited'}
    >
      <h2 className="palette__title">Blocks</h2>
      <p className="palette__hint">Drag onto the diagram</p>
      <Group title="This file" entries={local} editable={editable} open />
      <Group title="Standard library" entries={standard} editable={editable} open={false} />
    </aside>
  );
}

function Group({
  title,
  entries,
  editable,
  open,
}: {
  title: string;
  entries: PaletteEntry[];
  editable: boolean;
  open: boolean;
}) {
  return (
    <details className="palette__group" open={open}>
      <summary>
        {title} <span className="palette__count">{entries.length}</span>
      </summary>
      {entries.length === 0 ? (
        <p className="palette__empty">No other types</p>
      ) : (
        <ul>
          {entries.map((e) => (
            <li
              key={`${e.group}:${e.name}`}
              className="palette__entry"
              data-testid={`palette-${e.name}`}
              draggable={editable}
              title={`Add a block of ${e.name}${e.doc ? `:\n${e.doc}` : ''}`}
              onDragStart={(event) => {
                event.dataTransfer.setData(PALETTE_MIME, e.name);
                event.dataTransfer.effectAllowed = 'copy';
              }}
            >
              <span className="palette__name">{e.name}</span>
              {KIND_LABELS[e.kind] && <span className="palette__kind">{KIND_LABELS[e.kind]}</span>}
            </li>
          ))}
        </ul>
      )}
    </details>
  );
}
