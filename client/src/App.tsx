import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { applyNodeChanges, MarkerType, type NodeChange, type OnNodeDrag } from '@xyflow/react';
import { Canvas, type Extent } from './Canvas';
import { type FlowEdgeType } from './FlowEdge';
import { type DiagramFlowNode } from './nodes';
import {
  webSocketUrl,
  type ClientMessage,
  type DiagramEdge,
  type DiagramMessage,
  type ServerMessage,
} from './protocol';

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

type Status = 'connecting' | 'open' | 'reconnecting';

/** Delay before reconnecting after the connection drops: doubles up to the maximum. */
const RECONNECT_MIN_MS = 500;
const RECONNECT_MAX_MS = 10_000;

const UNBOUNDED: Extent = [
  [-Infinity, -Infinity],
  [Infinity, Infinity],
];

/** Literal id -> id of the node it feeds. */
function literalTargets(diagram: DiagramMessage | null): Map<string, string> {
  const literals = new Set((diagram?.nodes ?? []).filter((n) => n.kind === 'literal').map((n) => n.id));
  return new Map((diagram?.edges ?? []).filter((e) => literals.has(e.source)).map((e) => [e.source, e.target]));
}

/**
 * How far from the left edge each node fed by literals must stay, so that its
 * literals (drawn left of it) stay inside the area where things can be placed.
 * React Flow 12.3 only honours a node's own extent while dragging if the node
 * has a parent, so this is applied to position changes here (clampToEdge).
 */
function literalClearance(diagram: DiagramMessage | null): Map<string, number> {
  const byId = new Map((diagram?.nodes ?? []).map((n) => [n.id, n]));
  const minX = new Map<string, number>();
  for (const [literal, target] of literalTargets(diagram)) {
    const l = byId.get(literal);
    const t = byId.get(target);
    if (l && t) minX.set(target, Math.max(minX.get(target) ?? 0, t.x - l.x));
  }
  return minX;
}

