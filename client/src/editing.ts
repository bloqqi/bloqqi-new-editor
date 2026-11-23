// Edit actions offered by the canvas itself (e.g. the × button on a selected
// connection), provided by App, which owns the WebSocket.

import { createContext, useContext } from 'react';

export interface Editing {
  /** The diagram can be edited (connected, not read-only). */
  editable: boolean;
  /** Deletes these connections (edge ids) and blocks (node ids), as one edit. */
  deleteItems: (items: { edges?: string[]; nodes?: string[] }) => void;
  /** The edge id of the connection a literal feeds, if it can be deleted; else null. */
  literalConnection: (literalId: string) => string | null;
  /** A literal is being dragged from the palette: free input handles are highlighted. */
  draggingLiteral: boolean;
}

export const EditingContext = createContext<Editing>({
  editable: false,
  deleteItems: () => {},
  literalConnection: () => null,
  draggingLiteral: false,
});

export const useEditing = () => useContext(EditingContext);
