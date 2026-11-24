package org.bloqqi.web;

import static org.bloqqi.web.SourceEdits.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.web.Diagram.DiagramNode;

/**
 * Layout edits: moving nodes, auto layout, and placing new nodes. Positions
 * are stored in the type's {@code annotation(...)} block.
 */
public final class LayoutEdits {
	private LayoutEdits() {
	}

	/** A new position for a node's body (its size is kept). */
	public record Move(String id, int x, int y) {
	}

	/**
	 * Moves nodes (parameters, blocks, variables) of a top-level diagram type.
	 *
	 * <p>Positions are stored in the type's {@code annotation(...)} block, as the
	 * old editor did (docs/editor-spec.md §3.6, §6.3): every node currently shown
	 * gets an entry keyed by its access string, so a type that was auto-laid-out,
	 * or that inherits its layout from a super type, keeps its current look; an
	 * inherited node gets its own entry ({@code P::e}) in the subtype. Entries are
	 * sorted by key. Existing entries for other keys are kept as they are. Only
	 * the annotation block is rewritten (or inserted after the type's closing
	 * brace, before any {@code wiring}); the rest of the text is untouched.
	 *
	 * @throws IllegalArgumentException if the file has syntax errors, the type does
	 *         not exist, or an id is not a movable node of the type
	 */
	public static String moveNodes(LoadedFile file, String diagramTypeName, List<Move> moves) {
		requireEditable(file);
		for (Move m : moves) {
			// The annotation grammar only has unsigned numbers (NUM = [0-9]+).
			if (m.x() < 0 || m.y() < 0) {
				throw new IllegalArgumentException("Cannot move " + m.id() + " to (" + m.x() + ", " + m.y()
						+ "): positions must not be negative");
			}
		}
		DiagramType dt = topLevelDiagramType(file, diagramTypeName);
		Diagram diagram = DiagramBuilder.build(file, diagramTypeName);
		SourceText source = new SourceText(file.source());

		Map<String, DiagramNode> nodes = new HashMap<>();
		for (DiagramNode n : diagram.nodes()) {
			if (!n.kind().equals(Diagram.LITERAL)) {
				nodes.put(n.id(), n);
			}
		}

		TreeMap<String, Layout.Rect> entries = annotations(source, dt);
		// The current layout of every node, as shown...
		for (DiagramNode n : nodes.values()) {
			entries.put(n.id(), new Layout.Rect(n.x(), n.y(), n.width(), n.height()));
		}
		// ...with the moves applied.
		for (Move m : moves) {
			DiagramNode n = nodes.get(m.id());
			if (n == null) {
				throw new IllegalArgumentException("Cannot move " + m.id() + ": not a node of " + dt.name());
			}
			entries.put(n.id(), new Layout.Rect(m.x(), m.y(), n.width(), n.height()));
		}

		String newText = apply(source.text(), List.of(annotationEdit(source, dt, entries)));
		check(file, newText);
		return newText;
	}

	/**
	 * After a node was added (giving {@code added}), stores the layout: every
	 * node where it was shown before ({@code before}), and the new one at (x, y).
	 */
	static String placeNew(Diagram before, LoadedFile added, String diagramTypeName, String id, int x, int y) {
		List<Move> positions = new ArrayList<>();
		for (DiagramNode n : before.nodes()) {
			if (!n.kind().equals(Diagram.LITERAL)) {
				positions.add(new Move(n.id(), n.x(), n.y()));
			}
		}
		positions.add(new Move(id, Math.max(0, x), Math.max(0, y)));
		String newText = moveNodes(added, diagramTypeName, positions);
		if (DiagramBuilder.build(BloqqiLoader.load(added.fileName(), newText), diagramTypeName).nodes().stream()
				.noneMatch(n -> n.id().equals(id))) {
			throw new IllegalStateException("Could not add " + id + " to " + diagramTypeName);
		}
		return newText;
	}

	/**
	 * Lays out the whole type with ELK, as if it had no stored positions, and
	 * stores the result ("Auto layout" in the old editor's toolbar, §6.4).
	 */
	public static String autoLayout(LoadedFile file, String diagramTypeName) {
		requireEditable(file);
		DiagramType dt = topLevelDiagramType(file, diagramTypeName);
		Map<String, Layout.Rect> rects = DiagramBuilder.resolve(file, diagramTypeName).autoLayout();
		SourceText source = new SourceText(file.source());
		TreeMap<String, Layout.Rect> entries = annotations(source, dt);
		entries.putAll(rects);
		String newText = apply(source.text(), List.of(annotationEdit(source, dt, entries)));
		check(file, newText);
		return newText;
	}
}
