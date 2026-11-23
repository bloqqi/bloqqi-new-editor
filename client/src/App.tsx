import { useCallback, useMemo, useRef, useState } from 'react';
import { Canvas } from './Canvas';
import { Dialogs } from './Dialogs';
import { EditingContext, type Editing } from './editing';
import type { FlowEdgeType } from './FlowEdge';
import { edgeMenu, newMenu, nodeMenu, paneMenu, type MenuContext } from './menus';
import type { DiagramFlowNode } from './nodes';
import { Palette } from './Palette';
import { useRenameKey, useUndoRedoKeys } from './shortcuts';
import { Toolbar } from './Toolbar';
import { useDiagramFlow } from './useDiagramFlow';
import { useDialogs } from './useDialogs';
import { useServer } from './useServer';

/**
 * The editor: the toolbar, the palette, the canvas with its menus and forms,
 * and the problems of the file. The parts:
 * - useServer: the WebSocket, the shown diagram, undo/redo
 * - useDiagramFlow: the canvas' nodes and edges, and edits made on it directly
 * - useDialogs (rendered by Dialogs): the menu and the forms
 * - menus: what the menus offer
 */
export function App() {
  // The server tells the open form whether its submission was accepted; the
  // dialogs are created after the server (they need its send), hence the ref.
  const formAnswered = useRef<(error: string | null) => void>(() => {});
  const server = useServer(useCallback((error: string | null) => formAnswered.current(error), []));
  const { diagram, status, send, open } = server;
  const flow = useDiagramFlow(diagram, send);

  /** The shown diagram can be edited. */
  const editable = status === 'open' && !!diagram?.diagramType && !diagram.readOnly;
  /** The file can get new types: connected, and the file has no syntax errors. */
  const fileEditable = status === 'open' && !!diagram && !diagram.readOnly;

  const dialogs = useDialogs({
    diagram,
    editable,
    send,
    literalConnections: flow.literalConnections,
    setError: server.setError,
  });
  formAnswered.current = dialogs.formAnswered;

  useUndoRedoKeys(server.undo, server.redo);
  useRenameKey(flow.nodes, dialogs.editNode);

  // Which diagram (file/type) has had its initial fit; the canvas exposes it as
  // data-fitted so that tests can wait until the view stops moving.
  const diagramKey = `${diagram?.file}/${diagram?.diagramType}`;
  const [fittedKey, setFittedKey] = useState<string | null>(null);
  const onFitted = useCallback(() => setFittedKey(diagramKey), [diagramKey]);

  // The right-click menus.
  const menuContext = useMemo<MenuContext | null>(
    () =>
      diagram && {
        diagram,
        editable,
        literalConnections: flow.literalConnections,
        editNode: dialogs.editNode,
        deleteItems: flow.deleteItems,
        open,
        autoLayout: () => {
          if (diagram.diagramType) send({ type: 'autoLayout', file: diagram.file, diagramType: diagram.diagramType });
        },
      },
    [diagram, editable, flow.literalConnections, flow.deleteItems, dialogs.editNode, open, send],
  );
  const { openMenu } = dialogs;
  const onNodeContextMenu = useCallback(
    (e: React.MouseEvent, node: DiagramFlowNode) => {
      e.preventDefault();
      if (menuContext) openMenu({ x: e.clientX, y: e.clientY }, nodeMenu(node, menuContext));
    },
    [menuContext, openMenu],
  );
  const onEdgeContextMenu = useCallback(
    (e: React.MouseEvent, edge: FlowEdgeType) => {
      e.preventDefault();
      if (menuContext) openMenu({ x: e.clientX, y: e.clientY }, edgeMenu(edge, menuContext));
    },
    [menuContext, openMenu],
  );
  const onPaneContextMenu = useCallback(
    (e: MouseEvent | React.MouseEvent) => {
      e.preventDefault();
      if (menuContext?.diagram.diagramType) openMenu({ x: e.clientX, y: e.clientY }, paneMenu(menuContext));
    },
    [menuContext, openMenu],
  );
  const onNodeDoubleClick = useCallback(
    (_e: React.MouseEvent, node: DiagramFlowNode) => dialogs.editNode(node),
    [dialogs.editNode],
  );

  /** The toolbar's New ▾ menu, below the button. */
  const onNew = useCallback(
    (e: React.MouseEvent<HTMLButtonElement>) => {
      const r = e.currentTarget.getBoundingClientRect();
      const below = { x: r.left, y: r.bottom + 4 };
      openMenu(
        below,
        newMenu({
          newDiagramType: () => dialogs.newDiagramType(below),
          newExternalFunction: () => dialogs.newExternalFunction(below),
        }),
      );
    },
    [openMenu, dialogs.newDiagramType, dialogs.newExternalFunction],
  );

  const editing = useMemo<Editing>(
    () => ({
      editable,
      deleteItems: flow.deleteItems,
      literalConnection: (literalId) => flow.literalConnections.get(literalId) ?? null,
      draggingLiteral: dialogs.draggingLiteral,
    }),
    [editable, flow.deleteItems, flow.literalConnections, dialogs.draggingLiteral],
  );

  return (
    <div className="app">
      <Toolbar server={server} fileEditable={fileEditable} onNew={onNew} />

      {server.error && (
        <div className="banner banner--error" role="alert">
          {server.error}
        </div>
      )}
      {diagram?.diagramType && diagram.readOnly && (
        <div className="banner banner--info" data-testid="read-only">
          Read-only: {diagram.readOnly}
        </div>
      )}

      <div className="workarea">
        {diagram?.diagramType && (
          <Palette
            entries={diagram.palette}
            editable={editable}
            onDragTool={(tool) => dialogs.setDraggingLiteral(tool === 'literal')}
          />
        )}
        <main className="canvas" data-fitted={fittedKey === diagramKey}>
          {diagram && !diagram.diagramType && (
            <p className="empty">
              {diagram.problems.length > 0
                ? `${diagram.file} could not be parsed.`
                : `${diagram.file} has no diagram types.`}
            </p>
          )}
          <EditingContext.Provider value={editing}>
            <Canvas
              key={diagramKey}
              diagram={diagram}
              nodes={flow.nodes}
              edges={flow.edges}
              editable={editable}
              onNodesChange={flow.onNodesChange}
              onEdgesChange={flow.onEdgesChange}
              onNodeDragStop={flow.onNodeDragStop}
              onConnect={flow.onConnect}
              isValidConnection={flow.isValidConnection}
              onDelete={flow.onDelete}
              onAddBlock={flow.addBlock}
              onTool={dialogs.onTool}
              onNodeContextMenu={onNodeContextMenu}
              onEdgeContextMenu={onEdgeContextMenu}
              onPaneContextMenu={onPaneContextMenu}
              onNodeDoubleClick={onNodeDoubleClick}
              onFitted={onFitted}
            />
          </EditingContext.Provider>
        </main>
        <Dialogs dialogs={dialogs} busy={server.pendingCount > 0} />
      </div>

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
