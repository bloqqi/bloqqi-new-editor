// The connection to the server: one WebSocket (reconnected when it drops), the
// requests on their way, the diagram shown, and the undo/redo history.

import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { UndoHistory } from './history';
import { webSocketUrl, type ClientMessage, type DiagramMessage, type ServerMessage } from './protocol';

export type Status = 'connecting' | 'open' | 'reconnecting';

/**
 * What a request was for. The server answers requests in order, so a queue of
 * these tells what each answer ("diagram" or "error") belongs to. A "form"
 * request is an edit submitted from a form, which is told the outcome.
 */
export type RequestKind = 'open' | 'edit' | 'form' | 'undo' | 'redo';

/** Delay before reconnecting after the connection drops: doubles up to the maximum. */
const RECONNECT_MIN_MS = 500;
const RECONNECT_MAX_MS = 10_000;

/** Keeps ?file=…&type=… in the address bar so a view can be reloaded or linked. */
function updateUrl(file: string, diagramType: string | null) {
  const params = new URLSearchParams();
  params.set('file', file);
  if (diagramType) params.set('type', diagramType);
  window.history.replaceState(null, '', `?${params}`);
}

export interface Server {
  status: Status;
  files: string[];
  /** The diagram shown (the latest answer). */
  diagram: DiagramMessage | null;
  /** A message for the error banner, or null. */
  error: string | null;
  /** A short-lived message for the info banner (e.g. the file was reloaded), or null. */
  notice: string | null;
  setError: (error: string | null) => void;
  send: (message: ClientMessage, kind?: RequestKind) => void;
  open: (file: string, diagramType?: string) => void;
  /** Requests sent but not yet answered. */
  pendingCount: number;
  canUndo: boolean;
  canRedo: boolean;
  undo: () => void;
  redo: () => void;
}

/**
 * @param onFormAnswered told the outcome of each "form" request: null when it
 *   was accepted, or the server's reason when it was refused
 */
