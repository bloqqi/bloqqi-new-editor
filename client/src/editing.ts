// Edit actions offered by the canvas itself (e.g. the × button on a selected
// connection), provided by App, which owns the WebSocket.

import { createContext, useContext } from 'react';

export interface Editing {
  /** The diagram can be edited (connected, not read-only). */
  editable: boolean;
  /** Deletes these connections (edge ids). */
  deleteConnections: (edgeIds: string[]) => void;
  /** The edge id of the connection a literal feeds, if it can be deleted; else null. */
  literalConnection: (literalId: string) => string | null;
}

export const EditingContext = createContext<Editing>({
  editable: false,
  deleteConnections: () => {},
  literalConnection: () => null,
});

export const useEditing = () => useContext(EditingContext);
