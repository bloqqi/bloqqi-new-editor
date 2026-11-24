package org.bloqqi.web;

import static org.bloqqi.web.SourceEdits.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.Anchor;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.FlowDecl;
import org.bloqqi.compiler.ast.InheritedConnection;
import org.bloqqi.compiler.ast.Port;

/**
 * Deleting connections and nodes (with what belongs to them).
 */
public final class DeleteEdits {
	private DeleteEdits() {
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
}