export function useServer(onFormAnswered: (error: string | null) => void): Server {
  const socket = useRef<WebSocket | null>(null);
  const [status, setStatus] = useState<Status>('connecting');
  const [files, setFiles] = useState<string[]>([]);
  const [diagram, setDiagram] = useState<DiagramMessage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const noticeTimer = useRef<number | undefined>(undefined);
  const showNotice = useCallback((message: string, ms: number) => {
    window.clearTimeout(noticeTimer.current);
    setNotice(message);
    noticeTimer.current = window.setTimeout(() => setNotice(null), ms);
  }, []);

  const history = useRef(new UndoHistory());
  const [, historyChanged] = useReducer((n: number) => n + 1, 0);
  const pending = useRef<{ kind: RequestKind; file: string }[]>([]);
  const [pendingCount, setPendingCount] = useState(0);
  const formAnswered = useRef(onFormAnswered);
  formAnswered.current = onFormAnswered;

  const sendRaw = useCallback((ws: WebSocket, message: ClientMessage, kind: RequestKind) => {
    pending.current.push({ kind, file: message.file });
    setPendingCount(pending.current.length);
    ws.send(JSON.stringify(message));
  }, []);

  const send = useCallback(
    (message: ClientMessage, kind: RequestKind = message.type === 'open' ? 'open' : 'edit') => {
      if (socket.current) sendRaw(socket.current, message, kind);
    },
    [sendRaw],
  );

  /** The request that the next answer from the server belongs to. */
  const answered = useCallback(() => {
    const request = pending.current.shift();
    setPendingCount(pending.current.length);
    return request;
  }, []);

  const open = useCallback(
    (file: string, diagramType?: string) => send({ type: 'open', file, diagramType }),
    [send],
  );

  // One WebSocket at a time. If it closes (server restart, network change,
  // laptop sleep), reconnect with backoff; the "workspace" message on the new
  // connection reopens the diagram named in the URL, freshly loaded from disk.
  useEffect(() => {
    let ws: WebSocket | null = null;
    let stopped = false;
    let retryTimer: number | undefined;
    let delay = RECONNECT_MIN_MS;

    const reconnectNow = () => {
      if (!stopped && ws === null) {
        window.clearTimeout(retryTimer);
        connect();
      }
    };

    function connect() {
      const current = new WebSocket(webSocketUrl());
      ws = current;
      socket.current = current;
      // Requests sent on an earlier connection will not be answered.
      pending.current = [];
      setPendingCount(0);
      current.onopen = () => {
        delay = RECONNECT_MIN_MS;
        setStatus('open');
      };
      current.onclose = () => {
        if (ws !== current) return;
        ws = null;
        socket.current = null;
        if (stopped) return;
        setStatus('reconnecting');
        retryTimer = window.setTimeout(connect, delay);
        delay = Math.min(delay * 2, RECONNECT_MAX_MS);
      };
      current.onmessage = (event) => {
        const message = JSON.parse(event.data as string) as ServerMessage;
        switch (message.type) {
          case 'workspace': {
            setFiles(message.files);
            const params = new URLSearchParams(window.location.search);
            const file = params.get('file') ?? message.files[0];
            if (file) {
              sendRaw(current, { type: 'open', file, diagramType: params.get('type') ?? undefined }, 'open');
            }
            break;
          }
          case 'diagram': {
            const request = answered();
            if (message.change && request) {
              const h = history.current;
              if (request.kind === 'undo') h.undone(message.file);
              else if (request.kind === 'redo') h.redone(message.file);
              else h.record(message.file, { diagramType: message.diagramType, ...message.change });
              historyChanged();
            }
            if (request?.kind === 'form') formAnswered.current(null);
            setDiagram(message);
            setError(null);
            updateUrl(message.file, message.diagramType);
            break;
          }
          // Pushed by the server, not answers: they leave the request queue alone.
          case 'update': {
            // The shown file changed on disk; the history no longer matches it.
            history.current.clear(message.file);
            historyChanged();
            setDiagram({ ...message, type: 'diagram' });
            updateUrl(message.file, message.diagramType);
            showNotice(`${message.file} was changed outside this tab, so it has been reloaded.`, 5000);
            break;
          }
          case 'files':
            setFiles(message.files);
            break;
          case 'notice':
            showNotice(message.message, 15000);
            break;
          case 'error': {
            const request = answered();
            if (request && (request.kind === 'undo' || request.kind === 'redo')) {
              // The file no longer is what the history expects (changed elsewhere).
              history.current.clear(request.file);
              historyChanged();
              setError(`${message.message}. The undo history of ${request.file} has been cleared.`);
            } else if (request?.kind === 'form') {
              formAnswered.current(message.message); // shown in the form, which stays open
            } else {
              setError(message.message);
            }
            // A refused edit: put the nodes back where the file has them.
            setDiagram((d) => (d ? { ...d } : d));
            break;
          }
        }
      };
    }

    connect();
    // Don't wait for the backoff when the user comes back to the tab.
    const onVisible = () => {
      if (document.visibilityState === 'visible') reconnectNow();
    };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('online', reconnectNow);
    return () => {
      stopped = true;
      pending.current = [];
      window.clearTimeout(retryTimer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', reconnectNow);
      ws?.close();
    };
  }, [sendRaw, answered, showNotice]);

  // Undo/redo of edits in the shown file (see history.ts). Not while a request
  // is on its way: its answer may change the history.
  const canUndo = status === 'open' && pendingCount === 0 && !!diagram && !!history.current.nextUndo(diagram.file);
  const canRedo = status === 'open' && pendingCount === 0 && !!diagram && !!history.current.nextRedo(diagram.file);

  const undo = useCallback(() => {
    const entry = diagram && history.current.nextUndo(diagram.file);
    if (!canUndo || !diagram || !entry) return;
    send(
      { type: 'restore', file: diagram.file, diagramType: entry.diagramType, text: entry.before, expected: entry.after },
      'undo',
    );
  }, [canUndo, diagram, send]);

  const redo = useCallback(() => {
    const entry = diagram && history.current.nextRedo(diagram.file);
    if (!canRedo || !diagram || !entry) return;
    send(
      { type: 'restore', file: diagram.file, diagramType: entry.diagramType, text: entry.after, expected: entry.before },
      'redo',
    );
  }, [canRedo, diagram, send]);

  return { status, files, diagram, error, notice, setError, send, open, pendingCount, canUndo, canRedo, undo, redo };
}
