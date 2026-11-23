// Pure helpers that turn a diagram from the server into React Flow nodes and
// edges, and keep literals next to the nodes they feed.

import { MarkerType, type NodeChange } from '@xyflow/react';
import type { Extent } from './Canvas';
import type { FlowEdgeType } from './FlowEdge';
import type { DiagramFlowNode } from './nodes';
import type { DiagramEdge, DiagramMessage } from './protocol';

/** Arrow head colours (ConnectionPart.refreshVisuals); must match .flow* in styles.css. */
const COLOR_LOCAL = '#1f4fd1';
const COLOR_INHERITED = '#333333';
const COLOR_ERROR = '#d92d20';

function arrowColor(e: DiagramEdge): string {
  // The arrow sits on the second half of an interception's "second" connection,
  // which stands for the inherited connection.
  if (e.inherited || e.intercept === 'second') return COLOR_INHERITED;
  return e.error ? COLOR_ERROR : COLOR_LOCAL;
}

const UNBOUNDED: Extent = [
  [-Infinity, -Infinity],
  [Infinity, Infinity],
];

/** Literal id -> id of the node it feeds. */
export function literalTargets(diagram: DiagramMessage | null): Map<string, string> {
  const literals = new Set((diagram?.nodes ?? []).filter((n) => n.kind === 'literal').map((n) => n.id));
  return new Map((diagram?.edges ?? []).filter((e) => literals.has(e.source)).map((e) => [e.source, e.target]));
}

/** Literal id -> the deletable connection it feeds. */
export function literalConnections(diagram: DiagramMessage | null): Map<string, string> {
  return new Map((diagram?.edges ?? []).filter((e) => e.fromLiteral && e.deletable).map((e) => [e.source, e.id]));
}

/**
 * How far from the left edge each node fed by literals must stay, so that its
 * literals (drawn left of it) stay inside the area where things can be placed.
 * React Flow 12.3 only honours a node's own extent while dragging if the node
 * has a parent, so this is applied to position changes here (clampToEdge).
 */
export function literalClearance(diagram: DiagramMessage | null): Map<string, number> {
  const byId = new Map((diagram?.nodes ?? []).map((n) => [n.id, n]));
  const minX = new Map<string, number>();
  for (const [literal, target] of literalTargets(diagram)) {
    const l = byId.get(literal);
    const t = byId.get(target);
    if (l && t) minX.set(target, Math.max(minX.get(target) ?? 0, t.x - l.x));
  }
  return minX;
}

export function clampToEdge(
  changes: NodeChange<DiagramFlowNode>[],
  minX: Map<string, number>,
): NodeChange<DiagramFlowNode>[] {
  return changes.map((c) =>
    c.type === 'position' && c.position && (minX.get(c.id) ?? 0) > c.position.x
      ? { ...c, position: { ...c.position, x: minX.get(c.id)! } }
      : c,
  );
}

/**
 * React Flow nodes for a diagram. Literals can't be dragged (they follow their
 * target), nor can anything in a read-only diagram; otherwise the canvas-wide
 * nodesDraggable setting applies. Literals get an unbounded extent: they may
 * lie left of the origin in a file, and must not be pushed onto their block.
 */
export function toFlowNodes(diagram: DiagramMessage | null): DiagramFlowNode[] {
  return (diagram?.nodes ?? []).map((n) => {
    const literal = n.kind === 'literal';
    return {
      id: n.id,
      type: n.kind,
      position: { x: n.x, y: n.y },
      width: n.width,
      height: n.height,
      // Literals are selectable so that Delete removes their connection.
      draggable: literal || diagram?.readOnly ? false : undefined,
      ...(literal ? { extent: UNBOUNDED } : {}),
      data: { ...n },
    };
  });
}

/**
 * While nodes are dragged, moves each literal along with the node it feeds, so
 * it stays next to its port until the server sends the new layout.
 */
export function moveLiterals(nodes: DiagramFlowNode[], targets: Map<string, string>): DiagramFlowNode[] {
  if (targets.size === 0) return nodes;
  const byId = new Map(nodes.map((n) => [n.id, n]));
  return nodes.map((n) => {
    const target = byId.get(targets.get(n.id) ?? '');
    if (!target) return n;
    const x = target.position.x + (n.data.x - target.data.x);
    const y = target.position.y + (n.data.y - target.data.y);
    return x === n.position.x && y === n.position.y ? n : { ...n, position: { x, y } };
  });
}

/** React Flow edges for a diagram; only connections that can be deleted here are selectable. */
export function toFlowEdges(diagram: DiagramMessage | null): FlowEdgeType[] {
  return (diagram?.edges ?? []).map((e) => ({
    id: e.id,
    type: 'flow',
    selectable: e.deletable && !diagram?.readOnly,
    source: e.source,
    sourceHandle: e.sourceHandle,
    target: e.target,
    targetHandle: e.targetHandle,
    markerEnd: e.fromLiteral
      ? undefined
      : { type: MarkerType.Arrow, color: arrowColor(e), width: 14, height: 14, strokeWidth: 1.5 },
    data: { ...e },
  }));
}

/** `base` (or `base2`, `base3`, ...) if no node of the diagram has that name. */
export function unusedName(base: string, diagram: DiagramMessage | null): string {
  if (!base) return '';
  const used = new Set((diagram?.nodes ?? []).map((n) => n.id.split('::').pop()!));
  if (!used.has(base)) return base;
  for (let i = 2; ; i++) {
    if (!used.has(`${base}${i}`)) return `${base}${i}`;
  }
}
