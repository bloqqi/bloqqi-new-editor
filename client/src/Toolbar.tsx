import { sortedNames } from './names';
import { REDO_KEYS, UNDO_KEYS } from './shortcuts';
import type { Server } from './useServer';

export type ViewMode = 'diagram' | 'text' | 'c';

/** Why something is disabled while the text view has unsaved changes. */
const UNSAVED = 'Save or revert the changes in the text first';

/**
 * The header: Back, file and diagram type selection, File ▾, New ▾, the
 * Diagram/Text/C switch, Undo/Redo and the connection status.
 */
export function Toolbar({
  server,
  fileEditable,
  view,
  textDirty,
  onView,
  onOpenFile,
  canGoBack,
  onBack,
  onNew,
  onFileMenu,
}: {
  server: Server;
  /** New types can be added to the file (connected, no syntax errors). */
  fileEditable: boolean;
  view: ViewMode;
  /** The text view has unsaved changes. */
  textDirty: boolean;
  onView: (view: ViewMode) => void;
  /** Another file was chosen (it may have to wait for unsaved text). */
  onOpenFile: (file: string) => void;
  /** An earlier file or type to go back to (in the browser's history). */
  canGoBack: boolean;
  onBack: () => void;
  onNew: (event: React.MouseEvent<HTMLButtonElement>) => void;
  onFileMenu: (event: React.MouseEvent<HTMLButtonElement>) => void;
}) {
  const { status, files, diagram, open } = server;
  return (
    <header className="toolbar">
      <strong className="toolbar__title">Bloqqi</strong>
      <button
        type="button"
        data-testid="back"
        className="toolbar__back"
        onClick={onBack}
        disabled={!canGoBack}
        aria-label="Back"
        title="Back to the file or type shown before (also the browser's Back)"
      >
        ←
      </button>
      <label>
        File{' '}
        <select
          data-testid="file-select"
          value={diagram?.file ?? ''}
          onChange={(e) => onOpenFile(e.target.value)}
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
        disabled={status !== 'open' || textDirty}
        title={textDirty ? UNSAVED : 'New, rename or delete a file'}
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
          {diagram?.diagramType && !diagram.diagramTypes.includes(diagram.diagramType) && (
            // A block's anonymous type (Main$pid), shown read-only.
            <option value={diagram.diagramType}>{diagram.diagramType} (anonymous)</option>
          )}
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
        disabled={!fileEditable || textDirty}
        title={
          textDirty
            ? UNSAVED
            : fileEditable
              ? 'Add a diagram type or external function to the file'
              : (diagram?.readOnly ?? undefined)
        }
      >
        New ▾
      </button>
      <span className="toolbar__group toolbar__views" role="group" aria-label="View">
        <button
          type="button"
          data-testid="view-diagram"
          aria-pressed={view === 'diagram'}
          onClick={() => onView('diagram')}
          title="Show the diagram"
        >
          Diagram
        </button>
        <button
          type="button"
          data-testid="view-text"
          aria-pressed={view === 'text'}
          onClick={() => onView('text')}
          disabled={!diagram}
          title="Show and edit the file's text"
        >
          Text
        </button>
        <button
          type="button"
          data-testid="view-c"
          aria-pressed={view === 'c'}
          onClick={() => onView('c')}
          disabled={!diagram}
          title="Show the C code the Bloqqi compiler generates for the file (for its diagram type Main)"
        >
          C
        </button>
      </span>
      <span className="toolbar__group">
        <button
          type="button"
          data-testid="undo"
          onClick={server.undo}
          disabled={!server.canUndo || textDirty}
          title={textDirty ? UNSAVED : `Undo (${UNDO_KEYS})`}
        >
          Undo
        </button>
        <button
          type="button"
          data-testid="redo"
          onClick={server.redo}
          disabled={!server.canRedo || textDirty}
          title={textDirty ? UNSAVED : `Redo (${REDO_KEYS})`}
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
