import { BaseEdge, EdgeLabelRenderer, type Edge, type EdgeProps } from '@xyflow/react';
import { useEditing } from './editing';
import type { DiagramEdge } from './protocol';

export type FlowEdgeType = Edge<DiagramEdge & Record<string, unknown>, 'flow'>;

type Point = [number, number];

/** Horizontal distance a line runs straight out of a port before turning. */
const STUB = 10;

/**
 * Manhattan route from source (exits rightwards) to target (enters from the
 * left), like the old editor's ManhattanConnectionRouter: a jog halfway between
 * the two ends. Backward connections (target left of source) go around: out,
 * down/up to the midline, back, and in.
 */
export function route(sx: number, sy: number, tx: number, ty: number): Point[] {
  if (Math.abs(sy - ty) < 0.5 && tx >= sx) {
    return [
      [sx, sy],
      [tx, ty],
    ];
  }
  if (tx >= sx) {
    const mx = Math.round((sx + tx) / 2);
    return [
      [sx, sy],
      [mx, sy],
      [mx, ty],
      [tx, ty],
    ];
  }
  const my = Math.abs(sy - ty) < 20 ? Math.max(sy, ty) + 30 : Math.round((sy + ty) / 2);
  return [
    [sx, sy],
    [sx + STUB, sy],
    [sx + STUB, my],
    [tx - STUB, my],
    [tx - STUB, ty],
    [tx, ty],
  ];
}

/** Splits a polyline at half its length; returns both halves and the midpoint. */
export function splitAtMiddle(points: Point[]): { first: Point[]; second: Point[]; mid: Point } {
  const lengths = points.slice(1).map((p, i) => Math.hypot(p[0] - points[i][0], p[1] - points[i][1]));
  let remaining = lengths.reduce((a, b) => a + b, 0) / 2;
  for (let i = 0; i < lengths.length; i++) {
    if (remaining <= lengths[i] || i === lengths.length - 1) {
      const t = lengths[i] === 0 ? 0 : Math.min(1, remaining / lengths[i]);
      const [ax, ay] = points[i];
      const [bx, by] = points[i + 1];
      const mid: Point = [ax + (bx - ax) * t, ay + (by - ay) * t];
      return { first: [...points.slice(0, i + 1), mid], second: [mid, ...points.slice(i + 1)], mid };
    }
    remaining -= lengths[i];
  }
  return { first: points, second: points, mid: points[0] };
}

function toPath(points: Point[]): string {
  return points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'} ${x} ${y}`).join(' ');
}

/** Line style of a (half) connection, following ConnectionPart.refreshVisuals. */
function lineClass(data: DiagramEdge, inherited: boolean, selected: boolean): string {
  const classes = ['flow'];
  if (data.error) classes.push('flow--error');
  if (inherited) classes.push('flow--inherited');
  if (selected) classes.push('flow--selected');
  return classes.join(' ');
}

/** A × button that deletes one connection, drawn at a point in flow coordinates. */
export function DeleteButton({ x, y, label, onClick }: { x: number; y: number; label: string; onClick: () => void }) {
  return (
    <EdgeLabelRenderer>
      <button
        type="button"
        className="delete-button nodrag nopan"
        style={{ transform: `translate(-50%, -50%) translate(${x}px, ${y}px)` }}
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
    </EdgeLabelRenderer>
  );
}

export function FlowEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  data,
  markerEnd,
  selected,
}: EdgeProps<FlowEdgeType>) {
  const editing = useEditing();
  const isSelected = !!selected;
  if (!data) return null;
  const points = route(sourceX, sourceY, targetX, targetY);

  // A selected connection gets a halo behind it (not a thicker line: arrow
  // heads are sized in units of the line width).
  const halo = isSelected ? <path d={toPath(points)} className="flow__halo" /> : null;
  let deleteButton = null;
  if (isSelected && data.deletable && editing.editable) {
    const [mx, my] = splitAtMiddle(points).mid;
    deleteButton = (
      <DeleteButton x={mx} y={my} label="Delete connection" onClick={() => editing.deleteItems({ edges: [id] })} />
    );
  }

  if (!data.intercept || data.inherited) {
    return (
      <>
        {halo}
        <BaseEdge
          id={id}
          path={toPath(points)}
          markerEnd={markerEnd}
          className={lineClass(data, data.inherited, isSelected)}
        />
        {deleteButton}
      </>
    );
  }

  // An interception half: the part that stands for the original, inherited
  // connection is dashed; the new part is drawn normally.
  const { first, second, mid } = splitAtMiddle(points);
  const firstInherited = data.intercept === 'first';
  const size = data.largeMarker ? 8 : 4;
  return (
    <g data-intercept={data.intercept}>
      {halo}
      <BaseEdge id={`${id}:1`} path={toPath(first)} className={lineClass(data, firstInherited, isSelected)} />
      <BaseEdge
        id={id}
        path={toPath(second)}
        markerEnd={markerEnd}
        className={lineClass(data, !firstInherited, isSelected)}
      />
      <rect className="flow__marker" x={mid[0] - size / 2} y={mid[1] - size / 2} width={size} height={size} />
      {deleteButton}
    </g>
  );
}

export const edgeTypes = { flow: FlowEdge };
