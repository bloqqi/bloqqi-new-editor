// What the right-click menus (spec §3.7) and the toolbar's New ▾ menu offer.
// Items that do not apply are disabled, with the reason as their tooltip.

import type { MenuItem } from './ContextMenu';
import type { FlowEdgeType } from './FlowEdge';
import type { DiagramFlowNode } from './nodes';
import type { DiagramMessage } from './protocol';

export interface MenuContext {
  diagram: DiagramMessage;
  /** The diagram can be edited (connected, not read-only). */
  editable: boolean;
  /** Literal id -> the deletable connection it feeds. */
  literalConnections: Map<string, string>;
  editNode: (node: DiagramFlowNode) => void;
  deleteItems: (items: { edges?: string[]; nodes?: string[] }) => void;
  open: (file: string, diagramType?: string) => void;
  autoLayout: () => void;
}

/** Why nothing can be edited, or null if it can. */
function readOnlyReason(c: MenuContext): string | null {
  return c.editable ? null : c.diagram.readOnly ?? 'Not connected';
}

/** Blocks: Rename…, Open <Type>, Delete; parameters and variables: Rename…, Delete; literals: Change value…, Delete. */
export function nodeMenu(node: DiagramFlowNode, c: MenuContext): MenuItem[] {
  const readOnly = readOnlyReason(c);
  if (node.type === 'literal') {
    const id = c.literalConnections.get(node.id);
    const reason = readOnly ?? (id ? null : 'Inherited');
    return [
      { label: 'Change value…', disabledReason: reason, onSelect: () => c.editNode(node) },
      { label: 'Delete', shortcut: 'Del', disabledReason: reason, onSelect: () => id && c.deleteItems({ edges: [id] }) },
    ];
  }
  const notHere = node.data.inherited ? 'Inherited' : node.data.inlined ? 'Inside an inlined block' : 'Not declared here';
  const reason = readOnly ?? (node.data.deletable ? null : notHere);
  const items: MenuItem[] = [
    { label: 'Rename…', shortcut: 'F2', disabledReason: reason, onSelect: () => c.editNode(node) },
  ];
  if (node.type === 'block') {
    const typeName = node.data.typeName;
    const local = c.diagram.diagramTypes.includes(typeName);
    items.push({
      label: `Open ${typeName}`,
      disabledReason: local ? null : `${typeName} is not a diagram type in ${c.diagram.file}`,
      onSelect: () => c.open(c.diagram.file, typeName),
    });
  }
  items.push({
    label: 'Delete',
    shortcut: 'Del',
    disabledReason: reason,
    onSelect: () => c.deleteItems({ nodes: [node.id] }),
  });
  return items;
}

/** Connections: Delete. */
export function edgeMenu(edge: FlowEdgeType, c: MenuContext): MenuItem[] {
  const reason =
    readOnlyReason(c) ?? (edge.data?.deletable ? null : edge.data?.inherited ? 'Inherited' : 'Not declared here');
  return [{ label: 'Delete', shortcut: 'Del', disabledReason: reason, onSelect: () => c.deleteItems({ edges: [edge.id] }) }];
}

/** The empty canvas: Auto layout. */
export function paneMenu(c: MenuContext): MenuItem[] {
  return [{ label: 'Auto layout', disabledReason: readOnlyReason(c), onSelect: c.autoLayout }];
}

/** The toolbar's New ▾ menu. */
export function newMenu(actions: { newDiagramType: () => void; newExternalFunction: () => void }): MenuItem[] {
  return [
    { label: 'Diagram type…', onSelect: actions.newDiagramType },
    { label: 'External function…', onSelect: actions.newExternalFunction },
  ];
}
