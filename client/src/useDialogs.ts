// The menu and the forms on top of the canvas: which one is open, its error,
// and what submitting it sends to the server.

import { useCallback, useState } from 'react';
import type { FormRequest, FormValues } from './AddForm';
import type { MenuItem } from './ContextMenu';
import { unusedName } from './flow';
import type { DiagramFlowNode } from './nodes';
import type { Tool } from './Palette';
import type { PromptRequest } from './PromptForm';
import type { ClientMessage, DiagramMessage, ParameterSpec } from './protocol';
import type { RequestKind } from './useServer';

type Point = { x: number; y: number };

export interface Dialogs {
  /** The right-click (or New ▾) menu. */
  menu: { screen: Point; items: MenuItem[] } | null;
  /** The form opened by dropping a tool (literal, variable, parameter). */
  addForm: FormRequest | null;
  /** The one-field form: Rename…, Change value…, New diagram type. */
  prompt: PromptRequest | null;
  /** The form for a new external function (where it opens). */
  externalForm: Point | null;
  /** The form for the shown type's super types (where it opens). */
  superTypesForm: Point | null;
  /** The server's reason why the open form's last submission was refused. */
  formError: string | null;
  /** A literal is being dragged from the palette. */
  draggingLiteral: boolean;
  setDraggingLiteral: (dragging: boolean) => void;

  openMenu: (screen: Point, items: MenuItem[]) => void;
  closeMenu: () => void;
  /** A tool was dropped: open its form (a literal needs a free input port to feed). */
  onTool: (request: Omit<FormRequest, 'suggestedName'>) => void;
  /** Rename… (or, for a literal, Change value…) next to a node, if it can be edited. */
  editNode: (node: DiagramFlowNode) => void;
  newDiagramType: (screen: Point) => void;
  newExternalFunction: (screen: Point) => void;
  renameType: (typeName: string, screen: Point) => void;
  newSubtype: (superType: string, screen: Point) => void;
  editSuperTypes: (screen: Point) => void;
  submitAddForm: (values: FormValues) => void;
  submitPrompt: (value: string) => void;
  submitExternalFunction: (name: string, inputs: ParameterSpec[], output: ParameterSpec | null) => void;
  submitSuperTypes: (superTypes: string[]) => void;
  /** Closes the open form. */
  cancelForm: () => void;
  /** The outcome of a submitted form: null closes it, a reason is shown in it. */
  formAnswered: (error: string | null) => void;
}

