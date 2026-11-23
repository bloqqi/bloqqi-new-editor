// Edit actions offered by the canvas itself (e.g. the × button on a selected
// connection), provided by App, which owns the WebSocket.

import { createContext, useContext } from 'react';
import type { Endpoint } from './protocol';

/**
 * Intercepting a connection (spec §3.7), as in the old editor: first click a
 * block's input port, then a block's output port. Ports of the right kind are
 * coloured: green if they fit (the connection's type; an input must be free),
 * yellow if not (they can still be clicked; the server then says why not).
 */
export interface InterceptPick {
  phase: 'input' | 'output';
  /** The intercepted connection's type. */
  typeName: string | undefined;
  /** The input port clicked first. */
  into: Endpoint | null;
}

export interface Editing {
  /** The diagram can be edited (connected, not read-only). */
  editable: boolean;
  /** Deletes these connections (edge ids) and blocks (node ids), as one edit. */
  deleteItems: (items: { edges?: string[]; nodes?: string[] }) => void;
  /** The edge id of the connection a literal feeds, if it can be deleted; else null. */
  literalConnection: (literalId: string) => string | null;
  /** A literal is being dragged from the palette: free input handles are highlighted. */
  draggingLiteral: boolean;
  /** Intercept mode, or null. */
  interceptPick: InterceptPick | null;
  /** A port was clicked in intercept mode. */
  pickPort: (port: Endpoint) => void;
}

export const EditingContext = createContext<Editing>({
  editable: false,
  deleteItems: () => {},
  literalConnection: () => null,
  draggingLiteral: false,
  interceptPick: null,
  pickPort: () => {},
});

export const useEditing = () => useContext(EditingContext);
