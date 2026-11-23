// Messages exchanged with the Java server over the WebSocket.
// Keep in sync with server/src/main/java/org/bloqqi/web/Protocol.java.

export type NodeKind = 'block' | 'inParameter' | 'outParameter' | 'variable' | 'literal';

/** A port of a block. `y` is the top of the 6×6 square, relative to the block. */
export interface DiagramPort {
  name: string;
  typeName: string;
  y: number;
}

/**
 * A node on the canvas. The rectangle is the node's body; block ports stick out
 * PORT_SIZE pixels on either side.
 */
export interface DiagramNode {
  /**
   * Node.accessString(): `name`, `Super::name` (inherited) or `outer$inner` (inlined).
   * Literals: `#literal:<target node>.<target handle>`.
   */
  id: string;
  kind: NodeKind;
  label: string;
  typeName: string;
  inherited: boolean;
  inlined: boolean;
  /** Block on a type cycle or dataflow cycle. */
  error: boolean;
  x: number;
  y: number;
  width: number;
  height: number;
  inPorts: DiagramPort[];
  outPorts: DiagramPort[];
}

/**
 * A connection. Handles: blocks have `in:<port>` and `out:<port>`; parameters,
 * variables and literals have `in` (left) and `out` (right).
 */
export interface DiagramEdge {
  id: string;
  source: string;
  sourceHandle: string;
  target: string;
  targetHandle: string;
  /** Not deletable here (inherited, or inside an inlined block): dashed black. */
  inherited: boolean;
  /** Connection error or dataflow cycle: red. */
  error: boolean;
  /** Source is a literal: no arrow head. */
  fromLiteral: boolean;
  /** The two halves of an interception; the half replacing the inherited connection is dashed. */
  intercept: 'first' | 'second' | null;
  largeMarker: boolean;
}

export const PORT_SIZE = 6;

export interface Problem {
  line: number;
  column: number;
  message: string;
}

export interface WorkspaceMessage {
  type: 'workspace';
  files: string[];
}

export interface DiagramMessage {
  type: 'diagram';
  file: string;
  diagramType: string | null;
  diagramTypes: string[];
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  problems: Problem[];
  /** Why the diagram cannot be edited (e.g. the file has syntax errors), or null. */
  readOnly: string | null;
}

export interface ErrorMessage {
  type: 'error';
  message: string;
}

export type ServerMessage = WorkspaceMessage | DiagramMessage | ErrorMessage;

export interface OpenRequest {
  type: 'open';
  file: string;
  diagramType?: string;
}

/**
 * Move nodes (not literals) of a diagram type to new body positions. The server
 * writes the positions to the type's annotation(...) block in the file and
 * answers with the new diagram (or an error, leaving the file unchanged).
 */
export interface MoveRequest {
  type: 'move';
  file: string;
  diagramType: string;
  nodes: { id: string; x: number; y: number }[];
}

export type ClientMessage = OpenRequest | MoveRequest;

export function webSocketUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}/ws`;
}