export function useDialogs({
  diagram,
  editable,
  send,
  literalConnections,
  setError,
}: {
  diagram: DiagramMessage | null;
  editable: boolean;
  send: (message: ClientMessage, kind?: RequestKind) => void;
  literalConnections: Map<string, string>;
  setError: (error: string | null) => void;
}): Dialogs {
  const [menu, setMenu] = useState<{ screen: Point; items: MenuItem[] } | null>(null);
  const [addForm, setAddForm] = useState<FormRequest | null>(null);
  const [prompt, setPrompt] = useState<PromptRequest | null>(null);
  const [externalForm, setExternalForm] = useState<Point | null>(null);
  const [superTypesForm, setSuperTypesForm] = useState<Point | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [draggingLiteral, setDraggingLiteral] = useState(false);

  const openMenu = useCallback((screen: Point, items: MenuItem[]) => setMenu({ screen, items }), []);
  const closeMenu = useCallback(() => setMenu(null), []);

  const cancelForm = useCallback(() => {
    setAddForm(null);
    setPrompt(null);
    setExternalForm(null);
    setSuperTypesForm(null);
    setFormError(null);
  }, []);

  const formAnswered = useCallback(
    (error: string | null) => (error === null ? cancelForm() : setFormError(error)),
    [cancelForm],
  );

  const onTool = useCallback(
    (request: Omit<FormRequest, 'suggestedName'>) => {
      setDraggingLiteral(false);
      if (request.tool === 'literal' && !request.target) {
        setError('Drop the literal on a free input port (they light up green while you drag).');
        return;
      }
      const base: Record<Tool, string> = { literal: '', variable: 'v', inParameter: 'in', outParameter: 'out' };
      setFormError(null);
      setAddForm({ ...request, suggestedName: unusedName(base[request.tool], diagram) });
    },
    [diagram, setError],
  );

  const editNode = useCallback(
    (node: DiagramFlowNode) => {
      if (!editable) return;
      const el = document.querySelector(`.react-flow__node[data-id="${CSS.escape(node.id)}"]`);
      const r = el?.getBoundingClientRect();
      const screen = r ? { x: r.left, y: r.bottom + 8 } : { x: 100, y: 100 };
      setFormError(null);
      if (node.type === 'literal') {
        if (literalConnections.has(node.id)) {
          setPrompt({ kind: 'literalValue', id: node.id, initial: node.data.label, screen });
        }
      } else if (node.data.deletable) {
        setPrompt({ kind: 'rename', id: node.id, initial: node.id.split('::').pop()!, screen });
      }
    },
    [editable, literalConnections],
  );

  const newDiagramType = useCallback((screen: Point) => {
    setFormError(null);
    setPrompt({ kind: 'newDiagramType', id: '', initial: '', screen });
  }, []);

  const newExternalFunction = useCallback((screen: Point) => {
    setFormError(null);
    setExternalForm(screen);
  }, []);

  const renameType = useCallback((typeName: string, screen: Point) => {
    setFormError(null);
    setPrompt({ kind: 'renameType', id: typeName, initial: typeName, screen });
  }, []);

  const newSubtype = useCallback((superType: string, screen: Point) => {
    setFormError(null);
    setPrompt({ kind: 'newSubtype', id: superType, initial: '', screen });
  }, []);

  const editSuperTypes = useCallback((screen: Point) => {
    setFormError(null);
    setSuperTypesForm(screen);
  }, []);

  const submitAddForm = useCallback(
    (values: FormValues) => {
      if (!addForm || !diagram?.diagramType) return;
      const common = { file: diagram.file, diagramType: diagram.diagramType };
      const { x, y } = addForm.position;
      const name = values.name ?? '';
      const typeName = values.typeName ?? 'Int';
      switch (addForm.tool) {
        case 'literal':
          send({ ...common, type: 'addLiteral', target: addForm.target!, value: values.value ?? '' }, 'form');
          break;
        case 'variable':
          send({ ...common, type: 'addVariable', kind: values.kind ?? 'var', name, typeName, x, y }, 'form');
          break;
        case 'inParameter':
        case 'outParameter': {
          const direction = addForm.tool === 'inParameter' ? 'in' : 'out';
          send({ ...common, type: 'addParameter', direction, name, typeName, x, y }, 'form');
          break;
        }
      }
    },
    [addForm, diagram, send],
  );

  const submitPrompt = useCallback(
    (value: string) => {
      if (!prompt || !diagram) return;
      const name = value.trim();
      switch (prompt.kind) {
        case 'newDiagramType':
          // Answered with the new type's diagram, so the view switches to it.
          send({ type: 'addDiagramType', file: diagram.file, name }, 'form');
          return;
        case 'newSubtype':
          send({ type: 'addSubtype', file: diagram.file, superType: prompt.id, name }, 'form');
          return;
        case 'renameType':
          // Answered with the shown type, under its new name if it was the one renamed.
          send({ type: 'renameType', file: diagram.file, diagramType: diagram.diagramType, typeName: prompt.id, name }, 'form');
          return;
      }
      if (!diagram.diagramType) return;
      const common = { file: diagram.file, diagramType: diagram.diagramType };
      if (prompt.kind === 'rename') {
        send({ ...common, type: 'rename', node: prompt.id, name: value.trim() }, 'form');
      } else {
        send({ ...common, type: 'setLiteral', literal: prompt.id, value }, 'form');
      }
    },
    [prompt, diagram, send],
  );

  const submitExternalFunction = useCallback(
    (name: string, inputs: ParameterSpec[], output: ParameterSpec | null) => {
      if (!diagram) return;
      // Answered with the shown diagram: an external function has none of its own.
      send(
        { type: 'addExternalFunction', file: diagram.file, diagramType: diagram.diagramType, name, inputs, output },
        'form',
      );
    },
    [diagram, send],
  );

  const submitSuperTypes = useCallback(
    (superTypes: string[]) => {
      if (!diagram?.diagramType) return;
      send({ type: 'setSuperTypes', file: diagram.file, typeName: diagram.diagramType, superTypes }, 'form');
    },
    [diagram, send],
  );

  return {
    menu,
    superTypesForm,
    renameType,
    newSubtype,
    editSuperTypes,
    submitSuperTypes,
    addForm,
    prompt,
    externalForm,
    formError,
    draggingLiteral,
    setDraggingLiteral,
    openMenu,
    closeMenu,
    onTool,
    editNode,
    newDiagramType,
    newExternalFunction,
    submitAddForm,
    submitPrompt,
    submitExternalFunction,
    cancelForm,
    formAnswered,
  };
}
