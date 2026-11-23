package org.bloqqi.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.Anchor;
import org.bloqqi.compiler.ast.Block;
import org.bloqqi.compiler.ast.CompilationUnit;
import org.bloqqi.compiler.ast.ConnectionInterception;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.InheritedBlock;
import org.bloqqi.compiler.ast.InheritedConnection;
import org.bloqqi.compiler.ast.InheritedInParameter;
import org.bloqqi.compiler.ast.InheritedOutParameter;
import org.bloqqi.compiler.ast.InheritedVariable;
import org.bloqqi.compiler.ast.InterceptConnection;
import org.bloqqi.compiler.ast.Literal;
import org.bloqqi.compiler.ast.Node;
import org.bloqqi.compiler.ast.Parameter;
import org.bloqqi.compiler.ast.Port;
import org.bloqqi.compiler.ast.TypeDecl;
import org.bloqqi.compiler.ast.Variable;
import org.bloqqi.web.Diagram.DiagramEdge;
import org.bloqqi.web.Diagram.DiagramNode;
import org.bloqqi.web.Diagram.DiagramPort;

/**
 * Derives the client's view of a diagram type from the AST and its attributes
 * (docs/editor-spec.md §2): parameters, blocks with ports, variables, literals
 * and connections.
 */
public final class DiagramBuilder {
	/** Gap between a literal and the node it feeds (LiteralPart.refreshVisuals). */
	private static final int LITERAL_GAP = 12;
	private static final int LITERAL_HEIGHT = 15;
	private static final int LITERAL_CHAR_WIDTH = 7;

	/** One end of a connection: a node id and one of its handles. */
	record Endpoint(String node, String handle) {
		@Override
		public String toString() {
			return node + "." + handle;
		}
	}

	private DiagramBuilder() {
	}

	/** Names of the top-level diagram types in the file, in declaration order. */
	public static List<String> diagramTypeNames(CompilationUnit cu) {
		List<String> names = new ArrayList<>();
		for (TypeDecl td : cu.typeDecls()) {
			if (td.isDiagramType()) {
				names.add(td.name());
			}
		}
		return names;
	}

	/**
	 * Builds the diagram for {@code diagramTypeName}, or for the first diagram
	 * type in the file if it is null (as the old editor did).
	 *
	 * @throws IllegalArgumentException if the named diagram type does not exist
	 */
	public static Diagram build(LoadedFile file, String diagramTypeName) {
		List<Problem> problems = new ArrayList<>(file.syntaxErrors());
		problems.addAll(file.semanticErrors());
		if (!file.isParsed()) {
			return new Diagram(file.fileName(), null, List.of(), List.of(), List.of(), problems,
					DiagramEdits.readOnlyReason(file));
		}

		CompilationUnit cu = file.compilationUnit();
		List<String> names = diagramTypeNames(cu);
		DiagramType dt = find(cu, diagramTypeName != null ? diagramTypeName : names.isEmpty() ? null : names.get(0));
		if (dt == null) {
			if (diagramTypeName != null) {
				throw new IllegalArgumentException(
						"No diagram type " + diagramTypeName + " in " + file.fileName());
			}
			return new Diagram(file.fileName(), null, names, List.of(), List.of(), problems,
					DiagramEdits.readOnlyReason(file));
		}
		return new Builder(dt).build(file.fileName(), names, problems, DiagramEdits.readOnlyReason(file));
	}

	/**
	 * The diagram of a top-level diagram type together with the AST elements
	 * behind it, for diagram edits: connections by edge id, anchors by handle.
	 *
	 * @throws IllegalArgumentException if the type does not exist
	 */
	static Builder resolve(LoadedFile file, String diagramTypeName) {
		DiagramType dt = find(file.compilationUnit(), diagramTypeName);
		if (dt == null) {
			throw new IllegalArgumentException("No diagram type " + diagramTypeName + " in " + file.fileName());
		}
		Builder b = new Builder(dt);
		b.build(file.fileName(), List.of(), List.of(), null);
		return b;
	}

