import { sortedNames } from './names';
import { REDO_KEYS, UNDO_KEYS } from './shortcuts';
import type { Server } from './useServer';

/** The header: file and diagram type selection, New ▾, Undo/Redo and the connection status. */
export function Toolbar({
  server,
  fileEditable,
  onNew,
  onFileMenu,
}: {
  server: Server;
  /** New types can be added to the file (connected, no syntax errors). */
  fileEditable: boolean;
  onNew: (event: React.MouseEvent<HTMLButtonElement>) => void;
  onFileMenu: (event: React.MouseEvent<HTMLButtonElement>) => void;
}) {
  const { status, files, diagram, open } = server;
  return (
    <header className="toolbar">
      <strong className="toolbar__title">Bloqqi</strong>
      <label>
        File{' '}
        <select
          data-testid="file-select"
          value={diagram?.file ?? ''}
          onChange={(e) => open(e.target.value)}
          disabled={status !== 'open'}
        >
          {!diagram && <option value="">–</option>}
          {sortedNames(files).map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        data-testid="file-menu"
        onClick={onFileMenu}
        disabled={status !== 'open'}
        title="New, rename or delete a file"
      >
        File ▾
      </button>
      <label>
        Diagram type{' '}
        <select
          data-testid="type-select"
          value={diagram?.diagramType ?? ''}
          onChange={(e) => diagram && open(diagram.file, e.target.value)}
          disabled={status !== 'open' || !diagram || diagram.diagramTypes.length === 0}
        >
          {(!diagram || !diagram.diagramType) && <option value="">–</option>}
          {sortedNames(diagram?.diagramTypes ?? []).map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </label>
      <button
        type="button"
        data-testid="new"
        onClick={onNew}
        disabled={!fileEditable}
        title={fileEditable ? 'Add a diagram type or external function to the file' : diagram?.readOnly ?? undefined}
      >
        New ▾
      </button>
      <span className="toolbar__group">
        <button
          type="button"
          data-testid="undo"
          onClick={server.undo}
          disabled={!server.canUndo}
          title={`Undo (${UNDO_KEYS})`}
        >
          Undo
        </button>
        <button
          type="button"
          data-testid="redo"
          onClick={server.redo}
          disabled={!server.canRedo}
          title={`Redo (${REDO_KEYS})`}
        >
          Redo
        </button>
      </span>
      <span className={`status status--${status}`} data-testid="status">
        {status === 'open' ? 'connected' : `${status}…`}
      </span>
    </header>
  );
}
