package org.bloqqi.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bloqqi.compiler.ast.Annotation;
import org.bloqqi.compiler.ast.Block;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.InParameter;
import org.bloqqi.compiler.ast.Node;
import org.bloqqi.compiler.ast.OutParameter;
import org.bloqqi.compiler.ast.Port;
import org.bloqqi.compiler.ast.VarDecl;
import org.eclipse.elk.alg.layered.LayeredLayoutProvider;
import org.eclipse.elk.alg.layered.options.LayerConstraint;
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.data.LayoutMetaDataService;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.util.ElkGraphUtil;

/**
 * Computes node positions (parameters, blocks and variables) for a diagram type.
 * Literals are not laid out; they are placed next to their target (see
 * {@link DiagramBuilder}).
 *
 * <ul>
 * <li>Stored positions come from the {@code annotation(...)} clauses, looked up
 *     like the old editor did: the diagram type itself first, then its supertypes
 *     (see docs/editor-spec.md §6.2).</li>
 * <li>If no node has a stored position, all nodes are laid out with ELK layered
 *     (left to right, ports in fixed positions, connections as edges between ports).</li>
 * <li>If only some nodes have stored positions, the others are placed in a row
 *     below the stored ones.</li>
 * </ul>
 *
 * A rectangle is the node's body. Block ports stick out {@link #PORT_SIZE}
 * pixels on the left and right, as in the old editor.
 */
final class Layout {
	static final int DEFAULT_WIDTH = 60;
	static final int MIN_HEIGHT = 20;
	static final int PORT_SIZE = 6;
	private static final int PORT_PADDING = 8;
	private static final int PORT_Y0 = 5;
	private static final int GAP = 40;
	/** Rough width of a character in the client's node font, for auto-layout sizes. */
	private static final int CHAR_WIDTH = 7;

	record Rect(int x, int y, int width, int height) {
	}

	/** A connection between two handles, as {@link DiagramBuilder} resolves it. */
	record Link(DiagramBuilder.Endpoint source, DiagramBuilder.Endpoint target) {
	}

	private final DiagramType diagramType;
	private final Map<DiagramType, Map<String, Rect>> annotationsByType = new HashMap<>();

	Layout(DiagramType diagramType) {
		this.diagramType = diagramType;
	}

	/**
	 * Positions for the given nodes of the diagram type, keyed by access string.
	 *
	 * @param labels label shown for each node, used to size nodes without a stored size
	 * @param links  connections between the nodes (literal sources are left out)
	 */
	Map<String, Rect> layout(List<? extends Node> nodes, Map<String, String> labels, List<Link> links) {
		Map<String, Rect> result = new LinkedHashMap<>();
		List<Node> missing = new ArrayList<>();
		for (Node n : nodes) {
			Rect r = storedRect(n);
			if (r != null) {
				result.put(n.accessString(), r);
			} else {
				missing.add(n);
			}
		}
		if (result.isEmpty()) {
			return elkLayout(nodes, labels, links);
		}
		placeBelow(result, missing, labels);
		return result;
	}

	/** Top of port {@code index}'s square, relative to the block's top (PortPart.getYPos). */
	static int portY(int index) {
		return PORT_Y0 + index * (PORT_SIZE + PORT_PADDING);
	}

	/** Minimum block height for the number of ports, as in the old editor. */
	static int minHeight(Node node) {
		if (!(node instanceof Block b)) {
			return MIN_HEIGHT;
		}
		int n = Math.max(b.getNumInPort(), b.getNumOutPort());
		if (n == 0) {
			return MIN_HEIGHT;
		}
		return Math.max(MIN_HEIGHT, PORT_Y0 * 2 + n * PORT_SIZE + (n - 1) * PORT_PADDING);
	}

	/** Size used for a node without a stored rectangle: wide enough for its label. */
	private static int defaultWidth(String label) {
		int text = label == null ? 0 : label.length() * CHAR_WIDTH + 10;
		return Math.max(DEFAULT_WIDTH, text);
	}

	/** Nearest stored rectangle, starting with the most specific type. */
	private Rect storedRect(Node node) {
		for (DiagramType t : diagramType.superTypesLinearizedReversed()) {
			String key = t == node.declaredInDiagramType() ? node.name() : node.accessString();
			Rect r = annotations(t).get(key);
			if (r != null) {
				return r;
			}
		}
		return null;
	}

	private Map<String, Rect> annotations(DiagramType t) {
		return annotationsByType.computeIfAbsent(t, Layout::readAnnotations);
	}