	static final class Builder {
		private final DiagramType dt;
		/** Filled by {@link #build}: the connection behind each edge. */
		private final Map<String, InheritedConnection> connectionsById = new LinkedHashMap<>();
		/** Parameters, blocks and variables, in the old editor's order. */
		private final List<Node> nodes = new ArrayList<>();
		private final Map<String, String> labels = new HashMap<>();
		private final Map<String, Node> nodesById = new HashMap<>();

		Builder(DiagramType dt) {
			this.dt = dt;
			for (InheritedInParameter p : dt.getInParameters()) {
				add(p, p.name());
			}
			for (InheritedOutParameter p : dt.getOutParameters()) {
				add(p, p.name());
			}
			for (InheritedBlock b : dt.getBlocks()) {
				add(b, label(b));
			}
			for (InheritedVariable v : dt.getVariables()) {
				add(v, v.prettyPrintKind() + " " + v.inlinedName());
			}
		}

		private void add(Node n, String label) {
			nodes.add(n);
			labels.put(n.accessString(), label);
			nodesById.put(n.accessString(), n);
		}

		Diagram build(String fileName, List<String> names, List<Problem> problems, String readOnly) {
			List<Layout.Link> links = new ArrayList<>();
			for (InheritedConnection c : dt.getConnections()) {
				Endpoint s = endpoint(c.getSource().anchor(), true);
				Endpoint t = endpoint(c.getTarget().anchor(), false);
				if (s != null && t != null) {
					links.add(new Layout.Link(s, t));
				}
			}
			Map<String, Layout.Rect> rects = new Layout(dt).layout(nodes, labels, links);

			List<DiagramNode> result = new ArrayList<>();
			for (Node n : nodes) {
				result.add(node(n, rects.get(n.accessString())));
			}

			List<DiagramEdge> edges = new ArrayList<>();
			Map<String, Integer> seen = new HashMap<>();
			Map<String, DiagramNode> literals = new LinkedHashMap<>();
			for (InheritedConnection c : dt.getConnections()) {
				Endpoint t = endpoint(c.getTarget().anchor(), false);
				if (t == null) {
					continue; // Unresolved target: reported as a problem by errors().
				}
				Endpoint s;
				boolean fromLiteral = c.getSource() instanceof Literal;
				if (fromLiteral) {
					Literal lit = (Literal) c.getSource();
					DiagramNode ln = literal(lit, t, rects.get(t.node()));
					if (ln == null) {
						continue;
					}
					literals.putIfAbsent(ln.id(), ln);
					s = new Endpoint(ln.id(), Diagram.OUT);
				} else {
					s = endpoint(c.getSource().anchor(), true);
					if (s == null) {
						continue;
					}
				}
				String id = s + "->" + t;
				int n = seen.merge(id, 1, Integer::sum);
				if (n > 1) {
					id += "#" + n;
				}
				String intercept = null;
				boolean largeMarker = false;
				if (c instanceof InterceptConnection ic) {
					boolean first = ic.getIsFirstConnection();
					boolean connectionInterception = ic.getDeclaredFlowDecl() instanceof ConnectionInterception;
					intercept = first ? "first" : "second";
					// ConnectionPart.refreshVisuals
					largeMarker = first ? !connectionInterception : connectionInterception;
				}
				connectionsById.put(id, c);
				edges.add(new DiagramEdge(
						id, s.node(), s.handle(), t.node(), t.handle(),
						!c.canDelete(),
						isDeletable(c, dt),
						c.hasErrors() || c.isOnDataflowCycle(),
						fromLiteral,
						c.isIntercept() ? intercept : null,
						largeMarker));
			}
			result.addAll(literals.values());
			return new Diagram(fileName, dt.name(), names, result, edges, problems, readOnly);
		}

