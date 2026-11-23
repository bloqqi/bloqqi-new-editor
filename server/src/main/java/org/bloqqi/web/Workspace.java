package org.bloqqi.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * A directory of Bloqqi files. Only plain {@code *.dia} files directly in the
 * directory are visible; names with path separators are rejected.
 */
public final class Workspace {
	private final Path root;

	public Workspace(Path root) {
		this.root = root.toAbsolutePath().normalize();
		if (!Files.isDirectory(this.root)) {
			throw new IllegalArgumentException("Workspace is not a directory: " + this.root);
		}
	}

	public Path root() {
		return root;
	}

	/** Names of all Bloqqi files in the workspace, sorted. */
	public List<String> files() throws IOException {
		try (Stream<Path> paths = Files.list(root)) {
			return paths
					.filter(Files::isRegularFile)
					.map(p -> p.getFileName().toString())
					.filter(name -> name.endsWith(".dia"))
					.sorted()
					.toList();
		}
	}

	public LoadedFile load(String fileName) throws IOException {
		Path path = resolve(fileName);
		return BloqqiLoader.load(fileName, Files.readString(path, StandardCharsets.UTF_8));
	}

	/**
	 * Replaces the contents of an existing file. The text is written to a
	 * temporary file next to it and moved into place, so a crash never leaves a
	 * half-written file.
	 */
	public void write(String fileName, String text) throws IOException {
		Path path = resolve(fileName);
		Path tmp = Files.createTempFile(root, "." + fileName + ".", ".tmp");
		try {
			Files.writeString(tmp, text, StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Replaces the contents of a file, but only if it still contains
	 * {@code expected}: undo and redo must not overwrite changes made elsewhere
	 * (a text editor, another tab) since the edit they revert or repeat.
	 *
	 * @return the replaced text (equal to {@code expected})
	 * @throws IllegalArgumentException if the file has other contents
	 */
	public String replace(String fileName, String expected, String text) throws IOException {
		String current = Files.readString(resolve(fileName), StandardCharsets.UTF_8);
		if (!current.equals(expected)) {
			throw new IllegalArgumentException(fileName
					+ " has been changed since (in another editor or tab), so the change cannot be undone or redone");
		}
		write(fileName, text);
		return current;
	}

	private Path resolve(String fileName) {
		if (fileName == null
				|| !fileName.endsWith(".dia")
				|| fileName.contains("/")
				|| fileName.contains("\\")
				|| fileName.startsWith(".")) {
			throw new IllegalArgumentException("Invalid file name: " + fileName);
		}
		Path path = root.resolve(fileName).normalize();
		if (!path.getParent().equals(root) || !Files.isRegularFile(path)) {
			throw new IllegalArgumentException("No such file in workspace: " + fileName);
		}
		return path;
	}
}
