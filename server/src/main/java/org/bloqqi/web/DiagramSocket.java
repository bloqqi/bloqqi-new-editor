package org.bloqqi.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One WebSocket connection. Every request reloads the file from disk: the
 * source text is the single source of truth, so nothing is cached. Edits
 * ("move") are applied to the text, written back, and answered with the
 * diagram of the new text.
 * The socket pings the client every {@code pingInterval} so that an idle tab
 * (e.g. in a background window) is not closed by the idle timeout.
 *
 * <p>The socket remembers what the client shows (file, diagram type, and the
 * text the diagram was built from). When the file changes on disk with other
 * text (a text editor, another tab), it pushes an "update"; its own edits do
 * not come back, since their text is what it already sent.
 * (Public because Jetty invokes the listener methods through method handles.)
 */
public final class DiagramSocket implements Session.Listener.AutoDemanding {
	private static final Logger LOG = LoggerFactory.getLogger(DiagramSocket.class);

	private final Workspace workspace;
	private final Scheduler scheduler;
	private final Duration pingInterval;
	private final Set<DiagramSocket> sockets;
	private volatile Session session;
	private volatile Scheduler.Task pingTask;

	/** What the client shows: a diagram type of a file, built from {@code text}. */
	private record View(String file, String diagramType, String text) {
	}

	private volatile View view;

	/**
	 * Outgoing frames, sent one at a time: Jetty does not allow a send while
	 * another is in progress, and frames come from the request thread, the
	 * workspace watcher and the ping timer.
	 */
	private final Deque<Consumer<Callback>> outbox = new ArrayDeque<>();
	private boolean sending;

	/**
	 * @param sockets the open sockets, which this one joins while it is open
	 *                (the server tells them about changed files)
	 */
	DiagramSocket(Workspace workspace, Scheduler scheduler, Duration pingInterval, Set<DiagramSocket> sockets) {
		this.workspace = workspace;
		this.scheduler = scheduler;
		this.pingInterval = pingInterval;
		this.sockets = sockets;
	}

	@Override
	public void onWebSocketOpen(Session session) {
		this.session = session;
		sockets.add(this);
		schedulePing();
		try {
			send(Protocol.workspace(workspace.files()));
		} catch (IOException e) {
			LOG.warn("Could not list workspace", e);
			send(Protocol.error("Could not list workspace: " + e.getMessage()));
		}
	}

	@Override
	public void onWebSocketText(String text) {
		try {
			Protocol.Request request = Protocol.parseRequest(text);
			if (request == null) {
				send(Protocol.error("Unknown message: " + text));
				return;
			}
			String json;
			synchronized (BloqqiLoader.COMPILER_LOCK) {
				Answer answer = handle(request);
				view = new View(request.file(), answer.diagram().diagramType(), answer.text());
				json = Protocol.diagram(answer.diagram(), answer.change());
			}
			send(json);
		} catch (IllegalArgumentException | IllegalStateException e) {
			send(Protocol.error(e.getMessage()));
		} catch (Exception e) {
			LOG.warn("Request failed: {}", text, e);
			send(Protocol.error("Internal error: " + e));
		}
	}

	/**
	 * The diagram to send, how the request changed the file (null if it did
	 * not), and the file's text the diagram was built from.
	 */
	private record Answer(Diagram diagram, Protocol.Change change, String text) {
	}

	/** Handles a request; the caller holds the compiler lock. */
	private Answer handle(Protocol.Request request) throws IOException {
		LoadedFile file = workspace.load(request.file());
		return switch (request) {
			case Protocol.OpenRequest open ->
				new Answer(DiagramBuilder.build(file, open.diagramType()), null, file.source());
			case Protocol.RestoreRequest restore -> restore(file, restore);
			case Protocol.MoveRequest move ->
				save(file, move.diagramType(), DiagramEdits.moveNodes(file, move.diagramType(), move.moves()));
			case Protocol.ConnectRequest connect -> save(file, connect.diagramType(),
					DiagramEdits.connect(file, connect.diagramType(), connect.source(), connect.target()));
			case Protocol.DeleteRequest delete -> save(file, delete.diagramType(),
					DiagramEdits.delete(file, delete.diagramType(), delete.edges(), delete.nodes()));
			case Protocol.AddBlockRequest add -> save(file, add.diagramType(),
					DiagramEdits.addBlock(file, add.diagramType(), add.typeName(), add.x(), add.y()));
			case Protocol.AddLiteralRequest add -> save(file, add.diagramType(),
					DiagramEdits.addLiteral(file, add.diagramType(), add.target(), add.value()));
			case Protocol.AddVariableRequest add -> save(file, add.diagramType(), DiagramEdits.addVariable(file,
					add.diagramType(), add.kind(), add.name(), add.typeName(), add.x(), add.y()));
			case Protocol.AddParameterRequest add -> save(file, add.diagramType(), DiagramEdits.addParameter(file,
					add.diagramType(), add.in(), add.name(), add.typeName(), add.x(), add.y()));
			case Protocol.RenameRequest r -> save(file, r.diagramType(),
					DiagramEdits.rename(file, r.diagramType(), r.node(), r.name()));
			case Protocol.SetLiteralRequest r -> save(file, r.diagramType(),
					DiagramEdits.setLiteral(file, r.diagramType(), r.literal(), r.value()));
			case Protocol.AutoLayoutRequest r -> save(file, r.diagramType(),
					DiagramEdits.autoLayout(file, r.diagramType()));
			case Protocol.AddDiagramTypeRequest r -> save(file, r.name(),
					DiagramEdits.addDiagramType(file, r.name()));
			case Protocol.AddExternalFunctionRequest r -> save(file, r.diagramType(),
					DiagramEdits.addExternalFunction(file, r.name(), r.inputs(), r.output()));
			case Protocol.RenameTypeRequest r -> save(file,
					r.typeName().equals(r.diagramType()) ? r.name() : r.diagramType(),
					DiagramEdits.renameType(file, r.typeName(), r.name()));
			case Protocol.DeleteTypeRequest r -> save(file,
					r.typeName().equals(r.diagramType()) ? null : r.diagramType(),
					DiagramEdits.deleteType(file, r.typeName()));
			case Protocol.AddSubtypeRequest r -> save(file, r.name(),
					DiagramEdits.addSubtype(file, r.superType(), r.name()));
			case Protocol.SetSuperTypesRequest r -> save(file, r.typeName(),
					DiagramEdits.setSuperTypes(file, r.typeName(), r.superTypes()));
		};
	}

