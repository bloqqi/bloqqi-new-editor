import { describe, expect, it } from 'vitest';
import type { NodeChange } from '@xyflow/react';
import {
  clampToEdge,
  literalClearance,
  literalConnections,
  literalTargets,
  moveLiterals,
  toFlowEdges,
  toFlowNodes,
  unusedName,
} from './flow';
import type { DiagramFlowNode } from './nodes';
import { diagram, edge, node, port } from './testFixtures';

// A block `add` at x = 100, fed by the literal 1 drawn left of it at x = 40.
const add = node('add', { x: 100, y: 50, inPorts: [port('in1')], outPorts: [port('out')] });
const literal = node('#literal:add.in:in1', { kind: 'literal', label: '1', x: 40, y: 55 });
const fed = edge('#literal:add.in:in1', 'out', 'add', 'in:in1', { fromLiteral: true });
const inherited = edge('add', 'out:out', 'P::Out', 'in', { inherited: true, deletable: false });
const withLiteral = diagram([add, literal, node('P::Out', { kind: 'outParameter' })], [fed, inherited]);

describe('React Flow nodes', () => {
  it('literals cannot be dragged and may lie anywhere; other nodes follow the canvas', () => {
    const [block, lit] = toFlowNodes(withLiteral);
    expect(block).toMatchObject({ id: 'add', type: 'block', position: { x: 100, y: 50 }, draggable: undefined });
    expect(block.extent).toBeUndefined();
    expect(lit).toMatchObject({ type: 'literal', draggable: false });
    expect(lit.extent).toEqual([
      [-Infinity, -Infinity],
      [Infinity, Infinity],
    ]);
  });

  it('nothing can be dragged in a read-only diagram', () => {
    const readOnly = diagram([add], [], { readOnly: 'syntax errors' });
    expect(toFlowNodes(readOnly)[0].draggable).toBe(false);
    expect(toFlowNodes(null)).toEqual([]);
  });
});

describe('React Flow edges', () => {
  it('only declared connections can be deleted; literals have no arrow', () => {
    const [fromLiteral, inheritedEdge] = toFlowEdges(withLiteral);
    expect(fromLiteral.deletable).toBe(true);
    expect(fromLiteral.markerEnd).toBeUndefined();
    expect(inheritedEdge.deletable).toBe(false);
    expect(inheritedEdge.markerEnd).toMatchObject({ color: '#333333' });
    expect(toFlowEdges(diagram([], [fed], { readOnly: 'read-only' }))[0].deletable).toBe(false);
  });
});

describe('literals', () => {
  it('are known with the node and the connection they feed', () => {
    expect(literalTargets(withLiteral)).toEqual(new Map([['#literal:add.in:in1', 'add']]));
    expect(literalConnections(withLiteral)).toEqual(new Map([['#literal:add.in:in1', fed.id]]));
  });

  it('keep the node they feed far enough from the left edge for the literal to fit', () => {
    const clearance = literalClearance(withLiteral);
    expect(clearance).toEqual(new Map([['add', 60]]));
    const changes: NodeChange<DiagramFlowNode>[] = [
      { type: 'position', id: 'add', position: { x: 20, y: 50 } },
      { type: 'position', id: 'other', position: { x: 0, y: 0 } },
      { type: 'select', id: 'add', selected: true },
    ];
    expect(clampToEdge(changes, clearance)).toEqual([
      { type: 'position', id: 'add', position: { x: 60, y: 50 } },
      changes[1],
      changes[2],
    ]);
  });

  it('move along with the node they feed while it is dragged', () => {
    const nodes = toFlowNodes(withLiteral).map((n) => (n.id === 'add' ? { ...n, position: { x: 150, y: 70 } } : n));
    const moved = moveLiterals(nodes, literalTargets(withLiteral));
    expect(moved.find((n) => n.id === literal.id)!.position).toEqual({ x: 90, y: 75 });
    // Nothing to move: the same nodes.
    expect(moveLiterals(nodes, new Map())).toBe(nodes);
  });
});

describe('suggested names', () => {
  it('are the base, or the base with the first free number', () => {
    const d = diagram([node('acc'), node('P::acc2'), node('total')]);
    expect(unusedName('sum', d)).toBe('sum');
    expect(unusedName('acc', d)).toBe('acc3'); // acc2 is taken by an inherited node
    expect(unusedName('', d)).toBe('');
  });
});
