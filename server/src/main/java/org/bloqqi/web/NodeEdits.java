package org.bloqqi.web;

import static org.bloqqi.web.DeleteEdits.*;
import static org.bloqqi.web.LayoutEdits.*;
import static org.bloqqi.web.SourceEdits.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.Anchor;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.InheritedConnection;
import org.bloqqi.web.Diagram.DiagramEdge;
import org.bloqqi.web.DiagramBuilder.Endpoint;

/**
 * Edits of nodes: blocks, literals, variables and parameters (adding them,
 * a literal's value, the order of a type's parameters).
 */
public final class NodeEdits {
	private NodeEdits() {
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
	 * Sets the order of a type's own in- and out-parameters (old editor:
	 * Diagram properties, Move up/down/Remove). The lists name the type's
	 * local parameters in their new order; local parameters left out are
	 * deleted as by {@link DeleteEdits#delete} (with their connections and positions).
	 * Inherited parameters cannot be reordered here: they come first, in the
	 * super types' order. Only the text between the header's parentheses is
	 * rewritten, from each parameter's own text.
	 *
	 * @throws IllegalArgumentException if a name is not a local parameter of that
	 *         direction or is listed twice, or the edit adds errors
	 */
	public static String setParameters(LoadedFile file, String diagramTypeName, List<String> inParameters,
			List<String> outParameters) {
		requireEditable(file);
		DiagramType dt = topLevelDiagramType(file, diagramTypeName);
		List<String> localIns = parameterNames(dt.getLocalInParameterList());
		List<String> localOuts = parameterNames(dt.getLocalOutParameterList());
		Set<String> seen = new HashSet<>();
		for (String name : inParameters) {
			requireListed(name, localIns, "in-parameter", dt, seen);
		}
		for (String name : outParameters) {
			requireListed(name, localOuts, "out-parameter", dt, seen);
		}
		List<String> removed = new ArrayList<>();
		localIns.stream().filter(n -> !inParameters.contains(n)).forEach(removed::add);
		localOuts.stream().filter(n -> !outParameters.contains(n)).forEach(removed::add);

		LoadedFile current = file;
		if (!removed.isEmpty()) {
			current = check(file, delete(file, diagramTypeName, List.of(), removed));
		}
		if (inParameters.isEmpty() && outParameters.isEmpty()) {
			return current.source();
		}
		DiagramType reordered = topLevelDiagramType(current, diagramTypeName);
		SourceText source = new SourceText(current.source());
		Map<String, String> texts = new HashMap<>();
		for (ASTNode<?> p : positioned(reordered.getLocalInParameterList())) {
			texts.put("in:" + ((org.bloqqi.compiler.ast.Parameter) p).name(), source.text(p));
		}
		for (ASTNode<?> p : positioned(reordered.getLocalOutParameterList())) {
			texts.put("out:" + ((org.bloqqi.compiler.ast.Parameter) p).name(), source.text(p));
		}
		List<String> ins = inParameters.stream().map(n -> texts.get("in:" + n)).toList();
		List<String> outs = outParameters.stream().map(n -> texts.get("out:" + n)).toList();
		String inner = String.join(", ", ins)
				+ (outs.isEmpty() ? "" : (ins.isEmpty() ? "=> " : " => ") + String.join(", ", outs));
		Header h = header(source, reordered);
		String newText = apply(current.source(), List.of(new TextEdit(h.lpar() + 1, h.rpar(), inner)));
		check(file, newText);
		return newText;
	}

	private static List<String> parameterNames(ASTNode<?> list) {
		return positioned(list).stream().map(p -> ((org.bloqqi.compiler.ast.Parameter) p).name()).toList();
	}

	private static void requireListed(String name, List<String> locals, String kind, DiagramType dt, Set<String> seen) {
		if (!locals.contains(name)) {
			throw new IllegalArgumentException(name + " is not an " + kind + " declared in " + dt.name()
					+ " (inherited parameters cannot be reordered here)");
		}
		if (!seen.add(name)) {
			throw new IllegalArgumentException(name + " is listed twice");
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

	static void requireNewName(DiagramType dt, String name) {
		if (name == null || !org.bloqqi.compiler.ast.Program.isIdValid(name)) {
			throw new IllegalArgumentException("Not a valid name: \"" + name + "\"");
		}
		if (dt.lookup(name) != null) {
			throw new IllegalArgumentException("The name " + name + " is already used in " + dt.name());
		}
	}

	static void requireSimpleType(String typeName) {
		if (!SIMPLE_TYPES.contains(typeName)) {
			throw new IllegalArgumentException("The type must be one of " + SIMPLE_TYPES + ", not " + typeName);
		}
	}
}
