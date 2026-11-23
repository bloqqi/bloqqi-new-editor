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
 *
 * <p>Reads retry briefly when a file seems to be missing: replacing a file by
 * renaming another onto it (as this class and many editors do) is atomic on
 * local file systems, but not on all shared ones (e.g. Docker Desktop's
 * folders shared from macOS), where it can be gone for a moment.
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
		return BloqqiLoader.load(fileName, read(fileName));
	}

	/** How often, and how far apart, a read retries a file that seems to be missing. */
	private static final int READ_ATTEMPTS = 10;
	private static final long READ_RETRY_MILLIS = 20;

	/**
	 * The text of a workspace file.
	 *
	 * @throws IllegalArgumentException if the name is invalid or the file does
	 *         not exist (also after a few short retries)
	 */
	private String read(String fileName) throws IOException {
		Path path = resolve(fileName);
		for (int attempt = 1;; attempt++) {
			try {
				return Files.readString(path, StandardCharsets.UTF_8);
			} catch (java.nio.file.NoSuchFileException missing) {
				if (attempt == READ_ATTEMPTS) {
					throw new IllegalArgumentException("No such file in workspace: " + fileName);
				}
				try {
					Thread.sleep(READ_RETRY_MILLIS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw missing;
				}
			}
		}
	}

	/**
	 * Replaces the contents of an existing file. The text is written to a
	 * temporary file next to it and moved into place, so a crash never leaves a
	 * half-written file.
	 */
	public void write(String fileName, String text) throws IOException {
		read(fileName); // must exist
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
		String current = read(fileName);
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
		if (!path.getParent().equals(root) || Files.isDirectory(path)) {
			throw new IllegalArgumentException("No such file in workspace: " + fileName);
		}
		return path;
	}
}
