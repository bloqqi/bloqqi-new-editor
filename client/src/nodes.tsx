import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
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

/** Invisible handles at the middle of the left and right edges. */
function EdgeHandles() {
  return (
    <>
      <Handle type="target" position={Position.Left} id="in" className="handle--hidden" isConnectable={false} />
      <Handle type="source" position={Position.Right} id="out" className="handle--hidden" isConnectable={false} />
    </>
  );
}

/** A block: rounded body with its ports as 6×6 squares just outside the left and right edges. */
export function BlockNode({ data }: NodeProps<DiagramFlowNode>) {
  return (
    <>
      <div className={nodeClasses('block', data)} title={`${data.id}: ${data.typeName}`}>
        <span className="node__label">{data.label}</span>
      </div>
      {data.inPorts.map((p) => (
        <Handle
          key={`in:${p.name}`}
          id={`in:${p.name}`}
          type="target"
          position={Position.Left}
          className="port port--in"
          style={{ top: p.y }}
          title={`${p.name}: ${p.typeName}`}
          isConnectable={false}
        />
      ))}
      {data.outPorts.map((p) => (
        <Handle
          key={`out:${p.name}`}
          id={`out:${p.name}`}
          type="source"
          position={Position.Right}
          className="port port--out"
          style={{ top: p.y }}
          title={`${p.name}: ${p.typeName}`}
          isConnectable={false}
        />
      ))}
    </>
  );
}

/** In-parameter: box with a triangle pointing right (into the diagram). */
export function InParameterNode({ data }: NodeProps<DiagramFlowNode>) {
  return (
    <div className={nodeClasses('parameter', data) + ' parameter--in'} title={`in ${data.id}: ${data.typeName}`}>
      <span className="node__label">{data.label}</span>
      <span className="parameter__marker" aria-hidden="true" />
      <EdgeHandles />
    </div>
  );
}

/** Out-parameter: box with a bar on the left (where connections arrive). */
export function OutParameterNode({ data }: NodeProps<DiagramFlowNode>) {
  return (
    <div className={nodeClasses('parameter', data) + ' parameter--out'} title={`out ${data.id}: ${data.typeName}`}>
      <span className="parameter__marker" aria-hidden="true" />
      <span className="node__label">{data.label}</span>
      <EdgeHandles />
    </div>
  );
}

/** Variable: plain box labelled `kind name`. */
export function VariableNode({ data }: NodeProps<DiagramFlowNode>) {
  return (
    <div className={nodeClasses('variable', data)} title={`${data.id}: ${data.typeName}`}>
      <span className="node__label">{data.label}</span>
      <EdgeHandles />
    </div>
  );
}

/** Literal: just its text, left of the port it feeds. */
export function LiteralNode({ data }: NodeProps<DiagramFlowNode>) {
  return (
    <div className="literal" title={`${data.label}: ${data.typeName}`}>
      {data.label}
      <EdgeHandles />
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
