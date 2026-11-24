import { describe, expect, it } from 'vitest';
import { UndoHistory, type HistoryEntry } from './history';

const entry = (before: string, after: string): HistoryEntry => ({ diagramType: 'Main', before, after });

describe('the undo history', () => {
  it('undoes the latest edit first, and redoes what was undone', () => {
    const h = new UndoHistory();
    h.record('A.dia', entry('1', '2'));
    h.record('A.dia', entry('2', '3'));
    expect(h.nextUndo('A.dia')).toEqual(entry('2', '3'));
    expect(h.nextRedo('A.dia')).toBeUndefined();

    h.undone('A.dia');
    expect(h.nextUndo('A.dia')).toEqual(entry('1', '2'));
    expect(h.nextRedo('A.dia')).toEqual(entry('2', '3'));

    h.redone('A.dia');
    expect(h.nextUndo('A.dia')).toEqual(entry('2', '3'));
    expect(h.nextRedo('A.dia')).toBeUndefined();
  });

  it('forgets what could be redone when a new edit is made', () => {
    const h = new UndoHistory();
    h.record('A.dia', entry('1', '2'));
    h.undone('A.dia');
    h.record('A.dia', entry('1', 'x'));
    expect(h.nextRedo('A.dia')).toBeUndefined();
    expect(h.nextUndo('A.dia')).toEqual(entry('1', 'x'));
  });

  it('keeps one history per file, which can be cleared', () => {
    const h = new UndoHistory();
    h.record('A.dia', entry('a1', 'a2'));
    h.record('B.dia', entry('b1', 'b2'));
    h.clear('A.dia');
    expect(h.nextUndo('A.dia')).toBeUndefined();
    expect(h.nextUndo('B.dia')).toEqual(entry('b1', 'b2'));
    // Nothing to undo or redo: nothing happens.
    h.undone('C.dia');
    h.redone('C.dia');
    expect(h.nextUndo('C.dia')).toBeUndefined();
  });
});
