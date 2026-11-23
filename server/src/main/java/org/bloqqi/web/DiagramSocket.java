package org.bloqqi.web;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;

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
 * (Public because Jetty invokes the listener methods through method handles.)
 */
public final class DiagramSocket implements Session.Listener.AutoDemanding {
	private static final Logger LOG = LoggerFactory.getLogger(DiagramSocket.class);

	private final Workspace workspace;
	private final Scheduler scheduler;
	private final Duration pingInterval;
	private volatile Session session;
	private volatile Scheduler.Task pingTask;

	DiagramSocket(Workspace workspace, Scheduler scheduler, Duration pingInterval) {
		this.workspace = workspace;
		this.scheduler = scheduler;
		this.pingInterval = pingInterval;
	}

	@Override
	public void onWebSocketOpen(Session session) {
		this.session = session;
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

	/** The diagram to send, and how the request changed the file (null if it did not). */
	private record Answer(Diagram diagram, Protocol.Change change) {
	}

	/** Handles a request; the caller holds the compiler lock. */
	private Answer handle(Protocol.Request request) throws IOException {
		LoadedFile file = workspace.load(request.file());
		return switch (request) {
			case Protocol.OpenRequest open -> new Answer(DiagramBuilder.build(file, open.diagramType()), null);
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
		};
	}

	/** Writes an edit's new text and answers with the diagram built from it. */
	private Answer save(LoadedFile file, String diagramType, String text) throws IOException {
		workspace.write(file.fileName(), text);
		Diagram d = DiagramBuilder.build(BloqqiLoader.load(file.fileName(), text), diagramType);
		return new Answer(d, new Protocol.Change(file.source(), text));
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
		return new Answer(d, new Protocol.Change(before, restore.text()));
	}

	@Override
	public void onWebSocketClose(int statusCode, String reason) {
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
			s.sendPing(ByteBuffer.allocate(0), Callback.NOOP);
			schedulePing();
		}
	}

	private void send(String json) {
		if (session != null && session.isOpen()) {
			session.sendText(json, Callback.NOOP);
		}
	}
}
