package org.bloqqi.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Starts the Bloqqi web editor server.
 *
 * <pre>
 * java org.bloqqi.web.Main --workspace DIR [--port 8080] [--static client/dist] [--examples DIR]
 * </pre>
 *
 * With {@code --examples}, a missing or empty workspace (no files other than
 * hidden ones) first gets the examples from that folder that load without
 * errors (see {@link Workspace#addExamples}); the Docker image uses it.
 */
public final class Main {
	private Main() {
	}

	public static void main(String[] args) throws Exception {
		int port = 8080;
		Path workspace = null;
		Path staticDir = null;
		Path examples = null;
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
				case "--examples" -> examples = Path.of(value);
				default -> usage("Unknown option " + arg);
			}
		}
		if (workspace == null) {
			usage("--workspace is required");
		}

		if (examples != null) {
			try {
				List<String> added = Workspace.addExamples(workspace, examples);
				if (!added.isEmpty()) {
					System.out.println("The workspace was empty: added " + added.size() + " example files "
							+ added);
				}
			} catch (IOException e) {
				System.err.println("Could not add the examples to " + workspace.toAbsolutePath()
						+ " (can the editor write there?): " + e);
			}
		}
		WebServer server = new WebServer(port, new Workspace(workspace), staticDir);
		server.start();
		System.out.println("Bloqqi editor: http://localhost:" + server.port() + "/ (workspace "
				+ workspace.toAbsolutePath().normalize() + ")");
		server.join();
	}

	private static void usage(String message) {
		System.err.println(message);
		System.err.println("Usage: Main --workspace DIR [--port 8080] [--static DIR] [--examples DIR]");
		System.exit(2);
	}
}
