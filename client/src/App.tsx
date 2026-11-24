import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Canvas } from './Canvas';
import { Dialogs } from './Dialogs';
import { EditingContext, type Editing } from './editing';
import type { FlowEdgeType } from './FlowEdge';
import { edgeMenu, fileMenu, newMenu, nodeMenu, paletteMenu, paneMenu, type MenuContext } from './menus';
import type { PaletteEntry } from './protocol';
import type { DiagramFlowNode } from './nodes';
import { InterceptMode } from './InterceptMode';
import { handleInfo } from './connections';
import { Palette } from './Palette';
import { useRenameKey, useUndoRedoKeys } from './shortcuts';
import type { TextViewHandle } from './TextView';
import { Toolbar, type ViewMode } from './Toolbar';
import { UnsavedTextForm } from './UnsavedTextForm';
import { useDiagramFlow } from './useDiagramFlow';
import { useDialogs } from './useDialogs';
import { historyDepth as currentHistoryDepth, useServer } from './useServer';

/** The text and C views, with their editor (CodeMirror), are loaded when first shown. */
const TextView = lazy(() => import('./TextView').then((m) => ({ default: m.TextView })));
const CView = lazy(() => import('./CView').then((m) => ({ default: m.CView })));

/** The view mode in the address bar (`view=text`, `view=c`), so that a reload keeps it. */
function urlView(): ViewMode {
  const view = new URLSearchParams(window.location.search).get('view');
  return view === 'text' || view === 'c' ? view : 'diagram';
}

function setUrlView(view: ViewMode) {
  const params = new URLSearchParams(window.location.search);
  if (view !== 'diagram') params.set('view', view);
  else params.delete('view');
  window.history.replaceState(window.history.state, '', `?${params}`);
}

