import { useCallback, useEffect, useMemo, useRef } from 'react';
import {
  Background,
  ConnectionLineType,
  ControlButton,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useNodesInitialized,
  useReactFlow,
  useStore,
  ViewportPortal,
  type EdgeChange,
  type IsValidConnection,
  type NodeChange,
  type OnBeforeDelete,
  type OnConnect,
  type OnNodeDrag,
} from '@xyflow/react';
import { edgeTypes, type FlowEdgeType } from './FlowEdge';
import type { FormRequest } from './AddForm';
import { PALETTE_MIME, TOOL_MIME, type Tool } from './Palette';
import { nodeTypes, type DiagramFlowNode } from './nodes';
import type { DiagramMessage } from './protocol';

export type Extent = [[number, number], [number, number]];

/**
 * Nodes can't be dragged above or left of the origin: positions are stored in
 * annotation(...) blocks, whose numbers are unsigned. (Literals and the blocks
 * they feed get their own extents, see App's toFlowNodes.)
 */
const NODE_EXTENT: Extent = [
  [0, 0],
  [Infinity, Infinity],
];

/**
 * The canvas shows only the area where nodes can go, plus a strip of this many
 * screen pixels outside it (shaded) so that the edge is visible. It is the same
 * at every zoom level and in every diagram.
 */
const OUTSIDE_PX = 16;

const FIT_OPTIONS = { padding: 0.2, maxZoom: 1.5 };

/** Width of a new block (Layout.DEFAULT_WIDTH on the server). */
const BLOCK_WIDTH = 60;

/** How close (screen pixels) a literal must be dropped to a free input port. */
const DROP_RADIUS = 24;

/** The free input handle nearest to a point on the screen, within DROP_RADIUS. */
function freeInputNear(x: number, y: number): { node: string; handle: string } | undefined {
  let best: { node: string; handle: string } | undefined;
  let bestDistance = DROP_RADIUS;
  document.querySelectorAll<HTMLElement>('.react-flow__handle.target.handle--free').forEach((el) => {
    const r = el.getBoundingClientRect();
    const d = Math.hypot(r.left + r.width / 2 - x, r.top + r.height / 2 - y);
    if (d <= bestDistance && el.dataset.nodeid && el.dataset.handleid) {
      bestDistance = d;
      best = { node: el.dataset.nodeid, handle: el.dataset.handleid };
    }
  });
  return best;
}

/**
 * Top-left corner of what is drawn, in flow coordinates: the origin, unless a
 * file has nodes or literals at negative positions (literals are drawn left of
 * the block they feed; the editor keeps them inside, but files can say
 * otherwise). Ports sticking out of a block at x = 0 fit in the strip.
 */
function contentOrigin(diagram: DiagramMessage | null): [number, number] {
  let minX = 0;
  let minY = 0;
  for (const n of diagram?.nodes ?? []) {
    minX = Math.min(minX, n.x);
    minY = Math.min(minY, n.y);
  }
  return [minX, minY];
}

export interface CanvasProps {
  diagram: DiagramMessage | null;
  nodes: DiagramFlowNode[];
  edges: FlowEdgeType[];
  /** Nodes can be moved and connected (connected, editable diagram). */
  editable: boolean;
  onNodesChange: (changes: NodeChange<DiagramFlowNode>[]) => void;
  onEdgesChange: (changes: EdgeChange<FlowEdgeType>[]) => void;
  onNodeDragStop: OnNodeDrag<DiagramFlowNode>;
  onConnect: OnConnect;
  isValidConnection: IsValidConnection<FlowEdgeType>;
  /** Called with the selection when Delete/Backspace is pressed; React Flow itself deletes nothing. */
  onDelete: (selection: { nodes: DiagramFlowNode[]; edges: FlowEdgeType[] }) => void;
  /** A palette entry was dropped: add a block of the type with its top-left corner at (x, y). */
  onAddBlock: (typeName: string, x: number, y: number) => void;
  /** A tool was dropped (literal: on the target port, if any): open its form. */
  onTool: (request: Omit<FormRequest, 'suggestedName'>) => void;
  /** Called once the initial view of the diagram has been fitted. */
  onFitted: () => void;
}

/** The diagram canvas. Remount it (key) for each diagram to get a fresh view. */
export function Canvas(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <Flow {...props} />
    </ReactFlowProvider>
  );
}

/** Keys that delete the selection (Backspace for Mac keyboards). */
const DELETE_KEYS = ['Delete', 'Backspace'];

