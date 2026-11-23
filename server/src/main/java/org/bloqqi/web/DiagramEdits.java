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
import org.bloqqi.compiler.ast.Block;
import org.bloqqi.compiler.ast.InheritedBlock;
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

	/** A replacement of the text in [start, end). */
	record TextEdit(int start, int end, String text) {
	}

	/** Applies non-overlapping edits to a text. */
	static String apply(String text, List<TextEdit> edits) {
		List<TextEdit> sorted = new ArrayList<>(edits);
		sorted.sort(Comparator.comparingInt(TextEdit::start).reversed());
		StringBuilder b = new StringBuilder(text);
		int limit = Integer.MAX_VALUE;
		for (TextEdit e : sorted) {
			if (e.end() > limit) {
				throw new IllegalStateException("Overlapping edits");
			}
			b.replace(e.start(), e.end(), e.text());
			limit = e.start();
		}
		return b.toString();
	}

	/** The type's annotation entries, keyed by their text without whitespace (e.g. "P::K"). */
	private static TreeMap<String, Layout.Rect> annotations(SourceText source, DiagramType dt) {
		TreeMap<String, Layout.Rect> entries = new TreeMap<>();
		for (Annotation a : dt.getAnnotations()) {
			String key = source.text(a.getVarUse()).replaceAll("\\s+", "");
			entries.put(key, new Layout.Rect(a.getX(), a.getY(), a.getWidth(), a.getHeight()));
		}
		return entries;
	}

	/**
	 * The edit that makes the type's {@code annotation(...)} block hold exactly
	 * these entries, sorted by key as the old editor wrote them: the block is
	 * replaced, or inserted after the type's closing brace (before any
	 * {@code wiring}), or removed with its line(s) if there are no entries.
	 */
	private static TextEdit annotationEdit(SourceText source, DiagramType dt, TreeMap<String, Layout.Rect> entries) {
		String nl = source.lineSeparator();
		StringJoiner block = new StringJoiner("," + nl, "annotation(" + nl, nl + ")");
		entries.forEach((key, r) -> block.add(
				"  " + key + " = (" + r.x() + ", " + r.y() + ", " + r.width() + ", " + r.height() + ")"));

		if (dt.getNumAnnotation() > 0) {
			// The annotation list spans from the "annotation" keyword to its ")".
			int start = source.start(dt.getAnnotationList());
			int end = source.end(dt.getAnnotationList());
			if (entries.isEmpty()) {
				int[] lines = statementLines(source, start, end);
				return new TextEdit(lines[0], lines[1], "");
			}
			return new TextEdit(start, end, block.toString());
		}
		if (entries.isEmpty()) {
			return new TextEdit(0, 0, "");
		}
		if (dt.hasLocalWiring()) {
			int at = source.start(dt.getLocalWiring());
			return new TextEdit(at, at, block + nl);
		}
		int at = source.end(dt);
		return new TextEdit(at, at, nl + block);
	}

	/**
	 * Adds a block of a palette type to a top-level diagram type, at (x, y):
	 * inserts {@code Type_N;} (simple name form, the first free N, as the old
	 * editor named blocks by default) after the type's last variable or block
	 * declaration, and stores its position. The other nodes keep their current
	 * positions (the type's whole layout is stored, as for a move).
	 *
	 * @throws IllegalArgumentException if the type is not in the palette or the
	 *         block would give errors (e.g. a type cycle)
	 */
	public static String addBlock(LoadedFile file, String diagramTypeName, String typeName, int x, int y) {
		requireEditable(file);
		DiagramType dt = topLevelDiagramType(file, diagramTypeName);
		Diagram before = DiagramBuilder.build(file, diagramTypeName);
		if (before.palette().stream().noneMatch(e -> e.name().equals(typeName))) {
			throw new IllegalArgumentException("Cannot add a block of " + typeName + " to " + dt.name());
		}
		String name = null;
		for (int n = 1; name == null; n++) {
			if (dt.lookup(typeName + "_" + n) == null) {
				name = typeName + "_" + n;
			}
		}

		List<ASTNode<?>> declarations = new ArrayList<>();
		dt.getLocalVariableList().forEach(declarations::add);
		dt.getLocalBlockList().forEach(declarations::add);
		String withBlock = insertStatement(new SourceText(file.source()), dt, name + ";", declarations);
		LoadedFile added = check(file, withBlock);

		// Keep everything where it is shown now, and put the new block at (x, y).
		List<Move> positions = new ArrayList<>();
		for (DiagramNode n : before.nodes()) {
			if (!n.kind().equals(Diagram.LITERAL)) {
				positions.add(new Move(n.id(), n.x(), n.y()));
			}
		}
		positions.add(new Move(name, Math.max(0, x), Math.max(0, y)));
		String newText = moveNodes(added, diagramTypeName, positions);
		String id = name;
		if (DiagramBuilder.build(BloqqiLoader.load(file.fileName(), newText), diagramTypeName).nodes().stream()
				.noneMatch(n -> n.id().equals(id))) {
			throw new IllegalStateException("Could not add " + name + " to " + dt.name());
		}
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
		DiagramType dt = diagram.diagramType();
		List<ASTNode<?>> statements = new ArrayList<>();
		dt.getLocalVariableList().forEach(statements::add);
		dt.getLocalBlockList().forEach(statements::add);
		dt.getFlowDeclList().forEach(statements::add);
		String newText = insertStatement(new SourceText(file.source()), dt, statement, statements);
		LoadedFile after = check(file, newText);
		boolean connected = DiagramBuilder.build(after, diagramTypeName).edges().stream().anyMatch(e ->
				e.source().equals(source.node()) && e.sourceHandle().equals(source.handle())
						&& e.target().equals(target.node()) && e.targetHandle().equals(target.handle()));
		if (!connected) {
			throw new IllegalStateException("Could not connect " + source + " to " + target + " (" + statement + ")");
		}
		return newText;
	}

	/** Deletes connections, given as edge ids; see {@link #delete}. */
	public static String deleteConnections(LoadedFile file, String diagramTypeName, List<String> edgeIds) {
		return delete(file, diagramTypeName, edgeIds, List.of());
	}

	/**
	 * Deletes connections (edge ids) and blocks (node ids) in one edit
	 * (docs/editor-spec.md §3.4):
	 * <ul>
	 * <li>a connection: the statement that declares it, i.e. the {@code connect(...)},
	 *     or the whole {@code intercept ...} for either half of an interception;</li>
	 * <li>a block: its declaration, the statements of all connections on its
	 *     ports, and its entry in the type's annotation block.</li>
	 * </ul>
	 * Only what is declared in the type's own body can be deleted: not inherited
	 * connections and blocks, not those inside inlined blocks, not those coming
	 * from a {@code wiring}. If something else still refers to a deleted block
	 * (e.g. a subtype), the edit is refused because it would add errors.
	 *
	 * @throws IllegalArgumentException if something does not exist or cannot be deleted
	 */
	public static String delete(LoadedFile file, String diagramTypeName, List<String> edgeIds, List<String> nodeIds) {
		requireEditable(file);
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		DiagramType dt = diagram.diagramType();
		SourceText source = new SourceText(file.source());

		Set<FlowDecl> flows = new LinkedHashSet<>();
		for (String id : edgeIds) {
			InheritedConnection c = diagram.connection(id);
			if (c == null) {
				throw new IllegalArgumentException("No connection " + id + " in " + dt.name());
			}
			if (!DiagramBuilder.isDeletable(c, dt)) {
				throw new IllegalArgumentException("Connection " + id + " is not declared in " + dt.name()
						+ " (it is inherited, inside an inlined block, or comes from a wiring)");
			}
			flows.add(c.getDeclaredFlowDecl());
		}

		List<Block> blocks = new ArrayList<>();
		Set<String> blockIds = new HashSet<>();
		for (String id : nodeIds) {
			InheritedBlock b = null;
			for (InheritedBlock candidate : dt.getBlocks()) {
				if (candidate.accessString().equals(id)) {
					b = candidate;
				}
			}
			if (b == null) {
				throw new IllegalArgumentException("No block " + id + " in " + dt.name());
			}
			if (!DiagramBuilder.isDeletable(b, dt)) {
				throw new IllegalArgumentException("Block " + id + " is not declared in " + dt.name()
						+ " (it is inherited or inlined)");
			}
			blocks.add(b.getDeclaredBlock());
			blockIds.add(id);
		}
		// The connections on the deleted blocks' ports (and literals feeding them).
		if (!blockIds.isEmpty()) {
			for (InheritedConnection c : dt.getConnections()) {
				String s = nodeOf(c.getSource().anchor());
				String t = nodeOf(c.getTarget().anchor());
				if ((blockIds.contains(s) || blockIds.contains(t)) && DiagramBuilder.isDeletable(c, dt)) {
					flows.add(c.getDeclaredFlowDecl());
				}
			}
		}

		List<TextEdit> edits = new ArrayList<>();
		for (FlowDecl d : flows) {
			int[] r = statementLines(source, source.start(d), source.end(d));
			edits.add(new TextEdit(r[0], r[1], ""));
		}
		if (!blocks.isEmpty()) {
			int close = bodyBraces(source, dt)[1];
			for (Block b : blocks) {
				int start = source.start(b);
				int end = semicolonAfter(source.text(), source.end(b), close);
				int[] r = statementLines(source, start, end);
				edits.add(new TextEdit(r[0], r[1], ""));
			}
			TreeMap<String, Layout.Rect> entries = annotations(source, dt);
			if (entries.keySet().removeAll(blockIds)) {
				edits.add(annotationEdit(source, dt, entries));
			}
		}
		String newText = apply(file.source(), edits);
		check(file, newText);
		return newText;
	}

	/** The node id (access string) an anchor belongs to, or null (literals). */
	private static String nodeOf(Anchor a) {
		if (a instanceof Port p) {
			return p.block().accessString();
		}
		return a instanceof org.bloqqi.compiler.ast.Node n && !(a instanceof org.bloqqi.compiler.ast.Literal)
				? n.accessString()
				: null;
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
	 * Inserts a statement into a diagram type's body, on its own line: after the
	 * last of {@code after} (with its indentation), or, if none of them is
	 * written, before the body's first statement, or into the empty body.
	 * Trailing comments on the line before stay where they are.
	 */
	static String insertStatement(SourceText source, DiagramType dt, String statement, List<ASTNode<?>> after) {
		String text = source.text();
		String nl = source.lineSeparator();
		int[] braces = bodyBraces(source, dt);
		int open = braces[0];
		int close = braces[1];

		// The end (after its ';') of the last of "after".
		int lastStart = -1;
		int lastEnd = -1;
		for (ASTNode<?> n : after) {
			if (SourceText.hasPosition(n)) {
				int end = semicolonAfter(text, source.end(n), close);
				if (end > lastEnd) {
					lastEnd = end;
					lastStart = source.start(n);
				}
			}
		}

		if (lastEnd < 0) {
			// Before the first statement of the body, if there is one.
			int first = Integer.MAX_VALUE;
			List<ASTNode<?>> statements = new ArrayList<>();
			dt.getLocalVariableList().forEach(statements::add);
			dt.getLocalBlockList().forEach(statements::add);
			dt.getFlowDeclList().forEach(statements::add);
			for (ASTNode<?> n : statements) {
				if (SourceText.hasPosition(n)) {
					first = Math.min(first, source.start(n));
				}
			}
			if (first != Integer.MAX_VALUE) {
				String indent = indentation(text, first);
				int lineStart = first - indent.length();
				if (text.substring(lineStart, first).isBlank()) {
					return text.substring(0, lineStart) + indent + statement + nl + text.substring(lineStart);
				}
				return text.substring(0, first) + statement + " " + text.substring(first);
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