/**
 * The editor: the toolbar, the palette, the canvas with its menus and forms,
 * and the problems of the file; or, in the text view, the file's text; or, in
 * the C view, the C code the compiler generates for it. The parts:
 * - useServer: the WebSocket, the shown diagram, undo/redo
 * - useDiagramFlow: the canvas' nodes and edges, and edits made on it directly
 * - useDialogs (rendered by Dialogs): the menu and the forms
 * - menus: what the menus offer
 * - TextView: the whole file in a text editor, saved explicitly
 * - CView: the generated C code (read-only)
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
    request: server.request,
    literalConnections: flow.literalConnections,
    setError: server.setError,
  });
  formAnswered.current = dialogs.formAnswered;

  // The text view. Leaving it (to the diagram or another file) with unsaved
  // changes first asks whether to save or discard them.
  const [view, setView] = useState<ViewMode>(urlView);
  const textView = useRef<TextViewHandle>(null);
  const [textDirty, setTextDirty] = useState(false);
  /** Leaving the text view is waiting for Save or Discard; `cancel` undoes the attempt (if needed). */
  const [leaving, setLeaving] = useState<{ action: () => void; cancel?: () => void } | null>(null);
  const [savingToLeave, setSavingToLeave] = useState(false);
  const changeView = useCallback((next: ViewMode) => {
    setView(next);
    setUrlView(next);
  }, []);
  const unlessUnsaved = useCallback(
    (action: () => void) => {
      if (view === 'text' && textDirty) setLeaving({ action });
      else action();
    },
    [view, textDirty],
  );

  // Back/Forward in the browser's history (or the toolbar's ←): show that entry's
  // file, type and view. The address has already changed, so if unsaved text
  // makes the user cancel, the browser goes back to where it was.
  const { historyDepth, syncHistoryDepth } = server;
  useEffect(() => {
    const onPopState = () => {
      const params = new URLSearchParams(window.location.search);
      const file = params.get('file');
      if (!file) return;
      const before = historyDepth;
      syncHistoryDepth();
      const target = urlView();
      const go = () => {
        setView(target);
        open(file, params.get('type') ?? undefined, false);
      };
      if (view === 'text' && textDirty && (target !== 'text' || file !== diagram?.file)) {
        const cameBack = currentHistoryDepth() < before;
        setLeaving({ action: go, cancel: () => (cameBack ? window.history.forward() : window.history.back()) });
      } else {
        go();
      }
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [view, textDirty, diagram?.file, open, historyDepth, syncHistoryDepth]);

  // File undo/redo, not while the text has unsaved changes (the editor has its own).
  const undo = useCallback(() => !textDirty && server.undo(), [textDirty, server.undo]);
  const redo = useCallback(() => !textDirty && server.redo(), [textDirty, server.redo]);
  useUndoRedoKeys(undo, redo);
  useRenameKey(flow.nodes, dialogs.editNode);

  // Which diagram (file/type) has had its initial fit; the canvas exposes it as
  // data-fitted so that tests can wait until the view stops moving.
  const diagramKey = `${diagram?.file}/${diagram?.diagramType}`;
  const [fittedKey, setFittedKey] = useState<string | null>(null);
  const onFitted = useCallback(() => setFittedKey(diagramKey), [diagramKey]);

  // Adding a block from the palette. If its type has optional features, ask
  // whether to select them (as the old editor did).
  const { askFeatures } = dialogs;
  const addBlock = useCallback(
    async (typeName: string, x: number, y: number, screen: { x: number; y: number }) => {
      if (!diagram?.diagramType) return;
      const before = new Set(diagram.nodes.map((n) => n.id));
      const answer = await server.request({
        type: 'addBlock',
        file: diagram.file,
        diagramType: diagram.diagramType,
        typeName,
        x,
        y,
      });
      if (answer.type !== 'diagram') return;
      const added = answer.nodes.find((n) => n.kind === 'block' && !before.has(n.id) && n.featureBase);
      if (added?.featureBase) askFeatures(added.id, added.featureBase, screen);
    },
    [diagram, server.request, askFeatures],
  );

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
          if (diagram.diagramType)
            send({
              type: 'autoLayout',
              file: diagram.file,
              diagramType: diagram.diagramType,
            });
        },
        fileEditable,
        renameType: dialogs.renameType,
        newSubtype: dialogs.newSubtype,
        editSuperTypes: dialogs.editSuperTypes,
        editParameters: dialogs.editParameters,
        editFeatures: dialogs.editFeatures,
        // Answered with the shown type, or the first one if the shown one was deleted.
        intercept: dialogs.intercept,
        deleteType: (typeName) =>
          send({
            type: 'deleteType',
            file: diagram.file,
            diagramType: diagram.diagramType,
            typeName,
          }),
      },
    [diagram, editable, fileEditable, flow.literalConnections, flow.deleteItems, dialogs, open, send],
  );
  const { openMenu } = dialogs;
  const onNodeContextMenu = useCallback(
    (e: React.MouseEvent, node: DiagramFlowNode) => {
      e.preventDefault();
      if (menuContext) {
        const at = { x: e.clientX, y: e.clientY };
        openMenu(at, nodeMenu(node, menuContext, at));
      }
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
      const at = { x: e.clientX, y: e.clientY };
      if (menuContext?.diagram.diagramType) openMenu(at, paneMenu(menuContext, at));
    },
    [menuContext, openMenu],
  );
  const onPaletteContextMenu = useCallback(
    (e: React.MouseEvent, entry: PaletteEntry) => {
      e.preventDefault();
      const at = { x: e.clientX, y: e.clientY };
      if (menuContext) openMenu(at, paletteMenu(entry, menuContext, at));
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

  /** The toolbar's File ▾ menu, below the button. */
  const onFileMenu = useCallback(
    (e: React.MouseEvent<HTMLButtonElement>) => {
      const r = e.currentTarget.getBoundingClientRect();
      const below = { x: r.left, y: r.bottom + 4 };
      openMenu(
        below,
        fileMenu(diagram?.file ?? null, status === 'open', {
          newFile: () => dialogs.newFile(below),
          renameFile: () => dialogs.renameFile(below),
          deleteFile: () => dialogs.deleteFile(below),
        }),
      );
    },
    [openMenu, diagram, status, dialogs],
  );

  // Intercept mode: the connection, and its type (that of its source).
  const pick = dialogs.interceptPick;
  const pickEdge = pick ? diagram?.edges.find((e) => e.id === pick.edge) : undefined;
  const pickType = useMemo(() => {
    const source = pickEdge && diagram?.nodes.find((n) => n.id === pickEdge.source);
    return source ? handleInfo(source, pickEdge.sourceHandle)?.typeName : undefined;
  }, [pickEdge, diagram]);

  const editing = useMemo<Editing>(
    () => ({
      editable,
      deleteItems: flow.deleteItems,
      literalConnection: (literalId) => flow.literalConnections.get(literalId) ?? null,
      draggingLiteral: dialogs.draggingLiteral,
      interceptPick: pick && {
        phase: pick.into ? 'output' : 'input',
        typeName: pickType,
        into: pick.into,
      },
      pickPort: dialogs.pickPort,
    }),
    [editable, flow.deleteItems, flow.literalConnections, dialogs.draggingLiteral, pick, pickType, dialogs.pickPort],
  );

  return (
    <div className="app">
      <Toolbar
        server={server}
        fileEditable={fileEditable}
        view={view}
        textDirty={textDirty}
        onView={(next) => next !== view && unlessUnsaved(() => changeView(next))}
        onOpenFile={(file) => unlessUnsaved(() => open(file))}
        canGoBack={historyDepth > 0}
        onBack={() => window.history.back()}
        onNew={onNew}
        onFileMenu={onFileMenu}
      />
      {leaving && diagram && (
        <UnsavedTextForm
          file={diagram.file}
          busy={savingToLeave}
          onSave={async () => {
            setSavingToLeave(true);
            const saved = (await textView.current?.save()) ?? true;
            setSavingToLeave(false);
            setLeaving(null);
            if (saved) leaving.action();
            else leaving.cancel?.();
          }}
          onDiscard={() => {
            setLeaving(null);
            setTextDirty(false);
            leaving.action();
          }}
          onCancel={() => {
            setLeaving(null);
            leaving.cancel?.();
          }}
        />
      )}

      {server.error && (
        <div className="banner banner--error" role="alert">
          {server.error}
        </div>
      )}
      {server.notice && (
        <div className="banner banner--info" role="status" data-testid="notice">
          {server.notice}
        </div>
      )}
      {pick && pickEdge && (
        <InterceptMode edge={pickEdge} atSource={pick.atSource} into={pick.into} onCancel={dialogs.cancelIntercept} />
      )}
      {view === 'diagram' && diagram?.diagramType && diagram.readOnly && (
        <div className="banner banner--info" data-testid="read-only">
          Read-only: {diagram.readOnly}
        </div>
      )}

      {view === 'text' && diagram && (
        <div className="workarea">
          <main className="text-main">
            <Suspense fallback={<p className="empty">Loading the text editor…</p>}>
              <TextView
                key={diagram.file}
                ref={textView}
                file={diagram.file}
                text={diagram.text}
                diagramType={diagram.diagramType}
                problems={diagram.problems}
                editable={status === 'open'}
                request={server.request}
                onDirtyChange={setTextDirty}
              />
            </Suspense>
          </main>
          <Dialogs dialogs={dialogs} diagram={diagram} busy={server.pendingCount > 0} />
        </div>
      )}
      {view === 'c' && diagram && (
        <div className="workarea">
          <main className="text-main">
            <Suspense fallback={<p className="empty">Loading…</p>}>
              <CView file={diagram.file} text={diagram.text} request={server.request} />
            </Suspense>
          </main>
        </div>
      )}
      {view === 'diagram' && (
        <div className="workarea">
          {diagram?.diagramType && (
            <Palette
              entries={diagram.palette}
              editable={editable}
              onDragTool={(tool) => dialogs.setDraggingLiteral(tool === 'literal')}
              onEntryContextMenu={onPaletteContextMenu}
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
                onAddBlock={addBlock}
                onTool={dialogs.onTool}
                onNodeContextMenu={onNodeContextMenu}
                onEdgeContextMenu={onEdgeContextMenu}
                onPaneContextMenu={onPaneContextMenu}
                onNodeDoubleClick={onNodeDoubleClick}
                onFitted={onFitted}
              />
            </EditingContext.Provider>
          </main>
          <Dialogs dialogs={dialogs} diagram={diagram} busy={server.pendingCount > 0} />
        </div>
      )}

      {view === 'diagram' && diagram && diagram.problems.length > 0 && (
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
