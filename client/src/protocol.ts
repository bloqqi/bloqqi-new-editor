// Messages exchanged with the Java server over the WebSocket.
// Keep in sync with server/src/main/java/org/bloqqi/web/Protocol.java.

export type NodeKind = 'block' | 'inParameter' | 'outParameter' | 'variable' | 'literal';

/** A port of a block. `y` is the top of the 6×6 square, relative to the block. */
export interface DiagramPort {
  name: string;
  typeName: string;
  y: number;
  /** A new connection may end at this in-port (free, nameable) or start at this out-port. */
  connectable: boolean;
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
  /** Not blocks: a new connection may end at the `in` handle. */
  inConnectable: boolean;
  /** Not blocks: a new connection may start at the `out` handle. */
  outConnectable: boolean;
  /** A block declared in this type's body, which can be deleted here. */
  deletable: boolean;
}

/** A type a block can be added of (the palette; see DiagramBuilder.palette). */
export interface PaletteEntry {
  name: string;
  /** `local`: the file's own types; `standard`: the standard library. */
  group: 'local' | 'standard';
  kind: 'diagramType' | 'function' | 'externalFunction' | 'stateMachine';
  /** First line of the type's doc string, or null. */
  doc: string | null;
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
  /** Inherited, or inside an inlined block: dashed black. */
  inherited: boolean;
  /** Can be deleted here (declared by a statement in this type's body). */
  deletable: boolean;
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
  /** How the request changed the file (edits, restore), or null. */
  change: Change | null;
  palette: PaletteEntry[];
  /** The names in the shown type's `extends` clause. */
  superTypes: string[];
}

/** The text of a file before and after a change. */
export interface Change {
  before: string;
  after: string;
}

export interface ErrorMessage {
  type: 'error';
  message: string;
}

/** Pushed (not an answer): the shown file changed on disk (another tab, a text editor). */
export interface UpdateMessage extends Omit<DiagramMessage, 'type'> {
  type: 'update';
}

/** Pushed: the workspace's files changed (created, deleted, renamed). */
export interface FilesMessage {
  type: 'files';
  files: string[];
}

/** Pushed: a message for the user (e.g. the shown file was deleted). */
export interface NoticeMessage {
  type: 'notice';
  message: string;
}

export type ServerMessage =
  | WorkspaceMessage
  | DiagramMessage
  | ErrorMessage
  | UpdateMessage
  | FilesMessage
  | NoticeMessage;

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

/** One end of a connection: a node and one of its handles. */
export interface Endpoint {
  node: string;
  handle: string;
}

/** Connect two handles (appends `connect(...)` to the type); answered like a move. */
export interface ConnectRequest {
  type: 'connect';
  file: string;
  diagramType: string;
  source: Endpoint;
  target: Endpoint;
}

/**
 * Delete connections (edge ids) and blocks (node ids) in one edit; a block
 * takes its connections and annotation entry with it. Answered like a move.
 */
export interface DeleteRequest {
  type: 'delete';
  file: string;
  diagramType: string;
  edges: string[];
  nodes?: string[];
}

/** Add a block `Type_N;` of a palette type at (x, y). Answered like a move. */
export interface AddBlockRequest {
  type: 'addBlock';
  file: string;
  diagramType: string;
  typeName: string;
  x: number;
  y: number;
}

/**
 * Undo/redo: replace the file's text with `text` if it still contains
 * `expected`; otherwise the server answers with an error and changes nothing.
 */
export interface RestoreRequest {
  type: 'restore';
  file: string;
  diagramType: string | null;
  text: string;
  expected: string;
}

/** Types offered for new parameters and variables (DiagramEdits.SIMPLE_TYPES). */
export const SIMPLE_TYPES = ['Int', 'Bool', 'Real'] as const;

/** Kinds of variable (DiagramEdits.VARIABLE_KINDS). */
export const VARIABLE_KINDS = ['var', 'input', 'output'] as const;

/** Connect a literal (one token: 3, 2.5, true, "text") to a free target. Answered like a move. */
export interface AddLiteralRequest {
  type: 'addLiteral';
  file: string;
  diagramType: string;
  target: Endpoint;
  value: string;
}

/** Add `<kind> <name>: <typeName>;` at (x, y). Answered like a move. */
export interface AddVariableRequest {
  type: 'addVariable';
  file: string;
  diagramType: string;
  kind: (typeof VARIABLE_KINDS)[number];
  name: string;
  typeName: string;
  x: number;
  y: number;
}

/** Add an in- or out-parameter to the type's header, shown at (x, y). Answered like a move. */
export interface AddParameterRequest {
  type: 'addParameter';
  file: string;
  diagramType: string;
  direction: 'in' | 'out';
  name: string;
  typeName: string;
  x: number;
  y: number;
}

/** Rename a block, variable or parameter and all its uses. Answered like a move. */
export interface RenameRequest {
  type: 'rename';
  file: string;
  diagramType: string;
  node: string;
  name: string;
}

/** Change a literal's value. Answered like a move. */
export interface SetLiteralRequest {
  type: 'setLiteral';
  file: string;
  diagramType: string;
  literal: string;
  value: string;
}

/** Lay out the whole type with ELK and store it. Answered like a move. */
export interface AutoLayoutRequest {
  type: 'autoLayout';
  file: string;
  diagramType: string;
}

/** Add an empty diagram type at the end of the file; answered with its diagram. */
export interface AddDiagramTypeRequest {
  type: 'addDiagramType';
  file: string;
  name: string;
}

/** A parameter of a new external function. */
export interface ParameterSpec {
  name: string;
  typeName: string;
}

/**
 * Add `external <name>(<inputs> => <output>);`; answered with the diagram of
 * `diagramType` (the view does not change: an external function has no diagram).
 */
export interface AddExternalFunctionRequest {
  type: 'addExternalFunction';
  file: string;
  diagramType: string | null;
  name: string;
  inputs: ParameterSpec[];
  output: ParameterSpec | null;
}

/** Rename a diagram type or external function and its uses; answered with the shown type (renamed). */
export interface RenameTypeRequest {
  type: 'renameType';
  file: string;
  diagramType: string | null;
  typeName: string;
  name: string;
}

/** Delete an unused diagram type or external function; answered with the shown type (or the first). */
export interface DeleteTypeRequest {
  type: 'deleteType';
  file: string;
  diagramType: string | null;
  typeName: string;
}

/** Add `diagramtype <name> extends <superType> {}`; answered with the new type. */
export interface AddSubtypeRequest {
  type: 'addSubtype';
  file: string;
  superType: string;
  name: string;
}

/** Set a diagram type's `extends` clause. */
export interface SetSuperTypesRequest {
  type: 'setSuperTypes';
  file: string;
  typeName: string;
  superTypes: string[];
}

export type ClientMessage =
  | OpenRequest
  | MoveRequest
  | ConnectRequest
  | DeleteRequest
  | AddBlockRequest
  | AddLiteralRequest
  | AddVariableRequest
  | AddParameterRequest
  | RenameRequest
  | SetLiteralRequest
  | AutoLayoutRequest
  | AddDiagramTypeRequest
  | AddExternalFunctionRequest
  | RenameTypeRequest
  | DeleteTypeRequest
  | AddSubtypeRequest
  | SetSuperTypesRequest
  | RestoreRequest;

export function webSocketUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${window.location.host}/ws`;
}