		DiagramType diagramType() {
			return dt;
		}

		/** The connection behind an edge id, or null. */
		InheritedConnection connection(String edgeId) {
			return connectionsById.get(edgeId);
		}

		/**
		 * The anchor behind a handle: a port for {@code in:<port>}/{@code out:<port>}
		 * on a block, the parameter or variable itself for {@code in}/{@code out}.
		 * Null if there is no such handle.
		 */
		Anchor anchor(Endpoint e) {
			Node n = nodesById.get(e.node());
			if (n instanceof Block b) {
				for (Port p : b.getInPorts()) {
					if (Diagram.inPortHandle(p.name()).equals(e.handle())) {
						return p;
					}
				}
				for (Port p : b.getOutPorts()) {
					if (Diagram.outPortHandle(p.name()).equals(e.handle())) {
						return p;
					}
				}
				return null;
			}
			if (n instanceof Anchor a && (Diagram.IN.equals(e.handle()) || Diagram.OUT.equals(e.handle()))) {
				return a;
			}
			return null;
		}

		/** The handle a connection attaches to, or null if it is not on a shown node. */
		private Endpoint endpoint(Anchor a, boolean isSource) {
			if (a instanceof Port p) {
				String id = p.block().accessString();
				if (!nodesById.containsKey(id)) {
					return null;
				}
				return new Endpoint(id, p.isInParameter()
						? Diagram.inPortHandle(p.name())
						: Diagram.outPortHandle(p.name()));
			}
			if (a instanceof Parameter || a instanceof Variable) {
				String id = ((Node) a).accessString();
				if (!nodesById.containsKey(id)) {
					return null;
				}
				return new Endpoint(id, isSource ? Diagram.OUT : Diagram.IN);
			}
			return null;
		}

		private DiagramNode node(Node n, Layout.Rect r) {
			String id = n.accessString();
			String kind;
			String typeName;
			boolean error = false;
			boolean inConnectable = false;
			boolean outConnectable = false;
			List<DiagramPort> in = List.of();
			List<DiagramPort> out = List.of();
			if (n instanceof Block b) {
				kind = Diagram.BLOCK;
				typeName = b.type().name();
				error = b.isOnTypeCycle() || b.isDataflowCircular();
				in = ports(b.getInPorts());
				out = ports(b.getOutPorts());
			} else if (n instanceof Parameter p) {
				kind = p.isInParameter() ? Diagram.IN_PARAMETER : Diagram.OUT_PARAMETER;
				typeName = p.type().name();
				outConnectable = canBeSource(p);
				inConnectable = canBeTarget(p);
			} else {
				Variable v = (Variable) n;
				kind = Diagram.VARIABLE;
				typeName = v.type().name();
				outConnectable = canBeSource(v);
				inConnectable = canBeTarget(v);
			}
			return new DiagramNode(id, kind, labels.get(id), typeName,
					n.isInherited(), n.isInlined(), error,
					r.x(), r.y(), r.width(), r.height(), in, out, inConnectable, outConnectable);
		}

		/** A literal is drawn left of its target, level with the target port (LiteralPart). */
		private DiagramNode literal(Literal lit, Endpoint target, Layout.Rect r) {
			if (r == null) {
				return null;
			}
			String text = lit.toString();
			int width = text.length() * LITERAL_CHAR_WIDTH + 4;
			int y = r.y() + (r.height() - LITERAL_HEIGHT) / 2;
			Node targetNode = nodesById.get(target.node());
			if (targetNode instanceof Block b) {
				for (Port p : b.getInPorts()) {
					if (Diagram.inPortHandle(p.name()).equals(target.handle())) {
						y = r.y() + Layout.portY(p.index()) + Layout.PORT_SIZE / 2 - LITERAL_HEIGHT / 2;
					}
				}
			}
			int x = r.x() - width - LITERAL_GAP;
			return new DiagramNode("#literal:" + target, Diagram.LITERAL, text, lit.type().name(),
					false, false, false, x, y, width, LITERAL_HEIGHT, List.of(), List.of(), false, false);
		}

