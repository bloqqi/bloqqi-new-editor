package org.bloqqi.web;

import java.util.List;

/**
 * The view of one diagram type, as sent to the client.
 *
 * @param file         file name in the workspace
 * @param diagramType  name of the shown diagram type, or null if the file has none (or did not parse)
 * @param diagramTypes names of all top-level diagram types in the file
 * @param nodes        parameters, blocks, variables and literals
 * @param edges        one edge per connection in {@code DiagramType.getConnections()}
 * @param problems     syntax and semantic errors of the whole file
 * @param readOnly     why the diagram cannot be edited, or null if it can
 *                     ({@link DiagramEdits#readOnlyReason})
 * @param palette      the types a block can be added of, in the old editor's order
 * @param superTypes   the names in the shown type's {@code extends} clause
 */
public record Diagram(
		String file,
		String diagramType,
		List<String> diagramTypes,
		List<DiagramNode> nodes,
		List<DiagramEdge> edges,
		List<Problem> problems,
		String readOnly,
		List<PaletteEntry> palette,
		List<String> superTypes) {

	/** Palette groups. */
	public static final String LOCAL = "local";
	public static final String STANDARD = "standard";

	/**
	 * A type in the palette (docs/editor-spec.md §3.1): the file's own diagram
	 * types (not the shown one, not abstract), functions, external functions and
	 * state machines, then the standard library's.
	 *
	 * @param name  the type's name
	 * @param group {@link #LOCAL} or {@link #STANDARD}
	 * @param kind  {@code diagramType}, {@code function}, {@code externalFunction} or {@code stateMachine}
	 * @param doc   first line of its doc string, or null
	 */
	public record PaletteEntry(String name, String group, String kind, String doc) {
	}

	/** Kinds of nodes on the canvas. */
	public static final String BLOCK = "block";
	public static final String IN_PARAMETER = "inParameter";
	public static final String OUT_PARAMETER = "outParameter";
	public static final String VARIABLE = "variable";
	public static final String LITERAL = "literal";

	/**
	 * Handle names. Blocks have one handle per port, {@code in:<port>} on the
	 * left and {@code out:<port>} on the right. Parameters, variables and
	 * literals have a single {@code in} (left) and {@code out} (right) handle.
	 */
	public static final String IN = "in";
	public static final String OUT = "out";

	static String inPortHandle(String port) {
		return IN + ":" + port;
	}

	static String outPortHandle(String port) {
		return OUT + ":" + port;
	}

	/**
	 * A node on the canvas.
	 *
	 * @param id        {@code Node.accessString()}: {@code name}, {@code Super::name}
	 *                  for inherited nodes, {@code outer$inner} for inlined nodes.
	 *                  Literals have no name; their id is {@code #literal:<target node>.<target handle>}.
	 * @param kind      one of {@link #BLOCK}, {@link #IN_PARAMETER}, {@link #OUT_PARAMETER},
	 *                  {@link #VARIABLE}, {@link #LITERAL}
	 * @param label     text shown on the node
	 * @param typeName  name of the node's type
	 * @param inherited declared in a supertype
	 * @param inlined   produced by inlining a block
	 * @param error     block on a type cycle or a dataflow cycle
	 * @param x         position and size of the node's body; block ports stick out
	 *                  {@link Layout#PORT_SIZE} pixels on either side
	 * @param inPorts   input ports of a block (empty for other kinds)
	 * @param outPorts  output ports of a block (empty for other kinds)
	 * @param inConnectable  a new connection may end at the {@code in} handle (not for blocks;
	 *                       see {@link DiagramPort#connectable})
	 * @param outConnectable a new connection may start at the {@code out} handle
	 * @param deletable      can be deleted here: a block declared in this type's body
	 *                       (not inherited or inlined)
	 * @param featureBase    for a block whose type declares optional features (directly, or
	 *                       through its anonymous type {@code b: B { feature f; }}): that
	 *                       type's name (see {@link FeatureEdits}); otherwise null
	 */
	public record DiagramNode(
			String id,
			String kind,
			String label,
			String typeName,
			boolean inherited,
			boolean inlined,
			boolean error,
			int x,
			int y,
			int width,
			int height,
			List<DiagramPort> inPorts,
			List<DiagramPort> outPorts,
			boolean inConnectable,
			boolean outConnectable,
			boolean deletable,
			String featureBase) {
	}

	/**
	 * A port of a block.
	 *
	 * @param name     parameter name of the block's type
	 * @param typeName type of the port
	 * @param y        top of the 6×6 port square, relative to the block's top
	 * @param connectable a new connection may end at this in-port (it can be named
	 *                 here and has no incoming connection) or start at this out-port
	 */
	public record DiagramPort(String name, String typeName, int y, boolean connectable) {
	}

	/**
	 * A connection.
	 *
	 * @param id           {@code <source>.<sourceHandle>-><target>.<targetHandle>}, suffixed
	 *                     with {@code #n} if the same endpoints are connected more than once
	 * @param inherited    inherited, or inside an inlined block
	 *                     ({@code !Connection.canDelete()}); drawn dashed black
	 * @param deletable    can be deleted here: not inherited, and declared by a
	 *                     statement in this type's body (not, e.g., by a wiring)
	 * @param interceptable can be intercepted here: inherited and not inside an
	 *                     inlined block (docs/editor-spec.md §3.7)
	 * @param error        {@code hasErrors() || isOnDataflowCycle()}; drawn red
	 * @param fromLiteral  the source is a literal (no arrow head)
	 * @param intercept    null, or {@code "first"}/{@code "second"} for the two halves of an
	 *                     interception (the half that replaces the inherited connection
	 *                     is drawn like an inherited connection)
	 * @param largeMarker  for intercept halves: draw the large midpoint marker
	 */
	public record DiagramEdge(
			String id,
			String source,
			String sourceHandle,
			String target,
			String targetHandle,
			boolean inherited,
			boolean deletable,
			boolean interceptable,
			boolean error,
			boolean fromLiteral,
			String intercept,
			boolean largeMarker) {
	}
}
