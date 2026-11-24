// The canvas' state and the edits made on it directly: React Flow nodes and
// edges (selection, dragging), connecting, deleting and adding blocks.

import { useCallback, useMemo, useState } from 'react';
import {
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type EdgeChange,
  type NodeChange,
  type OnConnect,
  type OnNodeDrag,
} from '@xyflow/react';
import { canConnect } from './connections';
import type { FlowEdgeType } from './FlowEdge';
import {
  clampToEdge,
  literalClearance,
  literalConnections as findLiteralConnections,
  literalTargets,
  moveLiterals,
  toFlowEdges,
  toFlowNodes,
} from './flow';
import type { DiagramFlowNode } from './nodes';
import type { ClientMessage, DiagramMessage } from './protocol';

export interface DiagramFlow {
  nodes: DiagramFlowNode[];
  edges: FlowEdgeType[];
  onNodesChange: (changes: NodeChange<DiagramFlowNode>[]) => void;
  onEdgesChange: (changes: EdgeChange<FlowEdgeType>[]) => void;
  onNodeDragStop: OnNodeDrag<DiagramFlowNode>;
  isValidConnection: (c: Connection | FlowEdgeType) => boolean;
  onConnect: OnConnect;
  /** Deletes connections (edge ids) and blocks, variables, parameters (node ids) as one edit. */
  deleteItems: (items: { edges?: string[]; nodes?: string[] }) => void;
  /** Delete/Backspace on the selection. */
  onDelete: (selection: { nodes: DiagramFlowNode[]; edges: FlowEdgeType[] }) => void;
  addBlock: (typeName: string, x: number, y: number) => void;
  /** Literal id -> the deletable connection it feeds. */
  literalConnections: Map<string, string>;
}

interface FlowState {
  /** The diagram the nodes and edges were made from. */
  diagram: DiagramMessage | null;
  nodes: DiagramFlowNode[];
  edges: FlowEdgeType[];
}

const diagramKey = (d: DiagramMessage | null) => (d ? `${d.file}/${d.diagramType}` : null);

export function useDiagramFlow(diagram: DiagramMessage | null, send: (message: ClientMessage) => void): DiagramFlow {
  // Nodes are controlled here so they can be dragged, and edges so that they
  // can be selected (for Delete). Every diagram from the server (including
  // the answer to a move) replaces them, while rendering: the canvas, which is
  // created anew for each shown type, must never get the previous diagram's
  // nodes (it would measure and fit those, then have them replaced).
  const [flow, setFlow] = useState<FlowState>(() => ({
    diagram,
    nodes: toFlowNodes(diagram),
    edges: toFlowEdges(diagram),
  }));
  let current = flow;
  if (flow.diagram !== diagram) {
    // Keep the node selection across updates of the same diagram (e.g. after a
    // move); the edge selection is cleared.
    const same = diagramKey(flow.diagram) === diagramKey(diagram);
    const selected = new Set(same ? flow.nodes.filter((n) => n.selected).map((n) => n.id) : []);
    current = {
      diagram,
      nodes: toFlowNodes(diagram).map((n) => (selected.has(n.id) ? { ...n, selected: true } : n)),
      edges: toFlowEdges(diagram),
    };
    setFlow(current);
  }
  const { nodes, edges } = current;

  const onEdgesChange = useCallback(
    (changes: EdgeChange<FlowEdgeType>[]) => setFlow((s) => ({ ...s, edges: applyEdgeChanges(changes, s.edges) })),
    [],
  );

  const targets = useMemo(() => literalTargets(diagram), [diagram]);
  const clearance = useMemo(() => literalClearance(diagram), [diagram]);
  const literalConnections = useMemo(() => findLiteralConnections(diagram), [diagram]);

  const onNodesChange = useCallback(
    (changes: NodeChange<DiagramFlowNode>[]) =>
      setFlow((s) => ({
        ...s,
        nodes: moveLiterals(applyNodeChanges(clampToEdge(changes, clearance), s.nodes), targets),
      })),
    [targets, clearance],
  );

  /** On drop, store the new positions in the file. */
  const onNodeDragStop = useCallback<OnNodeDrag<DiagramFlowNode>>(
    (_event, _node, dragged) => {
      if (!diagram?.diagramType) return;
      const moved = dragged
        .map((n) => ({
          id: n.id,
          x: Math.max(Math.round(n.position.x), clearance.get(n.id) ?? 0),
          y: Math.round(n.position.y),
          data: n.data,
        }))
        .filter((n) => n.x !== n.data.x || n.y !== n.data.y)
        .map(({ id, x, y }) => ({ id, x, y }));
      if (moved.length > 0) {
        send({ type: 'move', file: diagram.file, diagramType: diagram.diagramType, nodes: moved });
      }
    },
    [diagram, send, clearance],
  );

  const nodeData = useMemo(() => new Map((diagram?.nodes ?? []).map((n) => [n.id, n])), [diagram]);

  /** Offer only connections the server would accept (see connections.ts). */
  const isValidConnection = useCallback(
    (c: Connection | FlowEdgeType) => {
      const source = nodeData.get(c.source);
      const target = nodeData.get(c.target);
      return (
        !!source &&
        !!target &&
        canConnect({ node: source, handle: c.sourceHandle }, { node: target, handle: c.targetHandle })
      );
    },
    [nodeData],
  );

  const onConnect = useCallback<OnConnect>(
    (c) => {
      if (!diagram?.diagramType || !c.sourceHandle || !c.targetHandle) return;
      send({
        type: 'connect',
        file: diagram.file,
        diagramType: diagram.diagramType,
        source: { node: c.source, handle: c.sourceHandle },
        target: { node: c.target, handle: c.targetHandle },
      });
    },
    [diagram, send],
  );

  const deleteItems = useCallback(
    ({ edges: edgeIds = [], nodes: nodeIds = [] }: { edges?: string[]; nodes?: string[] }) => {
      if (diagram?.diagramType && edgeIds.length + nodeIds.length > 0) {
        send({ type: 'delete', file: diagram.file, diagramType: diagram.diagramType, edges: edgeIds, nodes: nodeIds });
      }
    },
    [diagram, send],
  );

  /** Delete/Backspace: the selected connections, literals (their connections) and nodes, as one edit. */
  const onDelete = useCallback(
    ({ nodes: selectedNodes, edges: selectedEdges }: { nodes: DiagramFlowNode[]; edges: FlowEdgeType[] }) => {
      const edgeIds = new Set(selectedEdges.filter((e) => e.data?.deletable).map((e) => e.id));
      const nodeIds: string[] = [];
      for (const n of selectedNodes) {
        const id = literalConnections.get(n.id);
        if (id) edgeIds.add(id);
        else if (n.data.deletable) nodeIds.push(n.id); // blocks, variables, parameters
      }
      deleteItems({ edges: [...edgeIds], nodes: nodeIds });
    },
    [literalConnections, deleteItems],
  );

  const addBlock = useCallback(
    (typeName: string, x: number, y: number) => {
      if (diagram?.diagramType) {
        send({ type: 'addBlock', file: diagram.file, diagramType: diagram.diagramType, typeName, x, y });
      }
    },
    [diagram, send],
  );

  return {
    nodes,
    edges,
    onNodesChange,
    onEdgesChange,
    onNodeDragStop,
    isValidConnection,
    onConnect,
    deleteItems,
    onDelete,
    addBlock,
    literalConnections,
  };
}
