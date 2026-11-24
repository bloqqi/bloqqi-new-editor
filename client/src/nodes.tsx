import { useState } from 'react';
import { Handle, Position, useConnection, type Node, type NodeProps } from '@xyflow/react';
import { canConnect, handleInfo } from './connections';
import { useEditing } from './editing';
import type { DiagramNode, NodeKind } from './protocol';

export type DiagramFlowNode = Node<DiagramNode & Record<string, unknown>, NodeKind>;

// Handle ids must match the server (Diagram.java): blocks have `in:<port>` and
// `out:<port>`, all other nodes have `in` and `out`.

function nodeClasses(base: string, data: DiagramNode): string {
  const classes = [base];
  if (data.inherited) classes.push(`${base}--inherited`);
  if (data.inlined) classes.push(`${base}--inlined`);
  if (data.error) classes.push(`${base}--error`);
  return classes.join(' ');
}

/** The handle a connection is being dragged from, or null. */
function useConnectingFrom(): { node: DiagramNode; handle: string | null } | null {
  return useConnection<DiagramFlowNode, { node: DiagramNode; handle: string | null } | null>((c) =>
    c.inProgress ? { node: c.fromNode.data, handle: c.fromHandle.id ?? null } : null,
  );
}

/**
 * A connection handle. It can start or end a new connection when the canvas
 * allows editing and the handle is free; while a connection is being dragged,
 * every handle it could end at is marked valid (green).
 */
function ConnectionHandle({
  data,
  id,
  side,
  canvasConnectable,
  className,
  style,
  title,
}: {
  data: DiagramNode;
  id: string;
  side: 'in' | 'out';
  canvasConnectable: boolean;
  className: string;
  style?: React.CSSProperties;
  title?: string;
}) {
  const from = useConnectingFrom();
  const editing = useEditing();
  const [hover, setHover] = useState(false);
  const info = handleInfo(data, id);
  const connectable = canvasConnectable && !!info?.connectable;
  const classes = [className];

  // Intercept mode: block ports of the phase's kind can be clicked.
  const pick = editing.interceptPick;
  const pickable = !!pick && data.kind === 'block' && (pick.phase === 'input' ? side === 'in' : side === 'out');
  if (pick) {
    const chosen = pick.into?.node === data.id && pick.into.handle === id;
    if (chosen) classes.push('handle--from');
    if (pickable) {
      const fits = info?.typeName === pick.typeName && (side === 'out' || !!info?.connectable);
      classes.push('handle--pickable', fits ? 'handle--valid' : 'handle--unfit');
    }
  }
  if (connectable) classes.push('handle--free');
  // A literal dragged from the palette can be dropped on any free input.
  if (editing.draggingLiteral && connectable && info?.role === 'target') classes.push('handle--valid');
  if (from) {
    const self = from.node.id === data.id && from.handle === id;
    if (self) classes.push('handle--from');
    else if (canConnect(from, { node: data, handle: id })) classes.push('handle--valid');
  }
  return (
    <Handle
      id={id}
      type={side === 'in' ? 'target' : 'source'}
      position={side === 'in' ? Position.Left : Position.Right}
      className={classes.join(' ')}
      style={style}
      aria-label={title}
      isConnectable={connectable && !pick}
      // A port that cannot be connected still gets the mouse (for its label), so
      // it must not start or end a connection either.
      isConnectableStart={connectable && !pick}
      isConnectableEnd={connectable && !pick}
      onMouseEnter={title ? () => setHover(true) : undefined}
      onMouseLeave={title ? () => setHover(false) : undefined}
      // Safari starts selecting text when a drag begins on the page, which
      // turned the whole window blue while a connection was dragged.
      onMouseDown={connectable ? (e) => e.preventDefault() : undefined}
      onClick={
        pickable
          ? (e) => {
              e.stopPropagation();
              editing.pickPort({ node: data.id, handle: id });
            }
          : undefined
      }
    >
      {/* As in the old editor: "name: Type" beside the port, while the mouse is on it. */}
      {hover && title && (
        <span className={`port-tip port-tip--${side}`} role="tooltip">
          {title}
        </span>
      )}
    </Handle>
  );
}

/** Handles at the middle of the left (in) and right (out) edges, shown on hover when usable. */
function EdgeHandles({ data, isConnectable }: { data: DiagramNode; isConnectable: boolean }) {
  return (
    <>
      <ConnectionHandle data={data} id="in" side="in" canvasConnectable={isConnectable} className="node-handle node-handle--in" />
      <ConnectionHandle data={data} id="out" side="out" canvasConnectable={isConnectable} className="node-handle node-handle--out" />
    </>
  );
}

