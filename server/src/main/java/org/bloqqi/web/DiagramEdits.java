package org.bloqqi.web;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.Anchor;
import org.bloqqi.compiler.ast.Annotation;
import org.bloqqi.compiler.ast.BloqqiParser;
import org.bloqqi.compiler.ast.BloqqiScanner;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.FlowDecl;
import org.bloqqi.compiler.ast.InheritedConnection;
import org.bloqqi.compiler.ast.Port;
import org.bloqqi.compiler.ast.TypeDecl;
import org.bloqqi.compiler.ast.VarUse;
import org.bloqqi.web.Diagram.DiagramNode;
import org.bloqqi.web.DiagramBuilder.Endpoint;

import beaver.Symbol;

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
	 * Connects two handles: appends {@code connect(<source>, <target>);} to the
	 * body of a top-level diagram type (docs/editor-spec.md §3.2).
	 *
	 * <p>Unlike the old editor, invalid connections are refused (spec Q6): the
	 * source must be an out-port, in-parameter or variable and the target an
	 * in-port, out-parameter or variable without an incoming connection; both must
	 * be nameable here ({@code Anchor.canAccess()}: not ports inside inlined blocks);
	 * the types must be compatible; and, like every edit, the new text must not
	 * have more errors (e.g. a dataflow cycle). Finally, the reparsed diagram must
	 * have an edge between exactly these handles.
	 *
	 * @throws IllegalArgumentException if the connection is not allowed
	 */
	public static String connect(LoadedFile file, String diagramTypeName, Endpoint source, Endpoint target) {
		requireEditable(file);
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		Anchor s = diagram.anchor(source);
		Anchor t = diagram.anchor(target);
		if (s == null || t == null) {
			throw new IllegalArgumentException("No such handle: " + (s == null ? source : target));
		}
		if (!DiagramBuilder.canBeSource(s)) {
			throw new IllegalArgumentException("A connection cannot start at " + source);
		}
		if (!DiagramBuilder.canBeTarget(t)) {
			throw new IllegalArgumentException(DiagramBuilder.ingoing(t).isEmpty()
					? "A connection cannot end at " + target
					: target + " already has an incoming connection");
		}
		if (!s.type().compatibleWith(t.type())) {
			throw new IllegalArgumentException("Cannot connect " + source + " (" + s.type().name() + ") to "
					+ target + " (" + t.type().name() + "): incompatible types");
		}

		String statement = "connect(" + accessText(s) + ", " + accessText(t) + ");";
		String newText = insertStatement(new SourceText(file.source()), diagram.diagramType(), statement);
		LoadedFile after = check(file, newText);
		boolean connected = DiagramBuilder.build(after, diagramTypeName).edges().stream().anyMatch(e ->
				e.source().equals(source.node()) && e.sourceHandle().equals(source.handle())
						&& e.target().equals(target.node()) && e.targetHandle().equals(target.handle()));
		if (!connected) {
			throw new IllegalStateException("Could not connect " + source + " to " + target + " (" + statement + ")");
		}
		return newText;
	}

	/**
	 * Deletes connections, given as edge ids, by removing the statements that
	 * declare them (docs/editor-spec.md §3.4): the {@code connect(...)}, or the
	 * whole {@code intercept ...} for either half of an interception. Only
	 * connections declared in the type itself can be deleted
	 * ({@code Connection.canDelete()}), not inherited ones or those inside
	 * inlined blocks.
	 *
	 * @throws IllegalArgumentException if an edge does not exist or cannot be deleted
	 */
	public static String deleteConnections(LoadedFile file, String diagramTypeName, List<String> edgeIds) {
		requireEditable(file);
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		DiagramType dt = diagram.diagramType();
		Set<FlowDecl> declarations = new LinkedHashSet<>();
		for (String id : edgeIds) {
			InheritedConnection c = diagram.connection(id);
			if (c == null) {
				throw new IllegalArgumentException("No connection " + id + " in " + dt.name());
			}
			if (!DiagramBuilder.isDeletable(c, dt)) {
				throw new IllegalArgumentException("Connection " + id + " is not declared in " + dt.name()
						+ " (it is inherited, inside an inlined block, or comes from a wiring)");
			}
			declarations.add(c.getDeclaredFlowDecl());
		}

		SourceText source = new SourceText(file.source());
		List<int[]> ranges = new ArrayList<>();
		for (FlowDecl d : declarations) {
			ranges.add(statementLines(source, source.start(d), source.end(d)));
		}
		ranges.sort(Comparator.comparingInt((int[] r) -> r[0]).reversed());
		StringBuilder text = new StringBuilder(file.source());
		for (int[] r : ranges) {
			text.delete(r[0], r[1]);
		}
		String newText = text.toString();
		check(file, newText);
		return newText;
	}

	/**
	 * How a connection end is written: {@code Anchor.access()} (as in the old
	 * editor), but without the {@code Super::} qualification of an inherited block
	 * when its name alone is unambiguous ({@code e.out} rather than {@code P::e.out}).
	 */
	static String accessText(Anchor a) {
		VarUse use = a.access();
		if (use == null) {
			throw new IllegalArgumentException("Cannot refer to " + a + " here");
		}
		String text = use.toString();
		if (a instanceof Port p && p.block().isInherited() && !p.block().isNameAmbiguous()) {
			String qualifier = p.block().declaredInDiagramType().name() + ASTNode.DECLARED_IN_SEP;
			if (text.startsWith(qualifier)) {
				text = text.substring(qualifier.length());
			}
		}
		return text;
	}

	/**
	 * Inserts a statement at the end of a diagram type's body, on its own line
	 * with the indentation of the last statement. Trailing comments on that line
	 * stay where they are.
	 */
	static String insertStatement(SourceText source, DiagramType dt, String statement) {
		String text = source.text();
		String nl = source.lineSeparator();
		int[] braces = bodyBraces(source, dt);
		int open = braces[0];
		int close = braces[1];

		// The end (after its ';') of the last statement in the body.
		int lastStart = -1;
		int lastEnd = -1;
		List<ASTNode<?>> statements = new ArrayList<>();
		dt.getLocalVariableList().forEach(statements::add);
		dt.getLocalBlockList().forEach(statements::add);
		dt.getFlowDeclList().forEach(statements::add);
		for (ASTNode<?> n : statements) {
			if (SourceText.hasPosition(n)) {
				int end = semicolonAfter(text, source.end(n), close);
				if (end > lastEnd) {
					lastEnd = end;
					lastStart = source.start(n);
				}
			}
		}

		if (lastEnd < 0) {
			// Empty body: "{\n  statement\n}".
			boolean sameLine = text.substring(open, close).indexOf('\n') < 0
					&& text.substring(open, close).indexOf('\r') < 0;
			String indent = defaultIndent(text);
			return text.substring(0, open + 1) + nl + indent + statement + (sameLine ? nl : "")
					+ text.substring(open + 1);
		}
		int eol = endOfLine(text, lastEnd);
		if (close < eol) {
			// "{ a: Add; }": the body is on one line.
			return text.substring(0, close) + statement + " " + text.substring(close);
		}
		String indent = indentation(text, lastStart);
		return text.substring(0, eol) + nl + indent + statement + text.substring(eol);
	}

	/**
	 * Offsets of the '{' and '}' of a diagram type's body, from the scanner's
	 * tokens (so braces in comments and strings don't count).
	 */
	static int[] bodyBraces(SourceText source, DiagramType dt) {
		int from = source.start(dt);
		int open = -1;
		int depth = 0;
		BloqqiScanner scanner = new BloqqiScanner(new StringReader(source.text()));
		try {
			for (Symbol token = scanner.nextToken(); token.getId() != BloqqiParser.Terminals.EOF;
					token = scanner.nextToken()) {
				int offset = source.offset(Symbol.getLine(token.getStart()), Symbol.getColumn(token.getStart()));
				if (offset < from) {
					continue;
				}
				if (token.getId() == BloqqiParser.Terminals.LBRA) {
					if (depth++ == 0) {
						open = offset;
					}
				} else if (token.getId() == BloqqiParser.Terminals.RBRA && open >= 0 && --depth == 0) {
					return new int[] { open, offset };
				}
			}
		} catch (IOException | beaver.Scanner.Exception | BloqqiScanner.ScannerError e) {
			throw new IllegalStateException("Could not scan " + dt.name(), e);
		}
		throw new IllegalStateException("No body found for " + dt.name());
	}

	/** Offset just after the ';' that ends a statement whose node ends at {@code end}. */
	private static int semicolonAfter(String text, int end, int limit) {
		if (end > 0 && text.charAt(end - 1) == ';') {
			return end;
		}
		int i = text.indexOf(';', end);
		return i >= 0 && i < limit ? i + 1 : end;
	}

	/**
	 * The range to delete for a statement at [start, end): its whole line(s) if
	 * nothing else is on them, otherwise just the statement and the spaces after it.
	 */
	private static int[] statementLines(SourceText source, int start, int end) {
		String text = source.text();
		int lineStart = start;
		while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}
		int eol = endOfLine(text, end);
		boolean aloneBefore = text.substring(lineStart, start).isBlank();
		boolean aloneAfter = text.substring(end, eol).isBlank();
		if (aloneBefore && aloneAfter) {
			int next = eol;
			if (text.startsWith("\r\n", next)) {
				next += 2;
			} else if (next < text.length()) {
				next++;
			}
			return new int[] { lineStart, next };
		}
		int stop = end;
		while (stop < eol && (text.charAt(stop) == ' ' || text.charAt(stop) == '\t')) {
			stop++;
		}
		return new int[] { start, stop };
	}

	private static int endOfLine(String text, int from) {
		int i = from;
		while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
			i++;
		}
		return i;
	}

	private static String indentation(String text, int offset) {
		int lineStart = offset;
		while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}
		int i = lineStart;
		while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
			i++;
		}
		return text.substring(lineStart, i);
	}

	/** Indentation for a statement in an empty body: a tab if the file indents with tabs. */
	private static String defaultIndent(String text) {
		for (String line : text.split("\\R")) {
			if (line.startsWith("\t")) {
				return "\t";
			}
			if (line.startsWith(" ")) {
				return "  ";
			}
		}
		return "  ";
	}

	/**
	 * Reparses the edited text and rejects the edit if it introduced syntax
	 * errors or new semantic errors: a failed edit must not damage the file.
	 *
	 * @return the reparsed file
	 */
	static LoadedFile check(LoadedFile before, String newText) {
		LoadedFile after = BloqqiLoader.load(before.fileName(), newText);
		if (!after.syntaxErrors().isEmpty()) {
			throw new IllegalStateException("The edit would introduce syntax errors: " + after.syntaxErrors());
		}
		if (after.semanticErrors().size() > before.semanticErrors().size()) {
			Set<String> old = new HashSet<>();
			before.semanticErrors().forEach(p -> old.add(p.message()));
			List<String> added = new ArrayList<>();
			after.semanticErrors().stream().map(Problem::message).filter(m -> !old.contains(m)).forEach(added::add);
			throw new IllegalArgumentException("Not allowed: it would give "
					+ (added.isEmpty() ? after.semanticErrors().toString() : String.join("; ", added)));
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
