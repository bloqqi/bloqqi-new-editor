package org.bloqqi.web;

import static org.bloqqi.web.SourceEdits.*;

import org.bloqqi.compiler.ast.Anchor;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.InheritedConnection;
import org.bloqqi.web.DiagramBuilder.Endpoint;

/**
 * Creating connections, and intercepting inherited ones.
 */
public final class ConnectionEdits {
	private ConnectionEdits() {
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

	/**
	 * Intercepts an inherited connection (docs/editor-spec.md §3.7): adds
	 * {@code intercept X with T, S;} to the type, where X is the connection's
	 * target, so that the inherited connection {@code A -> X} becomes
	 * {@code A -> T} and {@code S -> X}; or, {@code atSource},
	 * {@code intercept source X with T, S;} where X is the connection's source,
	 * which adds {@code X -> T} and makes the inherited connections from X come
	 * from S. T must be a free input and S an output; the compiler checks the
	 * types (and that X is not already intercepted here) through the reparse check.
	 */
	public static String intercept(LoadedFile file, String diagramTypeName, String edgeId, boolean atSource,
			Endpoint into, Endpoint outOf) {
		requireEditable(file);
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		DiagramType dt = diagram.diagramType();
		InheritedConnection c = diagram.connection(edgeId);
		if (c == null) {
			throw new IllegalArgumentException("No connection " + edgeId + " in " + dt.name());
		}
		if (!c.isInherited() || c.isInlined()) {
			throw new IllegalArgumentException("Only inherited connections can be intercepted; " + edgeId
					+ " is declared in " + dt.name() + (c.isInlined() ? " (inside an inlined block)" : ""));
		}
		Anchor x = atSource ? c.getSource().anchor() : c.getTarget().anchor();
		if (x == null || x instanceof org.bloqqi.compiler.ast.Literal) {
			throw new IllegalArgumentException("A connection from a literal cannot be intercepted at its source");
		}
		Anchor t = diagram.anchor(into);
		Anchor s = diagram.anchor(outOf);
		if (t == null || s == null) {
			throw new IllegalArgumentException("No such handle: " + (t == null ? into : outOf));
		}
		if (!DiagramBuilder.canBeTarget(t)) {
			throw new IllegalArgumentException(DiagramBuilder.ingoing(t).isEmpty()
					? "The interception cannot go into " + into
					: into + " already has an incoming connection");
		}
		if (!DiagramBuilder.canBeSource(s)) {
			throw new IllegalArgumentException("The interception cannot come out of " + outOf);
		}
		String statement = "intercept " + (atSource ? "source " : "") + accessText(x) + " with "
				+ accessText(t) + ", " + accessText(s) + ";";
		String newText = insertStatement(new SourceText(file.source()), dt, statement, bodyStatements(dt));
		LoadedFile after = check(file, newText);
		boolean intoConnected = DiagramBuilder.build(after, diagramTypeName).edges().stream()
				.anyMatch(e -> e.target().equals(into.node()) && e.targetHandle().equals(into.handle()));
		if (!intoConnected) {
			throw new IllegalStateException("Could not intercept " + edgeId + " (" + statement + ")");
		}
		return newText;
	}
}
