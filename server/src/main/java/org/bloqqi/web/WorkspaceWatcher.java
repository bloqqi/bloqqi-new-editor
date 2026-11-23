package org.bloqqi.web;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the workspace directory for changes to {@code *.dia} files (made by
 * the editor itself, a text editor, git, ...) and reports the names of the
 * changed files. Changes are collected for {@link #SETTLE} before they are
 * reported, since editors often write a file in several steps. A {@code null}
 * name means "possibly all files" (the watch service overflowed).
 *
 * <p>Uses the platform's {@link WatchService}: immediate on Linux (inotify);
 * on macOS the JDK polls, so changes may take a few seconds to arrive.
 */
final class WorkspaceWatcher implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

	/** How long changes are collected before they are reported. */
	static final Duration SETTLE = Duration.ofMillis(100);

	private final Path root;
	private final Scheduler scheduler;
	private final Consumer<Set<String>> onChange;
	private final Set<String> changed = new LinkedHashSet<>();
	private boolean flushScheduled;
	private WatchService service;
	private Thread thread;

	WorkspaceWatcher(Path root, Scheduler scheduler, Consumer<Set<String>> onChange) {
		this.root = root;
		this.scheduler = scheduler;
		this.onChange = onChange;
	}

	void start() throws IOException {
		service = root.getFileSystem().newWatchService();
		root.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
		thread = new Thread(this::run, "workspace-watcher");
		thread.setDaemon(true);
		thread.start();
	}

	private void run() {
		try {
			while (true) {
				WatchKey key = service.take();
				for (WatchEvent<?> event : key.pollEvents()) {
					if (event.kind() == OVERFLOW) {
						note(null);
					} else {
						String name = event.context().toString();
						// Only workspace files (not, e.g., the editor's ".X.dia.*.tmp").
						if (name.endsWith(".dia") && !name.startsWith(".")) {
							note(name);
						}
					}
				}
				if (!key.reset()) {
					LOG.warn("The workspace {} can no longer be watched", root);
					return;
				}
			}
		} catch (InterruptedException | ClosedWatchServiceException e) {
			// Closed.
		}
	}

	private synchronized void note(String name) {
		changed.add(name);
		if (!flushScheduled) {
			flushScheduled = true;
			scheduler.schedule(this::flush, SETTLE);
		}
	}

	private void flush() {
		Set<String> names;
		synchronized (this) {
			names = new LinkedHashSet<>(changed);
			changed.clear();
			flushScheduled = false;
		}
		try {
			onChange.accept(names);
		} catch (RuntimeException e) {
			LOG.warn("Handling changes to {} failed", names, e);
		}
	}

	@Override
	public void close() throws IOException {
		if (service != null) {
			service.close();
		}
	}
}
