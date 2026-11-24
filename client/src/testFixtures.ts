// Small diagrams for the unit tests (*.test.ts): nodes, edges and messages
// with defaults for everything a test does not care about.

import type { DiagramEdge, DiagramMessage, DiagramNode, DiagramPort } from './protocol';

export function port(name: string, typeName = 'Int', connectable = true): DiagramPort {
  return { name, typeName, y: 0, connectable };
}

export function node(id: string, fields: Partial<DiagramNode> = {}): DiagramNode {
  return {
    id,
    kind: 'block',
    label: id,
    typeName: 'Int',
    inherited: false,
    inlined: false,
    error: false,
    x: 0,
    y: 0,
    width: 60,
    height: 30,
    inPorts: [],
    outPorts: [],
    inConnectable: false,
    outConnectable: false,
    deletable: true,
    featureBase: null,
    openType: null,
    ...fields,
  };
}

export function edge(
  source: string,
  sourceHandle: string,
  target: string,
  targetHandle: string,
  fields: Partial<DiagramEdge> = {},
): DiagramEdge {
  return {
    id: `${source}.${sourceHandle}->${target}.${targetHandle}`,
    source,
    sourceHandle,
    target,
    targetHandle,
    inherited: false,
    deletable: true,
    interceptable: false,
    error: false,
    fromLiteral: false,
    intercept: null,
    largeMarker: false,
    ...fields,
  };
}

export function diagram(
  nodes: DiagramNode[],
  edges: DiagramEdge[] = [],
  fields: Partial<DiagramMessage> = {},
): DiagramMessage {
  return {
    type: 'diagram',
    file: 'Test.dia',
    diagramType: 'Main',
    diagramTypes: ['Main'],
    nodes,
    edges,
    problems: [],
    readOnly: null,
    change: null,
    palette: [],
    superTypes: [],
    text: '',
    ...fields,
  };
}