function Flow({
  diagram,
  nodes,
  edges,
  editable,
  onNodesChange,
  onEdgesChange,
  onNodeDragStop,
  onConnect,
  isValidConnection,
  onDelete,
  onAddBlock,
  onTool,
  onFitted,
}: CanvasProps) {
  const flow = useReactFlow();

  // Dropping a palette entry adds a block, centred under the pointer.
  const onDragOver = useCallback(
    (event: React.DragEvent) => {
      const types = event.dataTransfer.types;
      if (editable && (types.includes(PALETTE_MIME) || types.includes(TOOL_MIME))) {
        event.preventDefault();
        event.dataTransfer.dropEffect = 'copy';
      }
    },
    [editable],
  );
  const onDrop = useCallback(
    (event: React.DragEvent) => {
      if (!editable) return;
      const typeName = event.dataTransfer.getData(PALETTE_MIME);
      const tool = event.dataTransfer.getData(TOOL_MIME) as Tool | '';
      if (!typeName && !tool) return;
      event.preventDefault();
      const p = flow.screenToFlowPosition({ x: event.clientX, y: event.clientY });
      const position = { x: Math.max(0, Math.round(p.x - BLOCK_WIDTH / 2)), y: Math.max(0, Math.round(p.y - 10)) };
      if (typeName) {
        onAddBlock(typeName, position.x, position.y);
      } else if (tool) {
        const screen = { x: event.clientX, y: event.clientY };
        const target = tool === 'literal' ? freeInputNear(screen.x, screen.y) : undefined;
        onTool({ tool, screen, position, target });
      }
    },
    [editable, flow, onAddBlock, onTool],
  );

  // Deleting is an edit of the file: hand the selection to the app and let
  // React Flow delete nothing; the server's answer replaces the diagram.
  const onBeforeDelete = useCallback<OnBeforeDelete<DiagramFlowNode, FlowEdgeType>>(
    async (selection) => {
      onDelete(selection);
      return false;
    },
    [onDelete],
  );

  const origin = useMemo(() => contentOrigin(diagram), [diagram]);
  // Panning stops OUTSIDE_PX screen pixels beyond the origin; in flow units
  // that depends on the zoom.
  const zoom = useStore((s) => s.transform[2]);
  const translateExtent = useMemo<Extent>(
    () => [
      [origin[0] - OUTSIDE_PX / zoom, origin[1] - OUTSIDE_PX / zoom],
      [Infinity, Infinity],
    ],
    [origin, zoom],
  );

  return (
    <ReactFlow
      nodes={nodes}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      onNodeDragStop={onNodeDragStop}
      edges={edges}
      nodeTypes={nodeTypes}
      edgeTypes={edgeTypes}
      nodesDraggable={editable}
      nodeExtent={NODE_EXTENT}
      translateExtent={translateExtent}
      nodesConnectable={editable}
      onConnect={onConnect}
      isValidConnection={isValidConnection}
      connectionLineType={ConnectionLineType.Step}
      onBeforeDelete={onBeforeDelete}
      onDragOver={onDragOver}
      onDrop={onDrop}
      deleteKeyCode={editable ? DELETE_KEYS : null}
      edgesFocusable={false}
      proOptions={{ hideAttribution: true }}
    >
      <Background gap={20} />
      {diagram?.diagramType && (
        <ViewportPortal>
          {/* The area x >= 0, y >= 0 where nodes can be placed; outside is shaded. */}
          <div className="allowed-area" data-testid="allowed-area" aria-hidden="true" />
        </ViewportPortal>
      )}
      <FitControls origin={origin} onFitted={onFitted} />
    </ReactFlow>
  );
}

/**
 * Fits the diagram once its nodes are measured, and replaces the Controls' fit
 * button. React Flow's fitView centres the diagram, which would show the
 * shaded outside; here the fitted view is then moved so that the content's
 * top-left corner sits OUTSIDE_PX from the canvas' top-left corner, like a page.
 */
function FitControls({ origin, onFitted }: { origin: [number, number]; onFitted: () => void }) {
  const flow = useReactFlow();
  const fit = useCallback(async () => {
    await flow.fitView(FIT_OPTIONS);
    const { x, y, zoom } = flow.getViewport();
    await flow.setViewport({
      x: Math.min(x, -origin[0] * zoom + OUTSIDE_PX),
      y: Math.min(y, -origin[1] * zoom + OUTSIDE_PX),
      zoom,
    });
  }, [flow, origin]);

  const initialized = useNodesInitialized();
  const done = useRef(false);
  useEffect(() => {
    if (initialized && !done.current) {
      done.current = true;
      void fit().then(onFitted);
    }
  }, [initialized, fit, onFitted]);

  return (
    <Controls showInteractive={false} showFitView={false}>
      <ControlButton
        onClick={() => void fit()}
        title="fit view"
        aria-label="fit view"
        className="react-flow__controls-fitview"
      >
        {/* React Flow's own fit-view icon. */}
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 30">
          <path d="M3.692 4.63c0-.53.4-.938.939-.938h5.215V0H4.708C2.13 0 0 2.054 0 4.63v5.216h3.692V4.631zM27.354 0h-5.2v3.692h5.17c.53 0 .984.4.984.939v5.215H32V4.631A4.624 4.624 0 0027.354 0zm.954 24.83c0 .532-.4.94-.939.94h-5.215v3.768h5.215c2.577 0 4.631-2.13 4.631-4.707v-5.139h-3.692v5.139zm-23.677.94c-.531 0-.939-.4-.939-.94v-5.138H0v5.139c0 2.577 2.13 4.707 4.708 4.707h5.138V25.77H4.631z" />
        </svg>
      </ControlButton>
    </Controls>
  );
}
