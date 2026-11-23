package org.bloqqi.web;

import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

/**
 * HTTP server: the built client (static files) on {@code /} and the diagram
 * WebSocket on {@code /ws}.
 */
public final class WebServer {
	public static final String WEBSOCKET_PATH = "/ws";

	/**
	 * Jetty closes a WebSocket after this long without traffic (Jetty's default).
	 * An editor tab in the background sends nothing, so the server pings it more
	 * often than this; browsers answer pings by themselves, even in hidden tabs.
	 */
	public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(30);
	public static final Duration PING_INTERVAL = Duration.ofSeconds(10);

	private final Server server;

	/**
	 * @param port      TCP port (0 = pick a free port)
	 * @param workspace the Bloqqi files to serve
	 * @param staticDir directory with the built client, or null to serve only the WebSocket
	 */
	public WebServer(int port, Workspace workspace, Path staticDir) {
		this(port, workspace, staticDir, IDLE_TIMEOUT, PING_INTERVAL);
	}

	/** As above, with explicit WebSocket timing (for tests). */
	WebServer(int port, Workspace workspace, Path staticDir, Duration idleTimeout, Duration pingInterval) {
		server = new Server(port);

		Handler content;
		if (staticDir != null) {
			if (!Files.isDirectory(staticDir)) {
				throw new IllegalArgumentException(
						"Static directory not found: " + staticDir + " (build the client first: ant client)");
			}
			ResourceHandler resources = new ResourceHandler();
			resources.setBaseResource(ResourceFactory.of(server).newResource(staticDir.toAbsolutePath()));
			resources.setWelcomeFiles("index.html");
			resources.setDirAllowed(false);
			content = resources;
		} else {
			content = null;
		}

		WebSocketUpgradeHandler ws = WebSocketUpgradeHandler.from(server, container -> {
			container.setIdleTimeout(idleTimeout);
			container.addMapping(WEBSOCKET_PATH, (request, response, callback) ->
					new DiagramSocket(workspace, server.getScheduler(), pingInterval));
		});
		ws.setHandler(content);
		server.setHandler(ws);
	}

	public void start() throws Exception {
		server.start();
	}

	public void stop() throws Exception {
		server.stop();
	}

	public void join() throws InterruptedException {
		server.join();
	}

	/** The actual port, useful when started with port 0. */
	public int port() {
		return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
	}
}
