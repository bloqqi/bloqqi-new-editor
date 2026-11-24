package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bloqqi.web.Diagram.DiagramEdge;
import org.bloqqi.web.Diagram.DiagramNode;
import org.bloqqi.web.Diagram.DiagramPort;
import org.junit.Test;

public class DiagramBuilderTest {

	private static Map<String, DiagramNode> byId(Diagram d) {
		return d.nodes().stream().collect(Collectors.toMap(DiagramNode::id, Function.identity()));
	}

	private static List<String> ids(Diagram d, String kind) {
		return d.nodes().stream().filter(n -> n.kind().equals(kind)).map(DiagramNode::id).toList();
	}

	private static List<String> blockIds(Diagram d) {
		return ids(d, Diagram.BLOCK);
	}

	private static Map<String, DiagramEdge> edgesById(Diagram d) {
		return d.edges().stream().collect(Collectors.toMap(DiagramEdge::id, Function.identity()));
	}

	private static void assertRect(DiagramNode n, int x, int y, int w, int h) {
		assertEquals(n.id() + " x", x, n.x());
		assertEquals(n.id() + " y", y, n.y());
		assertEquals(n.id() + " width", w, n.width());
		assertEquals(n.id() + " height", h, n.height());
	}

	@Test
	public void firstDiagramTypeIsShownByDefault() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("PLib.dia"), null);
		assertEquals("P", d.diagramType());
		assertEquals(List.of("P", "IPart", "DPart", "Rid", "PI", "PD", "PTrack", "Main"), d.diagramTypes());
		assertEquals(List.of("e", "PPart"), blockIds(d));
		assertRect(byId(d).get("e"), 76, 7, 24, 30);
	}

	@Test
	public void inheritedBlocksUseSubtypeAnnotations() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("PLib.dia"), "PTrack");
		assertEquals(List.of("P::e", "P::PPart", "SelReal_1"), blockIds(d));
		Map<String, DiagramNode> nodes = byId(d);

		DiagramNode e = nodes.get("P::e");
		assertEquals("e", e.label());
		assertEquals("RSub", e.typeName());
		assertTrue(e.inherited());
		assertRect(e, 100, 38, 34, 30);

		DiagramNode sel = nodes.get("SelReal_1");
		assertEquals("simple-named block shows its type", "SelReal", sel.label());
		assertFalse(sel.inherited());
		assertRect(sel, 298, 10, 44, 46);
	}

	@Test
	public void inlinedBlocksAreShownWithInlinedNames() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("InlineExample.dia"), "Tank");
		Map<String, DiagramNode> nodes = byId(d);
		assertEquals(List.of(
				"temperature", "valveOpening", "operatorSP", "pressure", "operatorGF",
				"loop$slave$controller", "loop$slave$filter", "loop$override",
				"loop$master$filter", "loop$master$controller"), blockIds(d));

		DiagramNode controller = nodes.get("loop$master$controller");
		assertTrue(controller.inlined());
		assertEquals("controller", controller.label());
		assertRect(controller, 152, 23, 54, 44);
		assertFalse(nodes.get("temperature").inlined());
	}

	@Test
	public void autoLayoutWhenFileHasNoAnnotations() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("TwoCounters.dia"), "Main");
		Map<String, DiagramNode> nodes = byId(d);
		assertEquals(List.of("acc1", "acc2", "print1", "print2"), blockIds(d));

		// Left to right along the connections acc -> print.
		assertTrue(nodes.get("acc1").x() < nodes.get("print1").x());
		assertTrue(nodes.get("acc2").x() < nodes.get("print2").x());

		// No overlaps.
		for (DiagramNode a : d.nodes()) {
			for (DiagramNode b : d.nodes()) {
				if (a != b) {
					boolean overlap = a.x() < b.x() + b.width() && b.x() < a.x() + a.width()
							&& a.y() < b.y() + b.height() && b.y() < a.y() + a.height();
					assertFalse(a.id() + " overlaps " + b.id(), overlap);
				}
			}
		}
	}

	@Test
	public void parametersVariablesAndPortsAreNodes() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("TwoCounters.dia"), "Acc");
		Map<String, DiagramNode> nodes = byId(d);
		assertEquals(List.of("in", "out", "add", "state"), d.nodes().stream().map(DiagramNode::id).toList());
		assertEquals(Diagram.IN_PARAMETER, nodes.get("in").kind());
		assertEquals("Int", nodes.get("in").typeName());
		assertEquals(Diagram.OUT_PARAMETER, nodes.get("out").kind());
		assertEquals(Diagram.VARIABLE, nodes.get("state").kind());
		assertEquals("var state", nodes.get("state").label());

		DiagramNode add = nodes.get("add");
		assertEquals(Diagram.BLOCK, add.kind());
		// Ports at y = 5 + 14 * index, as in the old editor.
		assertEquals(List.of(new DiagramPort("in1", "Int", 5, false), new DiagramPort("in2", "Int", 19, false)), add.inPorts());
		assertEquals(List.of(new DiagramPort("out", "Int", 5, true)), add.outPorts());
		assertTrue(nodes.get("state").inPorts().isEmpty());
	}

	@Test
	public void connectionsAreEdgesBetweenHandles() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("TwoCounters.dia"), "Acc");
		assertEquals(List.of(
				"in.out->add.in:in1",
				"state.out->add.in:in2",
				"add.out:out->out.in",
				"add.out:out->state.in"),
				d.edges().stream().map(DiagramEdge::id).toList());
		DiagramEdge e = d.edges().get(0);
		assertEquals("in", e.source());
		assertEquals(Diagram.OUT, e.sourceHandle());
		assertEquals("add", e.target());
		assertEquals("in:in1", e.targetHandle());
		assertFalse(e.inherited());
		assertFalse(e.error());
		assertFalse(e.fromLiteral());
		assertNull(e.intercept());
	}

	@Test
	public void autoLayoutPutsParametersAtTheEdges() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("TwoCounters.dia"), "Acc");
		Map<String, DiagramNode> nodes = byId(d);
		assertTrue(nodes.get("in").x() < nodes.get("add").x());
		assertTrue(nodes.get("add").x() < nodes.get("out").x());
	}

	@Test
	public void literalsAreDrawnLeftOfTheirTargetPort() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("TwoCounters.dia"), "Main");
		Map<String, DiagramNode> nodes = byId(d);
		DiagramNode lit = nodes.get("#literal:acc1.in:in");
		assertEquals(Diagram.LITERAL, lit.kind());
		assertEquals("1", lit.label());
		DiagramNode acc1 = nodes.get("acc1");
		assertEquals(acc1.x() - 12, lit.x() + lit.width());
		// Centred on the port: port mid = 5 + 3.
		assertEquals(acc1.y() + 8, lit.y() + lit.height() / 2);

		DiagramEdge e = edgesById(d).get("#literal:acc1.in:in.out->acc1.in:in");
		assertTrue(e.fromLiteral());
	}

	@Test
	public void inheritedAndInterceptedConnections() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("PLib.dia"), "PTrack");
		assertEquals(List.of("P::SP", "P::PV", "P::K", "Track", "TrackValue"), ids(d, Diagram.IN_PARAMETER));
		assertEquals(List.of("P::Out"), ids(d, Diagram.OUT_PARAMETER));
		assertTrue(byId(d).get("P::SP").inherited());
		assertFalse(byId(d).get("Track").inherited());

		Map<String, DiagramEdge> edges = edgesById(d);
		assertTrue(edges.get("P::e.out:out->P::PPart.in:in1").inherited());
		assertFalse(edges.get("Track.out->SelReal_1.in:sel").inherited());

		// intercept Out with SelReal_1.in2, SelReal_1.out;
		DiagramEdge first = edges.get("P::PPart.out:out->SelReal_1.in:in2");
		assertEquals("first", first.intercept());
		assertFalse(first.inherited());
		assertFalse("connection interception: small marker on the first half", first.largeMarker());
		DiagramEdge second = edges.get("SelReal_1.out:out->P::Out.in");
		assertEquals("second", second.intercept());
		assertTrue(second.largeMarker());
		assertFalse("the original P::PPart.out -> Out is replaced",
				edges.containsKey("P::PPart.out:out->P::Out.in"));
	}

	@Test
	public void inlinedConnectionsInsideTheInlinedBlockAreNotDeletable() throws Exception {
		Diagram d = DiagramBuilder.build(Examples.load("InlineExample.dia"), "Tank");
		Map<String, DiagramEdge> edges = edgesById(d);
		assertTrue(edges.get("loop$slave$filter.out:out->loop$slave$controller.in:PV").inherited());
		assertFalse(edges.get("temperature.out:value->loop$master$filter.in:in").inherited());
	}

	@Test
	public void unknownDiagramTypeIsRejected() throws Exception {
		try {
			DiagramBuilder.build(Examples.load("PLib.dia"), "NoSuchType");
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("NoSuchType"));
		}
	}

	@Test
	public void unparsableFileGivesProblemsAndNoNodes() throws Exception {
		LoadedFile file = Examples.load("Example-Pregulator.dia");
		assertFalse(file.isParsed());
		Diagram d = DiagramBuilder.build(file, null);
		assertNull(d.diagramType());
		assertTrue(d.nodes().isEmpty());
		assertFalse(d.problems().isEmpty());
	}

	@Test
	public void parserCrashDuringRecoveryIsASyntaxError() {
		// Beaver's recovery throws ArrayIndexOutOfBoundsException on this input.
		LoadedFile file = BloqqiLoader.load("x.dia",
				"diagramtype A(in: Int) {\n}\nannotation(\n  in = (-30, -10, 60, 20)\n)\n");
		assertFalse(file.isParsed());
		assertTrue(file.syntaxErrors().toString(),
				file.syntaxErrors().stream().anyMatch(p -> p.message().contains("failed while recovering")));
		assertNull(DiagramBuilder.build(file, null).diagramType());
	}

	@Test
	public void recoveredSyntaxErrorsAreReported() {
		LoadedFile file = BloqqiLoader.load("x.dia", "diagramtype A { connect(a, b) }");
		assertFalse("recovery must be reported", file.syntaxErrors().isEmpty());
	}

	/** A block's anonymous type ({@code pid: P { feature ...; }}) can be shown, read-only. */
	@Test
	public void anonymousTypesCanBeShownReadOnly() throws Exception {
		LoadedFile plib = Examples.load("PLib.dia");
		assertEquals("Main$pid", byId(DiagramBuilder.build(plib, "Main")).get("pid").openType());
		Map<String, DiagramNode> twoCounters = byId(DiagramBuilder.build(Examples.load("TwoCounters.dia"), "Main"));
		assertEquals("a diagram type in the file", "Acc", twoCounters.get("acc1").openType());
		assertNull("an external function", twoCounters.get("print1").openType());

		Diagram d = DiagramBuilder.build(plib, "Main$pid");
		assertEquals("Main$pid", d.diagramType());
		assertEquals("This is the type of block pid in Main, which is an anonymous type. It is shown read-only "
				+ "(change the block's features with \"Features…\" in Main, or edit the text)", d.readOnly());
		assertEquals(List.of("P"), d.superTypes());
		// P's elements and those of its features PD and PI.
		Map<String, DiagramNode> nodes = byId(d);
		for (String id : List.of("P::SP", "P::PPart", "PD::Td", "PD::DPart", "PI::Ti", "PI::IPart", "P::Out")) {
			assertTrue(id + " in " + nodes.keySet(), nodes.containsKey(id));
		}
		assertTrue(d.edges().size() > 10);

		// Edits do not reach it.
		try {
			LayoutEdits.autoLayout(plib, "Main$pid");
			fail("an edit of an anonymous type");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("No diagram type Main$pid"));
		}
		// A nested one (InlineExample: a block inside inlined blocks), and an unknown name.
		assertEquals("Tank$loop$master$controller",
				DiagramBuilder.build(Examples.load("InlineExample.dia"), "Tank$loop$master$controller").diagramType());
		try {
			DiagramBuilder.build(plib, "Main$nosuch");
			fail("unknown anonymous type");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("No diagram type Main$nosuch"));
		}
	}

	/** Every anonymous type a block in an example has can be shown. */
	@Test
	public void everyAnonymousTypeInTheExamplesCanBeShown() throws Exception {
		int shown = 0;
		List<String> files;
		try (var paths = java.nio.file.Files.list(Examples.dir())) {
			files = paths.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".dia"))
					.filter(n -> !Examples.EXCLUDED.contains(n)).sorted().toList();
		}
		for (String file : files) {
			LoadedFile f = Examples.load(file);
			for (String type : DiagramBuilder.diagramTypeNames(f.compilationUnit())) {
				for (DiagramNode n : DiagramBuilder.build(f, type).nodes()) {
					if (n.openType() != null && n.openType().contains("$")) {
						Diagram d = DiagramBuilder.build(f, n.openType());
						assertEquals(n.openType(), d.diagramType());
						assertTrue(n.openType(), d.readOnly() != null);
						shown++;
					}
				}
			}
		}
		assertTrue("some were shown: " + shown, shown >= 2);
	}
}
