package org.bloqqi.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import org.bloqqi.compiler.ast.Annotation;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.TypeDecl;
import org.bloqqi.web.Diagram.DiagramNode;

/**
 * Diagram operations, applied as text edits to the source. Each operation
 * returns the new source text; it does not write anything. The new text has
 * been reparsed and checked (see {@link #check}).
 */
public final class DiagramEdits {
	private DiagramEdits() {
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

		// Existing entries, keyed by their text without whitespace (e.g. "P::K").
		TreeMap<String, Layout.Rect> entries = new TreeMap<>();
		for (Annotation a : dt.getAnnotations()) {
			String key = source.text(a.getVarUse()).replaceAll("\\s+", "");
			entries.put(key, new Layout.Rect(a.getX(), a.getY(), a.getWidth(), a.getHeight()));
		}
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

		String nl = source.lineSeparator();
		StringJoiner block = new StringJoiner("," + nl, "annotation(" + nl, nl + ")");
		entries.forEach((key, r) -> block.add(
				"  " + key + " = (" + r.x() + ", " + r.y() + ", " + r.width() + ", " + r.height() + ")"));

		String text = source.text();
		String newText;
		if (dt.getNumAnnotation() > 0) {
			// The annotation list spans from the "annotation" keyword to its ")".
			int start = source.start(dt.getAnnotationList());
			int end = source.end(dt.getAnnotationList());
			newText = text.substring(0, start) + block + text.substring(end);
		} else if (dt.hasLocalWiring()) {
			int at = source.start(dt.getLocalWiring());
			newText = text.substring(0, at) + block + nl + text.substring(at);
		} else {
			int at = source.end(dt);
			newText = text.substring(0, at) + nl + block + text.substring(at);
		}
		check(file, newText);
		return newText;
	}

	/**
	 * Reparses the edited text and rejects the edit if it introduced syntax
	 * errors or more semantic errors: a failed edit must not damage the file.
	 *
	 * @return the reparsed file
	 */
	static LoadedFile check(LoadedFile before, String newText) {
		LoadedFile after = BloqqiLoader.load(before.fileName(), newText);
		if (!after.syntaxErrors().isEmpty()) {
			throw new IllegalStateException("Edit would introduce syntax errors: " + after.syntaxErrors());
		}
		if (after.semanticErrors().size() > before.semanticErrors().size()) {
			throw new IllegalStateException("Edit would introduce errors: " + after.semanticErrors());
		}
		return after;
	}

	/**
	 * Why diagrams of the file cannot be edited, or null if they can: edits are
	 * located through AST source positions, which are unreliable after the parser
	 * has recovered from syntax errors.
	 */
	public static String readOnlyReason(LoadedFile file) {
		if (!file.isParsed() || !file.syntaxErrors().isEmpty()) {
			return file.fileName() + " has syntax errors; fix them in a text editor to edit the diagram";
		}
		return null;
	}

	private static void requireEditable(LoadedFile file) {
		String reason = readOnlyReason(file);
		if (reason != null) {
			throw new IllegalArgumentException(reason);
		}
	}

	private static DiagramType topLevelDiagramType(LoadedFile file, String name) {
		for (TypeDecl td : file.compilationUnit().typeDecls()) {
			if (td.isDiagramType() && td.name().equals(name)) {
				return (DiagramType) td;
			}
		}
		throw new IllegalArgumentException("No diagram type " + name + " in " + file.fileName());
	}
}