function clampToEdge(changes: NodeChange<DiagramFlowNode>[], minX: Map<string, number>): NodeChange<DiagramFlowNode>[] {
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
function toFlowNodes(diagram: DiagramMessage | null): DiagramFlowNode[] {
  return (diagram?.nodes ?? []).map((n) => {
    const literal = n.kind === 'literal';
    return {
      id: n.id,
      type: n.kind,
      position: { x: n.x, y: n.y },
      width: n.width,
      height: n.height,
      draggable: literal || diagram?.readOnly ? false : undefined,
      selectable: !literal,
      ...(literal ? { extent: UNBOUNDED } : {}),
      data: { ...n },
    };
  });
}

/**
 * While nodes are dragged, moves each literal along with the node it feeds, so
 * it stays next to its port until the server sends the new layout.
 */
function moveLiterals(nodes: DiagramFlowNode[], literalTargets: Map<string, string>): DiagramFlowNode[] {
  if (literalTargets.size === 0) return nodes;
  const byId = new Map(nodes.map((n) => [n.id, n]));
  return nodes.map((n) => {
    const target = byId.get(literalTargets.get(n.id) ?? '');
    if (!target) return n;
    const x = target.position.x + (n.data.x - target.data.x);
    const y = target.position.y + (n.data.y - target.data.y);
    return x === n.position.x && y === n.position.y ? n : { ...n, position: { x, y } };
  });
}

/** Keeps ?file=…&type=… in the address bar so a view can be reloaded or linked. */
function updateUrl(file: string, diagramType: string | null) {
  const params = new URLSearchParams();
  params.set('file', file);
  if (diagramType) params.set('type', diagramType);
  window.history.replaceState(null, '', `?${params}`);
}

export function App() {
  const socket = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<Status>('connecting');
  const [files, setFiles] = useState<string[]>([]);
  const [diagram, setDiagram] = useState<DiagramMessage | null>(null);
  const [error, setError] = useState<string | null>(null);

  const send = useCallback((message: ClientMessage) => {
    socket.current?.send(JSON.stringify(message));
  }, []);

  const open = useCallback(
    (file: string, diagramType?: string) => send({ type: 'open', file, diagramType }),
    [send],
  );

  // One WebSocket at a time. If it closes (server restart, network change,
  // laptop sleep), reconnect with backoff; the "workspace" message on the new
  // connection reopens the diagram named in the URL, freshly loaded from disk.
  useEffect(() => {
    let ws: WebSocket | null = null;
    let stopped = false;
    let retryTimer: number | undefined;
    let delay = RECONNECT_MIN_MS;

    const reconnectNow = () => {
      if (!stopped && ws === null) {
        window.clearTimeout(retryTimer);
        connect();
      }
    };

    function connect() {
      const current = new WebSocket(webSocketUrl());
      ws = current;
      socket.current = current;
      current.onopen = () => {
        delay = RECONNECT_MIN_MS;
        setStatus('open');
      };
      current.onclose = () => {
        if (ws !== current) return;
        ws = null;
        socket.current = null;
        if (stopped) return;
        setStatus('reconnecting');
        retryTimer = window.setTimeout(connect, delay);
        delay = Math.min(delay * 2, RECONNECT_MAX_MS);
      };
      current.onmessage = (event) => {
        const message = JSON.parse(event.data as string) as ServerMessage;
        switch (message.type) {
          case 'workspace': {
            setFiles(message.files);
            const params = new URLSearchParams(window.location.search);
            const file = params.get('file') ?? message.files[0];
            if (file) {
              current.send(JSON.stringify({ type: 'open', file, diagramType: params.get('type') ?? undefined }));
            }
            break;
          }
          case 'diagram':
            setDiagram(message);
            setError(null);
            updateUrl(message.file, message.diagramType);
            break;
          case 'error':
            setError(message.message);
            // A refused move: put the nodes back where the file has them.
            setDiagram((d) => (d ? { ...d } : d));
            break;
        }
      };
    }

    connect();
    // Don't wait for the backoff when the user comes back to the tab.
    const onVisible = () => {
      if (document.visibilityState === 'visible') reconnectNow();
    };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', reconnectNow);
    return () => {
      stopped = true;
      window.clearTimeout(retryTimer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', reconnectNow);
      ws?.close();
    };
  }, []);

  // Nodes are controlled here so they can be dragged; every diagram from the
  // server (including the answer to a move) replaces them.
  const [nodes, setNodes] = useState<DiagramFlowNode[]>([]);
  const shown = useRef<string | null>(null);
  useEffect(() => {
    const key = diagram ? `${diagram.file}/${diagram.diagramType}` : null;
    const sameDiagram = key === shown.current;
    shown.current = key;
    setNodes((previous) => {
      // Keep the selection across updates of the same diagram (e.g. after a move).
      const selected = new Set(sameDiagram ? previous.filter((n) => n.selected).map((n) => n.id) : []);
      return toFlowNodes(diagram).map((n) => (selected.has(n.id) ? { ...n, selected: true } : n));
    });
  }, [diagram]);

  // Which diagram (file/type) has had its initial fit; the canvas exposes it as
  // data-fitted so that tests can wait until the view stops moving.
  const diagramKey = `${diagram?.file}/${diagram?.diagramType}`;
  const [fittedKey, setFittedKey] = useState<string | null>(null);
  const onFitted = useCallback(() => setFittedKey(diagramKey), [diagramKey]);

  const targets = useMemo(() => literalTargets(diagram), [diagram]);
  const clearance = useMemo(() => literalClearance(diagram), [diagram]);
  const onNodesChange = useCallback(
    (changes: NodeChange<DiagramFlowNode>[]) =>
      setNodes((current) => moveLiterals(applyNodeChanges(clampToEdge(changes, clearance), current), targets)),
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

  const edges = useMemo<FlowEdgeType[]>(
    () =>
      (diagram?.edges ?? []).map((e) => ({
        id: e.id,
        type: 'flow',
        selectable: false,
        source: e.source,
        sourceHandle: e.sourceHandle,
        target: e.target,
        targetHandle: e.targetHandle,
        markerEnd: e.fromLiteral
          ? undefined
          : { type: MarkerType.Arrow, color: arrowColor(e), width: 14, height: 14, strokeWidth: 1.5 },
        data: { ...e },
      })),
    [diagram],
  );

  return (
    <div className="app">
      <header className="toolbar">
        <strong className="toolbar__title">Bloqqi</strong>
        <label>
          File{' '}
          <select
            data-testid="file-select"
            value={diagram?.file ?? ''}
            onChange={(e) => open(e.target.value)}
            disabled={status !== 'open'}
          >
            {!diagram && <option value="">–</option>}
            {files.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </label>
        <label>
          Diagram type{' '}
          <select
            data-testid="type-select"
            value={diagram?.diagramType ?? ''}
            onChange={(e) => diagram && open(diagram.file, e.target.value)}
            disabled={status !== 'open' || !diagram || diagram.diagramTypes.length === 0}
          >
            {(!diagram || !diagram.diagramType) && <option value="">–</option>}
            {diagram?.diagramTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <span className={`status status--${status}`} data-testid="status">
          {status === 'open' ? 'connected' : `${status}…`}
        </span>
      </header>

      {error && (
        <div className="banner banner--error" role="alert">
          {error}
        </div>
      )}
      {diagram?.diagramType && diagram.readOnly && (
        <div className="banner banner--info" data-testid="read-only">
          Read-only: {diagram.readOnly}
        </div>
      )}

      <main className="canvas" data-fitted={fittedKey === diagramKey}>
        {diagram && !diagram.diagramType && (
          <p className="empty">
            {diagram.problems.length > 0
              ? `${diagram.file} could not be parsed.`
              : `${diagram.file} has no diagram types.`}
          </p>
        )}
        <Canvas
          key={diagramKey}
          diagram={diagram}
          nodes={nodes}
          edges={edges}
          draggable={status === 'open' && !!diagram?.diagramType && !diagram.readOnly}
          onNodesChange={onNodesChange}
          onNodeDragStop={onNodeDragStop}
          onFitted={onFitted}
        />
      </main>

      {diagram && diagram.problems.length > 0 && (
        <section className="problems" data-testid="problems">
          <h2>
            Problems in {diagram.file} ({diagram.problems.length})
          </h2>
          <ul>
            {diagram.problems.map((p, i) => (
              <li key={i}>
                {p.line > 0 ? `${p.line}:${p.column} ` : ''}
                {p.message}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
