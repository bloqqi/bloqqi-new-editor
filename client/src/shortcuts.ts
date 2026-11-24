// Keyboard shortcuts that are not React Flow's own (Delete is handled by the canvas).

import { useEffect, useLayoutEffect, useRef } from 'react';
import type { DiagramFlowNode } from './nodes';

export const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform);
export const UNDO_KEYS = IS_MAC ? '⌘Z' : 'Ctrl+Z';
export const REDO_KEYS = IS_MAC ? '⇧⌘Z' : 'Ctrl+Shift+Z';

/** Whether a key press is meant for a text field rather than the editor. */
function typing(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null;
  return !!t && (t.isContentEditable || t.tagName === 'INPUT' || t.tagName === 'TEXTAREA');
}

/**
 * Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y (⌘ on a Mac), except while typing in a field.
 * The listener always calls the latest undo/redo: they change with every
 * answer (whether something can be undone), and a listener re-registered in
 * an effect would only see them after the screen was updated, so a key
 * pressed right after an answer could be ignored.
 */
export function useUndoRedoKeys(undo: () => void, redo: () => void) {
  const latest = useRef({ undo, redo });
  useLayoutEffect(() => {
    latest.current = { undo, redo };
  }, [undo, redo]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(IS_MAC ? e.metaKey : e.ctrlKey) || e.altKey || typing(e)) return;
      const key = e.key.toLowerCase();
      if (key === 'z' && !e.shiftKey) {
        e.preventDefault();
        latest.current.undo();
      } else if ((key === 'z' && e.shiftKey) || (key === 'y' && !IS_MAC)) {
        e.preventDefault();
        latest.current.redo();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

/** F2 renames the selected node (or changes the selected literal's value). */
export function useRenameKey(nodes: DiagramFlowNode[], editNode: (node: DiagramFlowNode) => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'F2' || typing(e)) return;
      const selected = nodes.filter((n) => n.selected);
      if (selected.length === 1) {
        e.preventDefault();
        editNode(selected[0]);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [nodes, editNode]);
}