/** A × that deletes a node (with what belongs to it); see DeleteButton for connections. */
function NodeDeleteButton({ label, className, onClick }: { label: string; className: string; onClick: () => void }) {
  return (
    <button
      type="button"
      className={`delete-button ${className} nodrag nopan`}
      title={`${label} (Delete)`}
      aria-label={label}
      // Keep React Flow from treating the press as a click on the canvas, which
      // would clear the selection (and remove this button) before the click.
      onPointerDown={(e) => e.stopPropagation()}
      onMouseDown={(e) => e.stopPropagation()}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
    >
      ×
    </button>
  );
}

/**
 * A block: rounded body with its ports as 6×6 squares just outside the left
 * and right edges. When selected and deletable, a × below it deletes it.
 */
export function BlockNode({ data, isConnectable, selected }: NodeProps<DiagramFlowNode>) {
  const editing = useEditing();
  return (
    <>
      <div className={nodeClasses('block', data)} title={`${data.id}: ${data.typeName}`}>
        <span className="node__label">{data.label}</span>
      </div>
      {selected && editing.editable && data.deletable && (
        <NodeDeleteButton
          label="Delete block"
          className="delete-button--block"
          onClick={() => editing.deleteItems({ nodes: [data.id] })}
        />
      )}
      {data.inPorts.map((p) => (
        <ConnectionHandle
          key={`in:${p.name}`}
          data={data}
          id={`in:${p.name}`}
          side="in"
          canvasConnectable={isConnectable}
          className="port port--in"
          style={{ top: p.y }}
          title={`${p.name}: ${p.typeName}`}
        />
      ))}
      {data.outPorts.map((p) => (
        <ConnectionHandle
          key={`out:${p.name}`}
          data={data}
          id={`out:${p.name}`}
          side="out"
          canvasConnectable={isConnectable}
          className="port port--out"
          style={{ top: p.y }}
          title={`${p.name}: ${p.typeName}`}
        />
      ))}
    </>
  );
}

/** In-parameter: box with a triangle pointing right (into the diagram). */
export function InParameterNode({ data, isConnectable, selected }: NodeProps<DiagramFlowNode>) {
  const editing = useEditing();
  return (
    <>
    <div className={nodeClasses('parameter', data) + ' parameter--in'} title={`in ${data.id}: ${data.typeName}`}>
      <span className="node__label">{data.label}</span>
      <span className="parameter__marker" aria-hidden="true" />
      <EdgeHandles data={data} isConnectable={isConnectable} />
    </div>
    {selected && editing.editable && data.deletable && (
      <NodeDeleteButton
        label="Delete parameter"
        className="delete-button--block"
        onClick={() => editing.deleteItems({ nodes: [data.id] })}
      />
    )}
    </>
  );
}

/** Out-parameter: box with a bar on the left (where connections arrive). */
export function OutParameterNode({ data, isConnectable, selected }: NodeProps<DiagramFlowNode>) {
  const editing = useEditing();
  return (
    <>
    <div className={nodeClasses('parameter', data) + ' parameter--out'} title={`out ${data.id}: ${data.typeName}`}>
      <span className="parameter__marker" aria-hidden="true" />
      <span className="node__label">{data.label}</span>
      <EdgeHandles data={data} isConnectable={isConnectable} />
    </div>
    {selected && editing.editable && data.deletable && (
      <NodeDeleteButton
        label="Delete parameter"
        className="delete-button--block"
        onClick={() => editing.deleteItems({ nodes: [data.id] })}
      />
    )}
    </>
  );
}

/** Variable: plain box labelled `kind name`. */
export function VariableNode({ data, isConnectable, selected }: NodeProps<DiagramFlowNode>) {
  const editing = useEditing();
  return (
    <>
    <div className={nodeClasses('variable', data)} title={`${data.id}: ${data.typeName}`}>
      <span className="node__label">{data.label}</span>
      <EdgeHandles data={data} isConnectable={isConnectable} />
    </div>
    {selected && editing.editable && data.deletable && (
      <NodeDeleteButton
        label="Delete variable"
        className="delete-button--block"
        onClick={() => editing.deleteItems({ nodes: [data.id] })}
      />
    )}
    </>
  );
}

/**
 * Literal: just its text, left of the port it feeds. Its handle is never
 * connectable. When selected, a × below it deletes its connection.
 */
export function LiteralNode({ data, selected }: NodeProps<DiagramFlowNode>) {
  const editing = useEditing();
  const connection = editing.literalConnection(data.id);
  return (
    <div className="literal" title={`${data.label}: ${data.typeName}`}>
      {data.label}
      <EdgeHandles data={data} isConnectable={false} />
      {selected && editing.editable && connection && (
        <NodeDeleteButton
          label="Delete literal"
          className="delete-button--literal"
          onClick={() => editing.deleteItems({ edges: [connection] })}
        />
      )}
    </div>
  );
}

export const nodeTypes = {
  block: BlockNode,
  inParameter: InParameterNode,
  outParameter: OutParameterNode,
  variable: VariableNode,
  literal: LiteralNode,
} satisfies Record<NodeKind, unknown>;
