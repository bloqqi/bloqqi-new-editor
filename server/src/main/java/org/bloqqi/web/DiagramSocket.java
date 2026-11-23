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
				json = Protocol.diagram(handle(request));
			}
			send(json);
		} catch (IllegalArgumentException | IllegalStateException e) {
			send(Protocol.error(e.getMessage()));
		} catch (Exception e) {
			LOG.warn("Request failed: {}", text, e);
			send(Protocol.error("Internal error: " + e));
		}
	}

	/** Handles a request; the caller holds the compiler lock. */
	private Diagram handle(Protocol.Request request) throws IOException {
		LoadedFile file = workspace.load(request.file());
		return switch (request) {
			case Protocol.OpenRequest open -> DiagramBuilder.build(file, open.diagramType());
			case Protocol.MoveRequest move -> {
				String text = DiagramEdits.moveNodes(file, move.diagramType(), move.moves());
				workspace.write(file.fileName(), text);
				yield DiagramBuilder.build(BloqqiLoader.load(file.fileName(), text), move.diagramType());
			}
		};
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