	/** Writes an edit's new text and answers with the diagram built from it. */
	private Answer save(LoadedFile file, String diagramType, String text) throws IOException {
		workspace.write(file.fileName(), text);
		Diagram d = DiagramBuilder.build(BloqqiLoader.load(file.fileName(), text), diagramType);
		return new Answer(d, new Protocol.Change(file.source(), text), text);
	}

	/**
	 * Undo/redo: puts back an earlier text of the file, if the file has not
	 * changed since. The text was valid when it was written, so it is not
	 * checked again; if the shown diagram type is not in it, the first one is shown.
	 */
	private Answer restore(LoadedFile file, Protocol.RestoreRequest restore) throws IOException {
		LoadedFile restored = BloqqiLoader.load(file.fileName(), restore.text());
		Diagram d;
		try {
			d = DiagramBuilder.build(restored, restore.diagramType());
		} catch (IllegalArgumentException noSuchType) {
			d = DiagramBuilder.build(restored, null);
		}
		String before = workspace.replace(file.fileName(), restore.expected(), restore.text());
		return new Answer(d, new Protocol.Change(before, restore.text()), restore.text());
	}

	/**
	 * A workspace file changed on disk. If the client shows it and its text is
	 * not what the client was sent, push the new diagram ("update"), of the same
	 * type if it still exists. If the file is gone, say so ("notice").
	 */
	void fileChanged(String fileName) {
		View v = view;
		if (v == null || (fileName != null && !fileName.equals(v.file()))) {
			return;
		}
		String json;
		synchronized (BloqqiLoader.COMPILER_LOCK) {
			LoadedFile file;
			try {
				file = workspace.load(v.file());
			} catch (IllegalArgumentException | IOException gone) {
				view = null;
				send(Protocol.notice(v.file() + " has been deleted or renamed outside the editor."));
				return;
			}
			if (file.source().equals(v.text())) {
				return;
			}
			Diagram d;
			try {
				d = DiagramBuilder.build(file, v.diagramType());
			} catch (IllegalArgumentException noSuchType) {
				d = DiagramBuilder.build(file, null);
			}
			view = new View(v.file(), d.diagramType(), file.source());
			json = Protocol.update(d);
		}
		send(json);
	}

	/** The workspace's files changed (a file was created, deleted or renamed). */
	void filesChanged(List<String> files) {
		send(Protocol.files(files));
	}

	@Override
	public void onWebSocketClose(int statusCode, String reason) {
		sockets.remove(this);
		Scheduler.Task task = pingTask;
		if (task != null) {
			task.cancel();
		}
	}

	@Override
	public void onWebSocketError(Throwable cause) {
		LOG.debug("WebSocket error", cause);
	}

	private void schedulePing() {
		pingTask = scheduler.schedule(this::ping, pingInterval);
	}

	private void ping() {
		Session s = session;
		if (s != null && s.isOpen()) {
			enqueue(callback -> s.sendPing(ByteBuffer.allocate(0), callback));
			schedulePing();
		}
	}

	private void send(String json) {
		Session s = session;
		if (s != null && s.isOpen()) {
			enqueue(callback -> s.sendText(json, callback));
		}
	}

	private void enqueue(Consumer<Callback> frame) {
		synchronized (outbox) {
			outbox.add(frame);
			if (sending) {
				return;
			}
			sending = true;
		}
		sendNext();
	}

	/** Sends the next queued frame; when it is done, the one after. */
	private void sendNext() {
		Consumer<Callback> frame;
		synchronized (outbox) {
			frame = outbox.poll();
			if (frame == null) {
				sending = false;
				return;
			}
		}
		Callback next = Callback.from(this::sendNext, failure -> {
			LOG.debug("Send failed", failure);
			synchronized (outbox) {
				outbox.clear();
				sending = false;
			}
		});
		try {
			frame.accept(next);
		} catch (RuntimeException e) {
			next.fail(e);
		}
	}
}
