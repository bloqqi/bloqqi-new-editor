package org.bloqqi.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

/**
 * HTTP server: the built client (static files) on {@code /} and the diagram
 * WebSocket on {@code /ws}. It watches the workspace and tells the open
 * sockets about changed files, so that every tab shows the files as they are.
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

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WebServer.class);

	private final Server server;
	private final Workspace workspace;
	private final Set<DiagramSocket> sockets = ConcurrentHashMap.newKeySet();
	private final WorkspaceWatcher watcher;
	private List<String> files = List.of();

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
		this.workspace = workspace;
		this.watcher = new WorkspaceWatcher(workspace.root(), server.getScheduler(), this::changed);

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
					new DiagramSocket(workspace, server.getScheduler(), pingInterval, sockets));
		});
		ws.setHandler(content);
		server.setHandler(ws);
	}

	public void start() throws Exception {
		server.start();
		files = workspace.files();
		watcher.start();
	}

	public void stop() throws Exception {
		watcher.close();
		server.stop();
	}

	/** Files changed on disk: tell the sockets (null: possibly all of them). */
	private void changed(Set<String> names) {
		try {
			List<String> now = workspace.files();
			boolean listChanged;
			synchronized (this) {
				listChanged = !now.equals(files);
				files = now;
			}
			for (DiagramSocket s : sockets) {
				if (listChanged) {
					s.filesChanged(now);
				}
				if (names.contains(null)) {
					s.fileChanged(null);
				} else {
					names.forEach(s::fileChanged);
				}
			}
		} catch (IOException e) {
			LOG.warn("Could not list the workspace", e);
		}
	}

	public void join() throws InterruptedException {
		server.join();
	}

	/** The actual port, useful when started with port 0. */
	public int port() {
		return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
	}
}
