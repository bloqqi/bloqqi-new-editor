// Undo/redo history of this tab, per file.
//
// The server is stateless, so the history lives here: every edit's answer
// carries the file's text before and after it. Undo asks the server to put
// back `before` if the file still contains `after`; redo the other way round.
// That way undo never overwrites changes made elsewhere (a text editor,
// another tab): the server refuses, and the file's history is cleared.

import type { Change } from './protocol';

export interface HistoryEntry extends Change {
  /** The diagram type the edit was made in; shown again on undo/redo. */
  diagramType: string | null;
}

interface Stacks {
  undo: HistoryEntry[];
  redo: HistoryEntry[];
}

function last<T>(items: T[] | undefined): T | undefined {
  return items && items.length > 0 ? items[items.length - 1] : undefined;
}

export class UndoHistory {
  private readonly files = new Map<string, Stacks>();

  private stacks(file: string): Stacks {
    let s = this.files.get(file);
    if (!s) {
      s = { undo: [], redo: [] };
      this.files.set(file, s);
    }
    return s;
  }

  /** A new edit: it can be undone, and what was undone before can no longer be redone. */
  record(file: string, entry: HistoryEntry) {
    const s = this.stacks(file);
    s.undo.push(entry);
    s.redo = [];
  }

  /** The edit that Undo would revert, if any. */
  nextUndo(file: string): HistoryEntry | undefined {
    return last(this.files.get(file)?.undo);
  }

  /** The edit that Redo would repeat, if any. */
  nextRedo(file: string): HistoryEntry | undefined {
    return last(this.files.get(file)?.redo);
  }

  /** The server has undone nextUndo(file). */
  undone(file: string) {
    const s = this.stacks(file);
    const e = s.undo.pop();
    if (e) s.redo.push(e);
  }

  /** The server has redone nextRedo(file). */
  redone(file: string) {
    const s = this.stacks(file);
    const e = s.redo.pop();
    if (e) s.undo.push(e);
  }

  /** Forget the file's history (e.g. it was changed elsewhere). */
  clear(file: string) {
    this.files.delete(file);
  }
}
