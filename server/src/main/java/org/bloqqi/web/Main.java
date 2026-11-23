package org.bloqqi.web;

import java.nio.file.Path;

/**
 * Starts the Bloqqi web editor server.
 *
 * <pre>
 * java org.bloqqi.web.Main --workspace DIR [--port 8080] [--static client/dist]
 * </pre>
 */
public final class Main {
	private Main() {
	}

	public static void main(String[] args) throws Exception {
		int port = 8080;
		Path workspace = null;
		Path staticDir = null;
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (i + 1 >= args.length) {
				usage("Missing value for " + arg);
			}
			String value = args[++i];
			switch (arg) {
				case "--port" -> port = Integer.parseInt(value);
				case "--workspace" -> workspace = Path.of(value);
				case "--static" -> staticDir = Path.of(value);
				default -> usage("Unknown option " + arg);
			}
		}
		if (workspace == null) {
			usage("--workspace is required");
		}

		WebServer server = new WebServer(port, new Workspace(workspace), staticDir);
		server.start();
		System.out.println("Bloqqi editor: http://localhost:" + server.port() + "/ (workspace "
				+ workspace.toAbsolutePath().normalize() + ")");
		server.join();
	}

	private static void usage(String message) {
		System.err.println(message);
		System.err.println("Usage: Main --workspace DIR [--port 8080] [--static DIR]");
		System.exit(2);
	}
}