	private static Map<String, Rect> readAnnotations(DiagramType t) {
		Map<String, Rect> map = new HashMap<>();
		for (Annotation a : t.getAnnotations()) {
			VarDecl decl = a.getVarUse().decl();
			if (decl instanceof Node node) {
				map.put(node.accessString(), new Rect(a.getX(), a.getY(), a.getWidth(), a.getHeight()));
			}
		}
		return map;
	}

	/**
	 * Outside Eclipse, ELK's metadata (option defaults, cloning of option values)
	 * must be registered once before layout.
	 */
	private static final class ElkSetup {
		static {
			LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(new LayeredMetaDataProvider());
		}

		static void ensure() {
			// Triggers the static initializer.
		}
	}

	private Map<String, Rect> elkLayout(List<? extends Node> nodes, Map<String, String> labels, List<Link> links) {
		ElkSetup.ensure();
		ElkNode graph = ElkGraphUtil.createGraph();
		graph.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
		graph.setProperty(CoreOptions.PADDING, new ElkPadding(20));
		graph.setProperty(CoreOptions.SPACING_NODE_NODE, 30.0);
		// Room for literals, which are drawn left of their target.
		graph.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 60.0);

		Map<String, ElkNode> elkNodes = new LinkedHashMap<>();
		Map<DiagramBuilder.Endpoint, ElkPort> ports = new HashMap<>();
		for (Node n : nodes) {
			String id = n.accessString();
			ElkNode en = ElkGraphUtil.createNode(graph);
			en.setIdentifier(id);
			int width = n instanceof Block ? DEFAULT_WIDTH : defaultWidth(labels.get(id));
			int height = minHeight(n);
			en.setDimensions(width, height);
			en.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_POS);
			if (n instanceof Block b) {
				for (Port p : b.getInPorts()) {
					ports.put(new DiagramBuilder.Endpoint(id, Diagram.inPortHandle(p.name())),
							port(en, PortSide.WEST, -PORT_SIZE, portY(p.index()), PORT_SIZE));
				}
				for (Port p : b.getOutPorts()) {
					ports.put(new DiagramBuilder.Endpoint(id, Diagram.outPortHandle(p.name())),
							port(en, PortSide.EAST, width, portY(p.index()), PORT_SIZE));
				}
			} else {
				ports.put(new DiagramBuilder.Endpoint(id, Diagram.IN),
						port(en, PortSide.WEST, 0, height / 2, 0));
				ports.put(new DiagramBuilder.Endpoint(id, Diagram.OUT),
						port(en, PortSide.EAST, width, height / 2, 0));
				if (n instanceof InParameter) {
					en.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, LayerConstraint.FIRST);
				} else if (n instanceof OutParameter) {
					en.setProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT, LayerConstraint.LAST);
				}
			}
			elkNodes.put(id, en);
		}
		for (Link link : links) {
			ElkPort s = ports.get(link.source());
			ElkPort t = ports.get(link.target());
			if (s != null && t != null && s.getParent() != t.getParent()) {
				ElkGraphUtil.createSimpleEdge(s, t);
			}
		}

		new LayeredLayoutProvider().layout(graph, new BasicProgressMonitor());

		Map<String, Rect> result = new LinkedHashMap<>();
		for (Map.Entry<String, ElkNode> e : elkNodes.entrySet()) {
			ElkNode n = e.getValue();
			result.put(e.getKey(), new Rect(
					(int) Math.round(n.getX()), (int) Math.round(n.getY()),
					(int) Math.round(n.getWidth()), (int) Math.round(n.getHeight())));
		}
		return result;
	}

	private static ElkPort port(ElkNode node, PortSide side, int x, int y, int size) {
		ElkPort p = ElkGraphUtil.createPort(node);
		p.setProperty(CoreOptions.PORT_SIDE, side);
		p.setLocation(x, y);
		p.setDimensions(size, size);
		return p;
	}

	private static void placeBelow(Map<String, Rect> placed, List<Node> missing, Map<String, String> labels) {
		int minX = Integer.MAX_VALUE;
		int maxY = 0;
		for (Rect r : placed.values()) {
			minX = Math.min(minX, r.x());
			maxY = Math.max(maxY, r.y() + r.height());
		}
		int x = minX;
		int y = maxY + GAP;
		for (Node n : missing) {
			int width = n instanceof Block ? DEFAULT_WIDTH : defaultWidth(labels.get(n.accessString()));
			placed.put(n.accessString(), new Rect(x, y, width, minHeight(n)));
			x += width + GAP;
		}
	}
}