		private static List<DiagramPort> ports(Iterable<Port> ports) {
			List<DiagramPort> result = new ArrayList<>();
			for (Port p : ports) {
				boolean connectable = p.isInParameter() ? canBeTarget(p) : canBeSource(p);
				result.add(new DiagramPort(p.name(), p.type().name(), Layout.portY(p.index()), connectable));
			}
			return result;
		}
	}

	/**
	 * Whether the connection can be deleted here: {@code Connection.canDelete()}
	 * (not inherited, not inside an inlined block), and the statement declaring
	 * it is written in this type's body. The second part matters for wired
	 * blocks: the compiler copies a {@code wiring} declaration's statements into
	 * the type, with their original source positions, which are outside the body.
	 */
	static boolean isDeletable(InheritedConnection c, DiagramType dt) {
		org.bloqqi.compiler.ast.FlowDecl declared = c.getDeclaredFlowDecl();
		return c.canDelete() && declared != null && isDeclaredIn(declared, dt);
	}

	/** Whether the statement is one of those written in the body of the type. */
	static boolean isDeclaredIn(org.bloqqi.compiler.ast.FlowDecl d, DiagramType dt) {
		// (Not getIndexOfChild: it can report an index for a node of another list.)
		var list = dt.getFlowDeclList();
		return d.getParent() == list && SourceText.hasPosition(list) && SourceText.hasPosition(d)
				&& SourceText.compare(d.getStartLine(), d.getStartColumn(), list.getStartLine(), list.getStartColumn()) >= 0
				&& SourceText.compare(d.getEndLine(), d.getEndColumn(), list.getEndLine(), list.getEndColumn()) <= 0;
	}

	/**
	 * Whether a new connection may start at the anchor: an out-port, an
	 * in-parameter or a variable that can be named here ({@code canAccess()};
	 * not, e.g., ports inside inlined blocks).
	 */
	static boolean canBeSource(Anchor a) {
		boolean kind = a instanceof Port p ? !p.isInParameter()
				: a instanceof Parameter p ? p.isInParameter()
				: a instanceof Variable;
		return kind && a.canAccess();
	}

	/**
	 * Whether a new connection may end at the anchor: an in-port, an
	 * out-parameter or a variable that can be named here and has no incoming
	 * connection yet.
	 */
	static boolean canBeTarget(Anchor a) {
		boolean kind = a instanceof Port p ? p.isInParameter()
				: a instanceof Parameter p ? !p.isInParameter()
				: a instanceof Variable;
		return kind && a.canAccess() && ingoing(a).isEmpty();
	}

	/** {@code ingoingConnections()} is generated on the concrete anchor classes only. */
	static java.util.Set<org.bloqqi.compiler.ast.Connection> ingoing(Anchor a) {
		if (a instanceof Port p) {
			return p.ingoingConnections();
		}
		if (a instanceof Parameter p) {
			return p.ingoingConnections();
		}
		if (a instanceof Variable v) {
			return v.ingoingConnections();
		}
		return java.util.Set.of();
	}

	/** Block label, following the old editor (BlockPart.refreshVisuals). */
	static String label(Block b) {
		if (b.getModifiers().isOnSimpleNameForm()) {
			return b.type().name();
		}
		String name = b.inlinedName();
		if (b.isNameAmbiguous()) {
			name = b.declaredInDiagramType().name() + ASTNode.DECLARED_IN_SEP + name;
		}
		return name;
	}

	private static DiagramType find(CompilationUnit cu, String name) {
		if (name == null) {
			return null;
		}
		for (TypeDecl td : cu.typeDecls()) {
			if (td.isDiagramType() && td.name().equals(name)) {
				return (DiagramType) td;
			}
		}
		return null;
	}
}
