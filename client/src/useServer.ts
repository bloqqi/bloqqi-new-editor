// The connection to the server: one WebSocket (reconnected when it drops), the
// requests on their way, the diagram shown, and the undo/redo history.

import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { UndoHistory } from './history';
import {
  webSocketUrl,
  type ClientMessage,
  type CCodeMessage,
  type DiagramMessage,
  type ErrorMessage,
  type FeaturesMessage,
  type ProblemsMessage,
  type ServerMessage,
} from './protocol';

export type Status = 'connecting' | 'open' | 'reconnecting';

/**
 * What a request was for. The server answers requests in order, so a queue of
 * these tells what each answer ("diagram", "problems", "cCode" or "error") belongs to.
 * A "navigate" request opens another file or type at the user's request (a
 * new entry in the browser's history, so that Back returns); an "open" request
 * opens one without that (on connecting, or when going back).
 * A "form" request is an edit submitted from a form, which is told the
 * outcome; a "query" request only asks for something (the problems of the text
 * view's text, the generated C code) and changes nothing that is shown
 * (it does not count as pending: nothing waits for it).
 */
export type RequestKind = 'open' | 'navigate' | 'edit' | 'form' | 'undo' | 'redo' | 'query';

/** An answer to a request. */
export type Answer = DiagramMessage | ProblemsMessage | CCodeMessage | FeaturesMessage | ErrorMessage;

interface Pending {
  kind: RequestKind;
  file: string;
  /** Told the answer (or an error if the connection is lost first). */
  resolve?: (answer: Answer) => void;
}

const defaultKind = (message: ClientMessage): RequestKind =>
  message.type === 'open'
    ? 'open'
    : message.type === 'checkText' || message.type === 'generateC' || message.type === 'features'
      ? 'query'
      : 'edit';

/** Delay before reconnecting after the connection drops: doubles up to the maximum. */
const RECONNECT_MIN_MS = 500;
const RECONNECT_MAX_MS = 10_000;

/**
 * How many of the editor's own navigations lead back from the current entry of
 * the browser's history (kept in the entries' state, so it survives a reload).
 */
export function historyDepth(): number {
  const state = window.history.state as { bloqqiDepth?: number } | null;
  return state?.bloqqiDepth ?? 0;
}

/**
 * Keeps ?file=…&type=…(&view=…) in the address bar so a view can be reloaded
 * or linked. A navigation to another file or type adds an entry to the
 * browser's history (so that Back returns), anything else replaces it.
 */
