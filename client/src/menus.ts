// What the right-click menus (spec §3.7) and the toolbar's New ▾ menu offer.
// Items that do not apply are disabled, with the reason as their tooltip.

import type { MenuItem } from './ContextMenu';
import type { FlowEdgeType } from './FlowEdge';
import type { DiagramFlowNode } from './nodes';
import type { DiagramMessage, PaletteEntry } from './protocol';

type Point = { x: number; y: number };

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
  /** The file can get new or changed types (connected, no syntax errors). */
  fileEditable: boolean;
  renameType: (typeName: string, at: Point) => void;
  newSubtype: (superType: string, at: Point) => void;
  editSuperTypes: (at: Point) => void;
  /** The form for the order of the shown type's parameters. */
  editParameters: (at: Point) => void;
  /** The feature selection of a block. */
  editFeatures: (block: string, at: Point) => void;
  deleteType: (typeName: string) => void;
  intercept: (edge: string, atSource: boolean) => void;
}

/** Why nothing can be edited, or null if it can. */
function readOnlyReason(c: MenuContext): string | null {
  return c.editable ? null : (c.diagram.readOnly ?? 'Not connected');
}

/** Blocks: Rename…, Open <Type>, Delete; parameters: Rename…, Parameters…, Delete; variables: Rename…, Delete; literals: Change value…, Delete. */
export function nodeMenu(node: DiagramFlowNode, c: MenuContext, at: Point): MenuItem[] {
  const readOnly = readOnlyReason(c);
  if (node.type === 'literal') {
    const id = c.literalConnections.get(node.id);
    const reason = readOnly ?? (id ? null : 'Inherited');
    return [
      { label: 'Change value…', disabledReason: reason, onSelect: () => c.editNode(node) },
      {
        label: 'Delete',
        shortcut: 'Del',
        disabledReason: reason,
        onSelect: () => id && c.deleteItems({ edges: [id] }),
      },
    ];
  }
  const notHere = node.data.inherited
    ? 'Inherited'
    : node.data.inlined
      ? 'Inside an inlined block'
      : 'Not declared here';
  const reason = readOnly ?? (node.data.deletable ? null : notHere);
  const items: MenuItem[] = [
    { label: 'Rename…', shortcut: 'F2', disabledReason: reason, onSelect: () => c.editNode(node) },
  ];
  if (node.type === 'block') {
    const typeName = node.data.typeName;
    // Its anonymous type (b: B { ... }, shown read-only) or a diagram type of the file.
    const target = node.data.openType;
    items.push({
      label: `Open ${target ?? typeName}`,
      disabledReason: target ? null : `${typeName} is not a diagram type in ${c.diagram.file}`,
      onSelect: () => target && c.open(c.diagram.file, target),
    });
    if (node.data.featureBase) {
      items.push({ label: 'Features…', disabledReason: reason, onSelect: () => c.editFeatures(node.id, at) });
    }
  }
  if (node.type === 'inParameter' || node.type === 'outParameter') {
    items.push({ label: 'Parameters…', disabledReason: readOnly, onSelect: () => c.editParameters(at) });
  }
  items.push({
    label: 'Delete',
    shortcut: 'Del',
    disabledReason: reason,
    onSelect: () => c.deleteItems({ nodes: [node.id] }),
  });
  return items;
}

/** Connections: Intercept, Intercept source port (inherited ones, spec §3.7), Delete. */
export function edgeMenu(edge: FlowEdgeType, c: MenuContext): MenuItem[] {
  const readOnly = readOnlyReason(c);
  const deleteReason =
    readOnly ?? (edge.data?.deletable ? null : edge.data?.inherited ? 'Inherited' : 'Not declared here');
  const interceptReason =
    readOnly ??
    (edge.data?.interceptable
      ? null
      : edge.data?.inherited
        ? 'Inside an inlined block'
        : 'Only inherited connections can be intercepted');
  const sourceReason = interceptReason ?? (edge.data?.fromLiteral ? 'Its source is a literal' : null);
  return [
    { label: 'Intercept', disabledReason: interceptReason, onSelect: () => c.intercept(edge.id, false) },
    { label: 'Intercept source port', disabledReason: sourceReason, onSelect: () => c.intercept(edge.id, true) },
    {
      label: 'Delete',
      shortcut: 'Del',
      disabledReason: deleteReason,
      onSelect: () => c.deleteItems({ edges: [edge.id] }),
    },
  ];
}

/** The empty canvas: Auto layout, and the shown type's Rename…, New subtype…, Super types…, Parameters…, Delete (last). */
export function paneMenu(c: MenuContext, at: Point): MenuItem[] {
  const type = c.diagram.diagramType!;
  const items = typeItems(type, 'diagramType', c, at);
  const del = items.pop()!; // Delete last
  return [
    { label: 'Auto layout', disabledReason: readOnlyReason(c), onSelect: c.autoLayout },
    ...items,
    { label: 'Super types…', disabledReason: fileReason(c), onSelect: () => c.editSuperTypes(at) },
    { label: 'Parameters…', disabledReason: readOnlyReason(c), onSelect: () => c.editParameters(at) },
    del,
  ];
}

/** A type in the palette's "This file" group: Open (diagram types), Rename…, New subtype…, Delete. */
export function paletteMenu(entry: PaletteEntry, c: MenuContext, at: Point): MenuItem[] {
  const items: MenuItem[] = [];
  if (entry.kind === 'diagramType') {
    items.push({ label: `Open ${entry.name}`, onSelect: () => c.open(c.diagram.file, entry.name) });
  }
  return [...items, ...typeItems(entry.name, entry.kind, c, at)];
}

function fileReason(c: MenuContext): string | null {
  return c.fileEditable ? null : (c.diagram.readOnly ?? 'Not connected');
}

function typeItems(name: string, kind: PaletteEntry['kind'], c: MenuContext, at: Point): MenuItem[] {
  const reason = fileReason(c);
  const items: MenuItem[] = [
    { label: `Rename ${name}…`, disabledReason: reason, onSelect: () => c.renameType(name, at) },
  ];
  if (kind === 'diagramType') {
    items.push({ label: `New subtype of ${name}…`, disabledReason: reason, onSelect: () => c.newSubtype(name, at) });
  }
  items.push({ label: `Delete ${name}`, disabledReason: reason, onSelect: () => c.deleteType(name) });
  return items;
}

/** The toolbar's File ▾ menu: New file…, Rename <file>…, Delete <file>…. */
export function fileMenu(
  file: string | null,
  connected: boolean,
  actions: { newFile: () => void; renameFile: () => void; deleteFile: () => void },
): MenuItem[] {
  const reason = !connected ? 'Not connected' : file ? null : 'No file is open';
  return [
    { label: 'New file…', disabledReason: connected ? null : 'Not connected', onSelect: actions.newFile },
    { label: `Rename ${file ?? 'file'}…`, disabledReason: reason, onSelect: actions.renameFile },
    { label: `Delete ${file ?? 'file'}…`, disabledReason: reason, onSelect: actions.deleteFile },
  ];
}

/** The toolbar's New ▾ menu. */
export function newMenu(actions: { newDiagramType: () => void; newExternalFunction: () => void }): MenuItem[] {
  return [
    { label: 'Diagram type…', onSelect: actions.newDiagramType },
    { label: 'External function…', onSelect: actions.newExternalFunction },
  ];
}
