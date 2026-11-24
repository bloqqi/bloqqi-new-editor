package org.bloqqi.web;

import java.nio.file.Path;
import java.util.List;

/**
 * Fills an empty workspace with the examples that load without errors
 * ({@link Workspace#addExamples}); run by {@code ant build} and {@code ant run}
 * for the default workspace.
 *
 * <pre>
 * java org.bloqqi.web.AddExamples WORKSPACE EXAMPLES
 * </pre>
 */
public final class AddExamples {
	private AddExamples() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("Usage: AddExamples WORKSPACE EXAMPLES");
			System.exit(2);
		}
		Path workspace = Path.of(args[0]);
		List<String> added = Workspace.addExamples(workspace, Path.of(args[1]));
		if (!added.isEmpty()) {
			System.out.println("Created " + workspace.toAbsolutePath().normalize() + " with " + added.size()
					+ " examples: " + String.join(", ", added));
		}
	}
}
