// Which handles can be connected, for feedback while a connection is dragged.
// This mirrors the server's checks (ConnectionEdits.connect), which have the
// final say: the server knows type compatibility, accessibility and cycles.

import type { DiagramNode } from './protocol';

export interface HandleInfo {
  /** A connection starts at a source handle and ends at a target handle. */
  role: 'source' | 'target';
  typeName: string;
  /** A new connection may start (source) or end (target) here. */
  connectable: boolean;
}

/**
 * The handle `handleId` of a node: block ports are `in:<port>` / `out:<port>`;
 * other nodes have `in` (target) and `out` (source).
 */
export function handleInfo(node: DiagramNode, handleId: string | null | undefined): HandleInfo | null {
  if (!handleId) return null;
  if (node.kind === 'block') {
    const [side, name] = splitHandle(handleId);
    const port = (side === 'in' ? node.inPorts : node.outPorts).find((p) => p.name === name);
    if (!port) return null;
    return { role: side === 'in' ? 'target' : 'source', typeName: port.typeName, connectable: port.connectable };
  }
  if (handleId === 'in') return { role: 'target', typeName: node.typeName, connectable: node.inConnectable };
  if (handleId === 'out') return { role: 'source', typeName: node.typeName, connectable: node.outConnectable };
  return null;
}

function splitHandle(handleId: string): [string, string] {
  const i = handleId.indexOf(':');
  return i < 0 ? [handleId, ''] : [handleId.slice(0, i), handleId.slice(i + 1)];
}

/**
 * Whether a connection between two handles is worth offering: one source and
 * one target, on different nodes, both free, with the same type. (Bloqqi's
 * compatibleWith is "same type or subtype"; ports have primitive or struct
 * types, where that is the same type.)
 */
export function canConnect(
  a: { node: DiagramNode; handle: string | null | undefined },
  b: { node: DiagramNode; handle: string | null | undefined },
): boolean {
  if (a.node.id === b.node.id) return false;
  const x = handleInfo(a.node, a.handle);
  const y = handleInfo(b.node, b.handle);
  return !!x && !!y && x.role !== y.role && x.connectable && y.connectable && x.typeName === y.typeName;
}