function updateUrl(file: string, diagramType: string | null, navigation: boolean) {
  const current = new URLSearchParams(window.location.search);
  const params = new URLSearchParams();
  params.set('file', file);
  if (diagramType) params.set('type', diagramType);
  const view = current.get('view');
  if (view) params.set('view', view);
  const moved = current.get('file') !== file || (current.get('type') ?? null) !== diagramType;
  if (navigation && moved) {
    window.history.pushState({ bloqqiDepth: historyDepth() + 1 }, '', `?${params}`);
  } else {
    window.history.replaceState({ bloqqiDepth: historyDepth() }, '', `?${params}`);
  }
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
  /** Sends a request and resolves with its answer. */
  request: (message: ClientMessage, kind?: RequestKind) => Promise<Answer>;
  /**
   * Opens a file (and type). By default a navigation, which Back returns from;
   * `navigation = false` when following the browser's history.
   */
  open: (file: string, diagramType?: string, navigation?: boolean) => void;
  /** How many navigations Back can return through (see historyDepth). */
  historyDepth: number;
  /** Reads historyDepth again (after the browser moved in its history). */
  syncHistoryDepth: () => void;
  /** Requests sent but not yet answered (not counting queries). */
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
  const pending = useRef<Pending[]>([]);
  const [pendingCount, setPendingCount] = useState(0);
  const countPending = useCallback(() => setPendingCount(pending.current.filter((p) => p.kind !== 'query').length), []);
  const formAnswered = useRef(onFormAnswered);
  formAnswered.current = onFormAnswered;

  const sendRaw = useCallback(
    (ws: WebSocket, message: ClientMessage, kind: RequestKind, resolve?: (answer: Answer) => void) => {
      pending.current.push({ kind, file: message.file, resolve });
      countPending();
      ws.send(JSON.stringify(message));
    },
    [countPending],
  );

  const send = useCallback(
    (message: ClientMessage, kind: RequestKind = defaultKind(message)) => {
      if (socket.current) sendRaw(socket.current, message, kind);
    },
    [sendRaw],
  );

  const request = useCallback(
    (message: ClientMessage, kind: RequestKind = defaultKind(message)) =>
      new Promise<Answer>((resolve) => {
        if (socket.current) sendRaw(socket.current, message, kind, resolve);
        else resolve({ type: 'error', message: 'Not connected' });
      }),
    [sendRaw],
  );

  /** The request that the next answer from the server belongs to. */
  const answered = useCallback(() => {
    const request = pending.current.shift();
    countPending();
    return request;
  }, [countPending]);

  /** Requests sent on a connection that is gone will not be answered. */
  const dropPending = useCallback(() => {
    const dropped = pending.current;
    pending.current = [];
    countPending();
    for (const p of dropped) p.resolve?.({ type: 'error', message: 'The connection was lost' });
  }, [countPending]);

  const open = useCallback(
    (file: string, diagramType?: string, navigation = true) =>
      send({ type: 'open', file, diagramType }, navigation ? 'navigate' : 'open'),
    [send],
  );
  const [depth, setDepth] = useState(historyDepth);
  const syncHistoryDepth = useCallback(() => setDepth(historyDepth()), []);

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
      dropPending();
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
            updateUrl(message.file, message.diagramType, request?.kind === 'navigate');
            setDepth(historyDepth());
            request?.resolve?.(message);
            break;
          }
          case 'problems':
          case 'cCode':
          case 'features':
            answered()?.resolve?.(message);
            break;
          // Pushed by the server, not answers: they leave the request queue alone.
          case 'update': {
            // The shown file changed on disk; the history no longer matches it.
            history.current.clear(message.file);
            historyChanged();
            setDiagram({ ...message, type: 'diagram' });
            updateUrl(message.file, message.diagramType, false);
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
            request?.resolve?.(message);
            if (request?.kind === 'query') break;
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
      dropPending();
      window.clearTimeout(retryTimer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('online', reconnectNow);
      ws?.close();
    };
  }, [sendRaw, answered, dropPending, showNotice]);

  // Undo/redo of edits in the shown file (see history.ts). Not while a request
  // is on its way: its answer may change the history.
  const canUndo = status === 'open' && pendingCount === 0 && !!diagram && !!history.current.nextUndo(diagram.file);
  const canRedo = status === 'open' && pendingCount === 0 && !!diagram && !!history.current.nextRedo(diagram.file);

  const undo = useCallback(() => {
    const entry = diagram && history.current.nextUndo(diagram.file);
    if (!canUndo || !diagram || !entry) return;
    send(
      {
        type: 'restore',
        file: diagram.file,
        diagramType: entry.diagramType,
        text: entry.before,
        expected: entry.after,
      },
      'undo',
    );
  }, [canUndo, diagram, send]);

  const redo = useCallback(() => {
    const entry = diagram && history.current.nextRedo(diagram.file);
    if (!canRedo || !diagram || !entry) return;
    send(
      {
        type: 'restore',
        file: diagram.file,
        diagramType: entry.diagramType,
        text: entry.after,
        expected: entry.before,
      },
      'redo',
    );
  }, [canRedo, diagram, send]);

  return {
    status,
    files,
    diagram,
    error,
    notice,
    setError,
    send,
    request,
    open,
    historyDepth: depth,
    syncHistoryDepth,
    pendingCount,
    canUndo,
    canRedo,
    undo,
    redo,
  };
}
