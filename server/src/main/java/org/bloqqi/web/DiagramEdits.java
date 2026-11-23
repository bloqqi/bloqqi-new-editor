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
import org.bloqqi.web.Diagram.DiagramEdge;
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

		return placeNew(before, added, diagramTypeName, name, x, y);
	}

	/**
	 * After a node was added (giving {@code added}), stores the layout: every
	 * node where it was shown before ({@code before}), and the new one at (x, y).
	 */
	private static String placeNew(Diagram before, LoadedFile added, String diagramTypeName, String id, int x, int y) {
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
	 * Renames a block, variable or parameter declared in the type
	 * (docs/editor-spec.md §3.8): its declaration and every use in the file,
	 * e.g. {@code x}, {@code x.in1}, {@code P::x} in subtypes, annotation entries,
	 * and, for a parameter, port uses {@code blk.p} in other types. A block in
	 * the simple name form ({@code Add_1;}) becomes {@code newName: Add;}.
	 *
	 * <p>The uses are found by walking every name use in the file and following
	 * it to the declaration it means (through the wrappers that inheritance
	 * creates, and from a port to its parameter); the name is then replaced in
	 * the use's text. Uses that the compiler made up (e.g. the implicit port in
	 * {@code connect(Sub_1, x)}) have no text of their own; if the old name does
	 * not appear in the text around them, there is nothing to change.
	 *
	 * @throws IllegalArgumentException if the node is not declared here, the
	 *         name is invalid or taken, or the result would have errors
	 */
	public static String rename(LoadedFile file, String diagramTypeName, String nodeId, String newName) {
		requireEditable(file);
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		DiagramType dt = diagram.diagramType();
		org.bloqqi.compiler.ast.Node node = diagram.node(nodeId);
		if (node == null || node instanceof org.bloqqi.compiler.ast.Literal) {
			throw new IllegalArgumentException("No block, variable or parameter " + nodeId + " in " + dt.name());
		}
		if (!DiagramBuilder.isDeletable(node, dt)) {
			throw new IllegalArgumentException(nodeId + " is not declared in " + dt.name()
					+ " (it is inherited or inlined), so it cannot be renamed here");
		}
		String oldName = node.name();
		if (oldName.equals(newName)) {
			return file.source();
		}
		requireNewName(dt, newName);
		SourceText source = new SourceText(file.source());
		ASTNode<?> declaration = DiagramBuilder.declaration(node);

		TreeMap<Integer, TextEdit> edits = new TreeMap<>();
		// The declaration.
		if (declaration instanceof Block b) {
			if (b.getModifiers().isOnSimpleNameForm()) {
				String typeName = b.type().name();
				TextEdit e = new TextEdit(source.start(b), source.end(b), newName + ": " + typeName);
				edits.put(e.start(), e);
			} else {
				TextEdit e = replaceLastWord(source, b.getName(), oldName, newName);
				edits.put(e.start(), e);
			}
		} else {
			TextEdit e = replaceDeclaredName(source, declaration, oldName, newName);
			edits.put(e.start(), e);
		}
		// The uses.
		int declarationEdits = edits.size();
		collectUses(file.compilationUnit(), declaration, source, oldName, newName, edits);
		int uses = edits.size() - declarationEdits;

		String newText = apply(file.source(), new ArrayList<>(edits.values()));
		LoadedFile after = check(file, newText);
		DiagramBuilder.Builder renamed = DiagramBuilder.resolve(after, diagramTypeName);
		org.bloqqi.compiler.ast.Node newNode = renamed.node(newName);
		if (newNode == null) {
			throw new IllegalStateException("Could not rename " + nodeId + " to " + newName);
		}
		// The new name must mean the same things: a use could otherwise end up
		// meaning something else that has the new name (e.g. a block of the same
		// name in a subtype), or an existing use of the new name could end up
		// meaning the renamed one. Both change the number of uses.
		TreeMap<Integer, TextEdit> usesAfter = new TreeMap<>();
		collectUses(after.compilationUnit(), DiagramBuilder.declaration(newNode), new SourceText(newText),
				newName, newName, usesAfter);
		if (usesAfter.size() != uses) {
			throw new IllegalArgumentException("Not allowed: " + newName + " already means something else where "
					+ oldName + " is used (e.g. in a subtype), so the rename would change the diagram");
		}
		return newText;
	}

	/** Adds an edit for every use of {@code target} under {@code n}. */
	private static void collectUses(ASTNode<?> n, ASTNode<?> target, SourceText source, String oldName,
			String newName, TreeMap<Integer, TextEdit> edits) {
		if (n instanceof VarUse use && refersTo(use, target)) {
			ASTNode<?> positioned = use;
			while (positioned != null && !SourceText.hasPosition(positioned)) {
				positioned = positioned.getParent();
			}
			if (positioned != null) {
				TextEdit e = replaceLastWordOrNull(source, positioned, oldName, newName);
				if (e != null) {
					edits.putIfAbsent(e.start(), e);
				}
			}
		}
		for (int i = 0; i < n.getNumChild(); i++) {
			collectUses(n.getChild(i), target, source, oldName, newName, edits);
		}
	}

	/** Whether a name use means the declaration (seen through inheritance wrappers and ports). */
	private static boolean refersTo(VarUse use, ASTNode<?> target) {
		org.bloqqi.compiler.ast.VarDecl d = use.decl();
		if (d instanceof Port p) {
			if (target instanceof org.bloqqi.compiler.ast.Parameter) {
				return original(p.getParameter()) == target;
			}
			return false;
		}
		return d != null && original(d) == target;
	}

	/** The declaration behind an inheritance (or inlining) wrapper. */
	private static ASTNode<?> original(ASTNode<?> d) {
		ASTNode<?> current = d;
		for (int i = 0; i < 100; i++) {
			ASTNode<?> next = current instanceof org.bloqqi.compiler.ast.Node n ? DiagramBuilder.declaration(n) : null;
			if (next == null || next == current) {
				return current;
			}
			current = next;
		}
		return current;
	}

	/** In a parameter or variable declaration: the name, which is before ':' or last. */
	private static TextEdit replaceDeclaredName(SourceText source, ASTNode<?> declaration, String oldName,
			String newName) {
		String text = source.text(declaration);
		int colon = text.indexOf(':');
		int at = colon >= 0 ? lastWord(text.substring(0, colon), oldName) : lastWord(text, oldName);
		if (at < 0) {
			throw new IllegalStateException("Cannot find " + oldName + " in " + text);
		}
		int start = source.start(declaration) + at;
		return new TextEdit(start, start + oldName.length(), newName);
	}

	private static TextEdit replaceLastWord(SourceText source, ASTNode<?> n, String oldName, String newName) {
		TextEdit e = replaceLastWordOrNull(source, n, oldName, newName);
		if (e == null) {
			throw new IllegalStateException("Cannot find " + oldName + " in " + source.text(n));
		}
		return e;
	}

	private static TextEdit replaceLastWordOrNull(SourceText source, ASTNode<?> n, String oldName, String newName) {
		int at = lastWord(source.text(n), oldName);
		if (at < 0) {
			return null;
		}
		int start = source.start(n) + at;
		return new TextEdit(start, start + oldName.length(), newName);
	}

	/** Index of the last occurrence of {@code word} as a whole identifier in {@code text}, or -1. */
	private static int lastWord(String text, String word) {
		for (int i = text.lastIndexOf(word); i >= 0; i = i == 0 ? -1 : text.lastIndexOf(word, i - 1)) {
			boolean startOk = i == 0 || !isIdChar(text.charAt(i - 1));
			int end = i + word.length();
			boolean endOk = end >= text.length() || !isIdChar(text.charAt(end));
			if (startOk && endOk) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isIdChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$';
	}

	/**
	 * Changes the value of a literal (docs/editor-spec.md §3.3): its text in
	 * {@code connect(<value>, ...)}. The new value is checked like a new literal.
	 */
	public static String setLiteral(LoadedFile file, String diagramTypeName, String literalId, String value) {
		requireEditable(file);
		String text = value == null ? "" : value.trim();
		if (org.bloqqi.compiler.ast.Program.parseLiteral(text) == null) {
			throw new IllegalArgumentException("Not a literal: \"" + text
					+ "\" (expected e.g. 3, 2.5, true, false or \"text\")");
		}
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		DiagramType dt = diagram.diagramType();
		Diagram d = DiagramBuilder.build(file, diagramTypeName);
		DiagramEdge edge = d.edges().stream().filter(e -> e.source().equals(literalId)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("No literal " + literalId + " in " + dt.name()));
		InheritedConnection c = diagram.connection(edge.id());
		if (!DiagramBuilder.isDeletable(c, dt) || !(c.getDeclaredFlowDecl() instanceof org.bloqqi.compiler.ast.Connection declared)) {
			throw new IllegalArgumentException("The literal is not declared in " + dt.name());
		}
		SourceText source = new SourceText(file.source());
		ASTNode<?> literal = declared.getSource();
		String newText = apply(file.source(), List.of(new TextEdit(source.start(literal), source.end(literal), text)));
		check(file, newText);
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

	/** A parameter of a new external function. */
	public record Parameter(String name, String typeName) {
	}

	/**
	 * Adds an empty diagram type {@code diagramtype <name> {}} at the end of the
	 * file (written as the old editor wrote types without parameters).
	 *
	 * @throws IllegalArgumentException if the name is invalid or a type of that name exists
	 */
	public static String addDiagramType(LoadedFile file, String name) {
		requireEditable(file);
		requireNewTypeName(file, name);
		SourceText source = new SourceText(file.source());
		String nl = source.lineSeparator();
		String text = file.source();
		boolean endsWithNewline = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r");
		String newText = text + (endsWithNewline ? "" : nl) + "diagramtype " + name + " {" + nl + "}" + nl;
		LoadedFile after = check(file, newText);
		if (!DiagramBuilder.diagramTypeNames(after.compilationUnit()).contains(name)) {
			throw new IllegalStateException("Could not add diagram type " + name);
		}
		return newText;
	}

	/**
	 * Adds an external function {@code external <name>(a: Int, b: Real => r: Bool);}
	 * with zero or more input parameters and an optional output parameter, after
	 * the file's last external function (or at the end of the file).
	 *
	 * @param output the output parameter, or null
	 * @throws IllegalArgumentException if a name or type is invalid, or names clash
	 */
	public static String addExternalFunction(LoadedFile file, String name, List<Parameter> inputs, Parameter output) {
		requireEditable(file);
		requireNewTypeName(file, name);
		List<Parameter> all = new ArrayList<>(inputs);
		if (output != null) {
			all.add(output);
		}
		Set<String> names = new HashSet<>();
		for (Parameter p : all) {
			if (p.name() == null || !org.bloqqi.compiler.ast.Program.isIdValid(p.name())) {
				throw new IllegalArgumentException("Not a valid parameter name: \"" + p.name() + "\"");
			}
			if (!names.add(p.name())) {
				throw new IllegalArgumentException("Two parameters are called " + p.name());
			}
			requireSimpleType(p.typeName());
		}
		StringJoiner ins = new StringJoiner(", ");
		inputs.forEach(p -> ins.add(p.name() + ": " + p.typeName()));
		String out = output == null ? ""
				: (inputs.isEmpty() ? "=> " : " => ") + output.name() + ": " + output.typeName();
		String declaration = "external " + name + "(" + ins + out + ");";

		SourceText source = new SourceText(file.source());
		String nl = source.lineSeparator();
		String text = file.source();
		int lastExternal = -1;
		for (TypeDecl td : file.compilationUnit().typeDecls()) {
			if (td.isExternalFunction() && SourceText.hasPosition(td)) {
				lastExternal = Math.max(lastExternal, source.end(td));
			}
		}
		String newText;
		if (lastExternal >= 0) {
			int eol = endOfLine(text, lastExternal);
			newText = text.substring(0, eol) + nl + declaration + text.substring(eol);
		} else {
			boolean endsWithNewline = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r");
			newText = text + (endsWithNewline ? "" : nl) + declaration + nl;
		}
		LoadedFile after = check(file, newText);
		TypeDecl added = after.program().lookupType(name);
		if (added == null || !added.isExternalFunction()) {
			throw new IllegalStateException("Could not add external function " + name);
		}
		return newText;
	}

	/** A new type name: an identifier no type has (in the file, the standard library or built in). */
	private static void requireNewTypeName(LoadedFile file, String name) {
		if (name == null || !org.bloqqi.compiler.ast.Program.isIdValid(name)) {
			throw new IllegalArgumentException("Not a valid name: \"" + name + "\"");
		}
		if (file.program().lookupType(name) != null) {
			throw new IllegalArgumentException("There is already a type called " + name);
		}
	}

	/** Types offered for new parameters and variables, as in the old editor (spec §3.5, Q11). */
	public static final List<String> SIMPLE_TYPES = List.of("Int", "Bool", "Real");

	/** Variable kinds: {@code var} (state), {@code input}, {@code output}. */
	public static final List<String> VARIABLE_KINDS = List.of("var", "input", "output");

	/**
	 * Feeds a literal into a free target (docs/editor-spec.md §3.3): appends
	 * {@code connect(<value>, <target>);}. The value must be one {@code true},
	 * {@code false}, integer, decimal or string token ({@code Program.parseLiteral});
	 * a value of the wrong type is refused by the reparse check.
	 */
	public static String addLiteral(LoadedFile file, String diagramTypeName, Endpoint target, String value) {
		requireEditable(file);
		String text = value == null ? "" : value.trim();
		if (org.bloqqi.compiler.ast.Program.parseLiteral(text) == null) {
			throw new IllegalArgumentException("Not a literal: \"" + text
					+ "\" (expected e.g. 3, 2.5, true, false or \"text\")");
		}
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		Anchor t = diagram.anchor(target);
		if (t == null) {
			throw new IllegalArgumentException("No such handle: " + target);
		}
		if (!DiagramBuilder.canBeTarget(t)) {
			throw new IllegalArgumentException(DiagramBuilder.ingoing(t).isEmpty()
					? "A literal cannot be connected to " + target
					: target + " already has an incoming connection");
		}
		DiagramType dt = diagram.diagramType();
		String newText = insertStatement(new SourceText(file.source()), dt,
				"connect(" + text + ", " + accessText(t) + ");", bodyStatements(dt));
		LoadedFile after = check(file, newText);
		boolean connected = DiagramBuilder.build(after, diagramTypeName).edges().stream().anyMatch(e ->
				e.fromLiteral() && e.target().equals(target.node()) && e.targetHandle().equals(target.handle()));
		if (!connected) {
			throw new IllegalStateException("Could not connect " + text + " to " + target);
		}
		return newText;
	}

	/**
	 * Adds a variable {@code <kind> <name>: <type>;} after the type's last
	 * variable declaration (or first in the body) and stores its position.
	 */
	public static String addVariable(LoadedFile file, String diagramTypeName, String kind, String name,
			String typeName, int x, int y) {
		requireEditable(file);
		if (!VARIABLE_KINDS.contains(kind)) {
			throw new IllegalArgumentException("Unknown kind of variable: " + kind);
		}
		DiagramType dt = topLevelDiagramType(file, diagramTypeName);
		requireNewName(dt, name);
		requireSimpleType(typeName);
		Diagram before = DiagramBuilder.build(file, diagramTypeName);
		List<ASTNode<?>> variables = new ArrayList<>();
		dt.getLocalVariableList().forEach(variables::add);
		String withVariable = insertStatement(new SourceText(file.source()), dt,
				kind + " " + name + ": " + typeName + ";", variables);
		return placeNew(before, check(file, withVariable), diagramTypeName, name, x, y);
	}

	/**
	 * Adds an in- or out-parameter {@code <name>: <type>} to the type's header
	 * (written {@code <type> <name>} if the header already uses that order),
	 * and stores its position.
	 */
	public static String addParameter(LoadedFile file, String diagramTypeName, boolean in, String name,
			String typeName, int x, int y) {
		requireEditable(file);
		DiagramType dt = topLevelDiagramType(file, diagramTypeName);
		requireNewName(dt, name);
		requireSimpleType(typeName);
		Diagram before = DiagramBuilder.build(file, diagramTypeName);
		SourceText source = new SourceText(file.source());
		Header h = header(source, dt);
		String parameter = h.typeFirst() ? typeName + " " + name : name + ": " + typeName;

		TextEdit edit;
		List<ASTNode<?>> ins = positioned(dt.getLocalInParameterList());
		List<ASTNode<?>> outs = positioned(dt.getLocalOutParameterList());
		if (in) {
			if (!ins.isEmpty()) {
				int at = source.end(ins.get(ins.size() - 1));
				edit = new TextEdit(at, at, ", " + parameter);
			} else if (h.lpar() >= 0) {
				edit = new TextEdit(h.lpar() + 1, h.lpar() + 1, parameter + (outs.isEmpty() ? "" : " "));
			} else {
				edit = new TextEdit(h.nameEnd(), h.nameEnd(), "(" + parameter + ")");
			}
		} else {
			if (!outs.isEmpty()) {
				int at = source.end(outs.get(outs.size() - 1));
				edit = new TextEdit(at, at, ", " + parameter);
			} else if (h.lpar() >= 0) {
				edit = new TextEdit(h.rpar(), h.rpar(), (ins.isEmpty() ? "=> " : " => ") + parameter);
			} else {
				edit = new TextEdit(h.nameEnd(), h.nameEnd(), "(=> " + parameter + ")");
			}
		}
		String withParameter = apply(file.source(), List.of(edit));
		return placeNew(before, check(file, withParameter), diagramTypeName, name, x, y);
	}

	private static void requireNewName(DiagramType dt, String name) {
		if (name == null || !org.bloqqi.compiler.ast.Program.isIdValid(name)) {
			throw new IllegalArgumentException("Not a valid name: \"" + name + "\"");
		}
		if (dt.lookup(name) != null) {
			throw new IllegalArgumentException("The name " + name + " is already used in " + dt.name());
		}
	}

	private static void requireSimpleType(String typeName) {
		if (!SIMPLE_TYPES.contains(typeName)) {
			throw new IllegalArgumentException("The type must be one of " + SIMPLE_TYPES + ", not " + typeName);
		}
	}

	/** The written (positioned) children of a list. */
	private static List<ASTNode<?>> positioned(ASTNode<?> list) {
		List<ASTNode<?>> result = new ArrayList<>();
		for (int i = 0; i < list.getNumChild(); i++) {
			ASTNode<?> child = list.getChild(i);
			if (SourceText.hasPosition(child)) {
				result.add(child);
			}
		}
		return result;
	}

	/** Statements of a type's body, in the grammar's order: variables, blocks, connections. */
	private static List<ASTNode<?>> bodyStatements(DiagramType dt) {
		List<ASTNode<?>> statements = new ArrayList<>();
		dt.getLocalVariableList().forEach(statements::add);
		dt.getLocalBlockList().forEach(statements::add);
		dt.getFlowDeclList().forEach(statements::add);
		return statements;
	}

	/**
	 * Where things are in a type's header {@code diagramtype Name(a: Int => b: Int)}:
	 * the offset after the name, of '(' and ')' (-1 if there are no parentheses),
	 * of {@code =>} (-1 if none), and whether parameters are written type first.
	 */
	record Header(int nameEnd, int lpar, int rpar, int arrow, boolean typeFirst) {
	}

	static Header header(SourceText source, DiagramType dt) {
		int from = source.start(dt);
		int open = bodyBraces(source, dt)[0];
		int nameEnd = -1;
		int lpar = -1;
		int rpar = -1;
		int arrow = -1;
		boolean afterKeyword = false;
		BloqqiScanner scanner = new BloqqiScanner(new StringReader(source.text()));
		try {
			for (Symbol token = scanner.nextToken(); token.getId() != BloqqiParser.Terminals.EOF;
					token = scanner.nextToken()) {
				int start = source.offset(Symbol.getLine(token.getStart()), Symbol.getColumn(token.getStart()));
				if (start < from) {
					continue;
				}
				if (start >= open) {
					break;
				}
				int end = source.offset(Symbol.getLine(token.getEnd()), Symbol.getColumn(token.getEnd())) + 1;
				short id = token.getId();
				if (id == BloqqiParser.Terminals.DIAGRAMTYPE) {
					afterKeyword = true;
				} else if (afterKeyword && nameEnd < 0 && id == BloqqiParser.Terminals.ID) {
					nameEnd = end;
				} else if (nameEnd >= 0 && lpar < 0 && rpar < 0 && id == BloqqiParser.Terminals.LPAR
						&& start == skipSpaces(source.text(), nameEnd)) {
					lpar = start;
				} else if (lpar >= 0 && rpar < 0 && id == BloqqiParser.Terminals.ARROW) {
					arrow = start;
				} else if (lpar >= 0 && rpar < 0 && id == BloqqiParser.Terminals.RPAR) {
					rpar = start;
				}
			}
		} catch (IOException | beaver.Scanner.Exception | BloqqiScanner.ScannerError e) {
			throw new IllegalStateException("Could not scan " + dt.name(), e);
		}
		if (nameEnd < 0) {
			throw new IllegalStateException("No header found for " + dt.name());
		}
		boolean typeFirst = false;
		for (List<ASTNode<?>> params : List.of(positioned(dt.getLocalInParameterList()),
				positioned(dt.getLocalOutParameterList()))) {
			for (ASTNode<?> p : params) {
				String typeName = ((org.bloqqi.compiler.ast.Parameter) p).getTypeUse().getID();
				String text = source.text(p);
				typeFirst = text.startsWith(typeName) && text.length() > typeName.length()
						&& Character.isWhitespace(text.charAt(typeName.length()));
			}
		}
		return new Header(nameEnd, lpar, rpar, arrow, typeFirst);
	}

	private static int skipSpaces(String text, int from) {
		int i = from;
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
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
		String newText = insertStatement(new SourceText(file.source()), dt, statement, bodyStatements(dt));
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

		List<ASTNode<?>> statements = new ArrayList<>(); // blocks and variables
		Set<ASTNode<?>> parameters = new HashSet<>();
		Set<String> blockIds = new HashSet<>(); // all deleted nodes
		for (String id : nodeIds) {
			org.bloqqi.compiler.ast.Node n = diagram.node(id);
			if (n == null) {
				throw new IllegalArgumentException("No block, variable or parameter " + id + " in " + dt.name());
			}
			if (!DiagramBuilder.isDeletable(n, dt)) {
				throw new IllegalArgumentException(id + " is not declared in " + dt.name()
						+ " (it is inherited or inlined)");
			}
			ASTNode<?> declared = DiagramBuilder.declaration(n);
			if (n instanceof org.bloqqi.compiler.ast.Parameter) {
				parameters.add(declared);
			} else {
				statements.add(declared);
			}
			blockIds.add(id);
		}
		// The connections on the deleted nodes (and literals feeding them).
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
		if (!statements.isEmpty()) {
			int close = bodyBraces(source, dt)[1];
			for (ASTNode<?> b : statements) {
				int start = source.start(b);
				int end = semicolonAfter(source.text(), source.end(b), close);
				int[] r = statementLines(source, start, end);
				edits.add(new TextEdit(r[0], r[1], ""));
			}
		}
		if (!parameters.isEmpty()) {
			edits.add(parameterListEdit(source, dt, parameters));
		}
		if (!blockIds.isEmpty()) {
			TreeMap<String, Layout.Rect> entries = annotations(source, dt);
			if (entries.keySet().removeAll(blockIds)) {
				edits.add(annotationEdit(source, dt, entries));
			}
		}
		String newText = apply(file.source(), edits);
		check(file, newText);
		return newText;
	}

	/**
	 * Rewrites the text between the header's parentheses without the removed
	 * parameters, from the remaining parameters' own text: {@code a: Int => b: Int}.
	 */
	private static TextEdit parameterListEdit(SourceText source, DiagramType dt, Set<ASTNode<?>> removed) {
		Header h = header(source, dt);
		List<String> ins = new ArrayList<>();
		List<String> outs = new ArrayList<>();
		for (ASTNode<?> p : positioned(dt.getLocalInParameterList())) {
			if (!removed.contains(p)) {
				ins.add(source.text(p));
			}
		}
		for (ASTNode<?> p : positioned(dt.getLocalOutParameterList())) {
			if (!removed.contains(p)) {
				outs.add(source.text(p));
			}
		}
		String inner = String.join(", ", ins)
				+ (outs.isEmpty() ? "" : (ins.isEmpty() ? "=> " : " => ") + String.join(", ", outs));
		return new TextEdit(h.lpar() + 1, h.rpar(), inner);
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
