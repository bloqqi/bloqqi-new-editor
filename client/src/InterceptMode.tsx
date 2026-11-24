import { useEffect, useState } from 'react';
import type { DiagramEdge, Endpoint } from './protocol';

/** How a handle is written: `block.port`, or a parameter's or variable's name. */
export function endpointLabel(node: string, handle: string): string {
  const port = handle.includes(':') ? handle.slice(handle.indexOf(':') + 1) : null;
  return port ? `${node}.${port}` : node;
}

/**
 * The hint shown while intercepting a connection (old editor: InterceptTool),
 * and, once the input port has been clicked, a line from it to the mouse.
 */
export function InterceptMode({
  edge,
  atSource,
  into,
  onCancel,
}: {
  edge: DiagramEdge;
  atSource: boolean;
  into: Endpoint | null;
  onCancel: () => void;
}) {
  const connection = `${endpointLabel(edge.source, edge.sourceHandle)} → ${endpointLabel(edge.target, edge.targetHandle)}`;
  return (
    <>
      <div className="banner banner--pick" role="status" data-testid="intercept-hint">
        <span>
          <strong>
            {atSource ? 'Intercept the source of' : 'Intercept'} {connection}:
          </strong>{' '}
          {into
            ? `now click the output port it comes back out of (${endpointLabel(into.node, into.handle)} → …).`
            : 'click the input port it goes into.'}{' '}
          Green ports fit.
        </span>
        <button type="button" onClick={onCancel}>
          Cancel (Esc)
        </button>
      </div>
      {into && <PickLine from={into} />}
    </>
  );
}

/** A dashed line from a port to the mouse pointer (like a connection being drawn). */
function PickLine({ from }: { from: Endpoint }) {
  const [mouse, setMouse] = useState<{ x: number; y: number } | null>(null);
  useEffect(() => {
    const onMove = (e: MouseEvent) => setMouse({ x: e.clientX, y: e.clientY });
    window.addEventListener('mousemove', onMove);
    return () => window.removeEventListener('mousemove', onMove);
  }, []);
  const el = document.querySelector(
    `.react-flow__node[data-id="${CSS.escape(from.node)}"] .react-flow__handle[data-handleid="${CSS.escape(from.handle)}"]`,
  );
  const r = el?.getBoundingClientRect();
  if (!r || !mouse) return null;
  return (
    <svg className="pick-line" aria-hidden="true">
      <line x1={r.left + r.width / 2} y1={r.top + r.height / 2} x2={mouse.x} y2={mouse.y} />
    </svg>
  );
}
