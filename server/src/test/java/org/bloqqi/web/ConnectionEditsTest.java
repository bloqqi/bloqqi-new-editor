package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bloqqi.web.Diagram.DiagramEdge;
import org.bloqqi.web.Diagram.DiagramNode;
import org.bloqqi.web.Diagram.DiagramPort;
import org.bloqqi.web.DiagramBuilder.Endpoint;
import org.junit.Test;

/**
 * Creating and deleting connections: the new text is as expected, reparses
 * without errors, and has (or no longer has) the edge.
 */
public class ConnectionEditsTest {

	private static String source(String fileName) throws Exception {
		return Files.readString(Examples.dir().resolve(fileName), StandardCharsets.UTF_8);
	}

	private static LoadedFile load(String fileName, String text) {
		return BloqqiLoader.load(fileName, text);
	}

	private static Set<String> edgeIds(LoadedFile file, String type) {
		return DiagramBuilder.build(file, type).edges().stream().map(DiagramEdge::id).collect(Collectors.toSet());
	}

	private static void assertCleanParse(LoadedFile file) {
		assertTrue("parsed", file.isParsed());
		assertEquals("syntax errors", List.of(), file.syntaxErrors());
		assertEquals("semantic errors", List.of(), file.semanticErrors());
	}

	private static Endpoint at(String node, String handle) {
		return new Endpoint(node, handle);
	}

	private static void assertRefused(String expectedMessage, Runnable edit) {
		try {
			edit.run();
			fail("expected the edit to be refused: " + expectedMessage);
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
		}
	}

	@Test
	public void deletingAConnectionRemovesItsLine() throws Exception {
		String before = source("TwoCounters.dia");
		String after = DeleteEdits.deleteConnections(load("TwoCounters.dia", before), "Acc",
				List.of("in.out->add.in:in1"));

		assertEquals(before.replace("  connect(in, add.in1);\n", ""), after);
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		assertFalse(edgeIds(reparsed, "Acc").contains("in.out->add.in:in1"));
	}

	@Test
	public void connectingAppendsAStatementToTheBody() throws Exception {
		String original = source("TwoCounters.dia");
		LoadedFile unconnected = load("TwoCounters.dia", DeleteEdits.deleteConnections(
				load("TwoCounters.dia", original), "Acc", List.of("in.out->add.in:in1")));

		String after = ConnectionEdits.connect(unconnected, "Acc", at("in", "out"), at("add", "in:in1"));

		assertEquals(unconnected.source().replace("  connect(add.out, state);\n}",
				"  connect(add.out, state);\n  connect(in, add.in1);\n}"), after);
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		assertTrue(edgeIds(reparsed, "Acc").contains("in.out->add.in:in1"));
	}

	@Test
	public void freeHandlesAreConnectable() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		DiagramNode add = DiagramBuilder.build(file, "Acc").nodes().stream()
				.filter(n -> n.id().equals("add")).findFirst().orElseThrow();
		assertFalse("in1 is already connected", add.inPorts().get(0).connectable());
		assertTrue("an out-port can always start another connection", add.outPorts().get(0).connectable());

		LoadedFile unconnected = load("TwoCounters.dia", DeleteEdits.deleteConnections(file, "Acc",
				List.of("in.out->add.in:in1")));
		List<DiagramNode> nodes = DiagramBuilder.build(unconnected, "Acc").nodes();
		DiagramPort in1 = nodes.stream().filter(n -> n.id().equals("add")).findFirst().orElseThrow().inPorts().get(0);
		assertTrue(in1.connectable());
		DiagramNode in = nodes.stream().filter(n -> n.id().equals("in")).findFirst().orElseThrow();
		assertTrue(in.outConnectable());
		assertFalse("in-parameters are never targets", in.inConnectable());
	}

	@Test
	public void portsOfInheritedBlocksAreWrittenWithoutQualification() throws Exception {
		// In PTrack (extends P), free SelReal_1.sel and drive it from the inherited block e.
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		LoadedFile freed = load("PLib.dia", DeleteEdits.deleteConnections(file, "PTrack",
				List.of("Track.out->SelReal_1.in:sel")));

		// e.out is a Real and sel a Bool.
		assertRefused("incompatible types",
				() -> ConnectionEdits.connect(freed, "PTrack", at("P::e", "out:out"), at("SelReal_1", "in:sel")));

		String after = ConnectionEdits.connect(freed, "PTrack", at("Track", "out"), at("SelReal_1", "in:sel"));
		assertTrue(after.contains("  connect(TrackValue, SelReal_1.in1);\n  connect(Track, SelReal_1.sel);\n}"));

		// An inherited block's port: "e.out", not "P::e.out".
		LoadedFile again = load("PLib.dia", DeleteEdits.deleteConnections(load("PLib.dia", after), "PTrack",
				List.of("TrackValue.out->SelReal_1.in:in1")));
		String withE = ConnectionEdits.connect(again, "PTrack", at("P::e", "out:out"), at("SelReal_1", "in:in1"));
		assertTrue(withE.contains("  connect(e.out, SelReal_1.in1);\n}"));
		LoadedFile reparsed = load("PLib.dia", withE);
		assertCleanParse(reparsed);
		assertTrue(edgeIds(reparsed, "PTrack").contains("P::e.out:out->SelReal_1.in:in1"));
	}

	@Test
	public void aTargetCanOnlyHaveOneIncomingConnection() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("already has an incoming connection",
				() -> ConnectionEdits.connect(file, "Acc", at("in", "out"), at("add", "in:in2")));
	}

	@Test
	public void connectionsMustRunFromASourceToATarget() throws Exception {
		LoadedFile file = load("TwoCounters.dia", DeleteEdits.deleteConnections(
				load("TwoCounters.dia", source("TwoCounters.dia")), "Acc", List.of("in.out->add.in:in1")));
		assertRefused("cannot start at", () -> ConnectionEdits.connect(file, "Acc", at("add", "in:in1"), at("out", "in")));
		assertRefused("cannot end at", () -> ConnectionEdits.connect(file, "Acc", at("add", "out:out"), at("in", "in")));
		assertRefused("No such handle", () -> ConnectionEdits.connect(file, "Acc", at("add", "out:nosuch"), at("add", "in:in1")));
	}

	@Test
	public void dataflowCyclesAreRefused() throws Exception {
		// P: e -> PPart; feeding PPart's output back into e makes a cycle without a variable.
		String before = source("PLib.dia");
		LoadedFile freed = load("PLib.dia", DeleteEdits.deleteConnections(load("PLib.dia", before), "P",
				List.of("SP.out->e.in:in1")));
		assertRefused("cycle", () -> ConnectionEdits.connect(freed, "P", at("PPart", "out:out"), at("e", "in:in1")));
	}

	@Test
	public void inheritedConnectionsCannotBeDeleted() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		assertRefused("not declared in PTrack",
				() -> DeleteEdits.deleteConnections(file, "PTrack", List.of("P::e.out:out->P::PPart.in:in1")));
		assertRefused("No connection",
				() -> DeleteEdits.deleteConnections(file, "PTrack", List.of("nosuch")));
	}

	@Test
	public void connectionsFromAWiringCannotBeDeleted() throws Exception {
		// MyLoop's "override" is a wired block: the interception comes from
		// "wiring Override[...] { intercept in with Override, Override; }".
		LoadedFile file = load("vlhcc-large.dia", source("vlhcc-large.dia"));
		DiagramEdge e = DiagramBuilder.build(file, "MyLoop").edges().stream()
				.filter(x -> x.id().equals("slave.out:out->override.in:in")).findFirst().orElseThrow();
		assertFalse("drawn like a local connection", e.inherited());
		assertFalse(e.deletable());
		assertRefused("comes from a wiring",
				() -> DeleteEdits.deleteConnections(file, "MyLoop", List.of(e.id())));
	}

	@Test
	public void deletingEitherHalfOfAnInterceptionRemovesTheInterception() throws Exception {
		String before = source("PLib.dia");
		String after = DeleteEdits.deleteConnections(load("PLib.dia", before), "PTrack",
				List.of("P::PPart.out:out->SelReal_1.in:in2"));

		assertEquals(before.replace("  intercept Out with SelReal_1.in2, SelReal_1.out;\n", ""), after);
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		Set<String> edges = edgeIds(reparsed, "PTrack");
		assertTrue("the inherited connection is back", edges.contains("P::PPart.out:out->P::Out.in"));
		assertFalse(edges.contains("SelReal_1.out:out->P::Out.in"));
	}

	@Test
	public void tabsAndCommentsAreKept() throws Exception {
		// Tank.dia indents with tabs and has comments between the connections.
		String before = source("Tank.dia");
		LoadedFile file = load("Tank.dia", before);
		String type = DiagramBuilder.diagramTypeNames(file.compilationUnit()).get(0);
		DiagramEdge edge = DiagramBuilder.build(file, type).edges().stream()
				.filter(DiagramEdge::deletable).reduce((a, b) -> b).orElseThrow();

		String deleted = DeleteEdits.deleteConnections(file, type, List.of(edge.id()));
		for (String line : before.split("\n")) {
			if (line.contains("//")) {
				assertTrue("kept: " + line, deleted.contains(line));
			}
		}
		assertEquals("one line less", before.split("\n").length - 1, deleted.split("\n").length);

		LoadedFile freed = load("Tank.dia", deleted);
		String reconnected = ConnectionEdits.connect(freed, type,
				new Endpoint(edge.source(), edge.sourceHandle()), new Endpoint(edge.target(), edge.targetHandle()));
		assertTrue("tab-indented: " + reconnected, reconnected.contains(";\n\tconnect("));
		assertCleanParse(load("Tank.dia", reconnected));
		assertTrue(edgeIds(load("Tank.dia", reconnected), type).contains(edge.id()));
	}

	@Test
	public void connectingInAnEmptyBody() {
		String twoLines = "diagramtype A(a: Int => b: Int) {\n}\n";
		assertEquals("diagramtype A(a: Int => b: Int) {\n  connect(a, b);\n}\n",
				ConnectionEdits.connect(load("x.dia", twoLines), "A", at("a", "out"), at("b", "in")));

		String oneLine = "diagramtype A(a: Int => b: Int) {}\n";
		assertEquals("diagramtype A(a: Int => b: Int) {\n  connect(a, b);\n}\n",
				ConnectionEdits.connect(load("x.dia", oneLine), "A", at("a", "out"), at("b", "in")));

		String oneLineBody = "diagramtype A(a: Int => b: Int) { var v: Int; }\n";
		assertEquals("diagramtype A(a: Int => b: Int) { var v: Int; connect(a, b); }\n",
				ConnectionEdits.connect(load("x.dia", oneLineBody), "A", at("a", "out"), at("b", "in")));
	}

	@Test
	public void bracesInCommentsDoNotConfuseTheInsertion() {
		String text = "diagramtype A(a: Int => b: Int) {\n  // a } in a comment\n  var v: Int;\n}\n";
		String after = ConnectionEdits.connect(load("x.dia", text), "A", at("a", "out"), at("b", "in"));
		assertEquals("diagramtype A(a: Int => b: Int) {\n  // a } in a comment\n  var v: Int;\n  connect(a, b);\n}\n",
				after);
	}

	private static String handleType(Diagram d, String node, String handle) {
		DiagramNode n = d.nodes().stream().filter(x -> x.id().equals(node)).findFirst().orElseThrow();
		for (DiagramPort p : n.inPorts()) {
			if (Diagram.inPortHandle(p.name()).equals(handle)) {
				return p.typeName();
			}
		}
		for (DiagramPort p : n.outPorts()) {
			if (Diagram.outPortHandle(p.name()).equals(handle)) {
				return p.typeName();
			}
		}
		return n.typeName();
	}

	/**
	 * In every example: every deletable connection can be deleted, and then
	 * connected again (unless it comes from a literal), giving back the edge;
	 * connecting other free handles works or is refused with a reason, never by
	 * a broken file. Connections that cannot be deleted are refused.
	 */
	@Test
	public void editsInEveryExample() throws Exception {
		int roundTrips = 0;
		for (String f : Examples.workspace().files()) {
			if (Examples.EXCLUDED.contains(f)) {
				continue;
			}
			LoadedFile file = load(f, source(f));
			for (String type : DiagramBuilder.diagramTypeNames(file.compilationUnit())) {
				Diagram d = DiagramBuilder.build(file, type);
				for (DiagramEdge e : d.edges()) {
					String where = f + " " + type + " " + e.id();
					if (!e.deletable()) {
						assertRefused("not declared in", () -> DeleteEdits.deleteConnections(file, type, List.of(e.id())));
						continue;
					}
					LoadedFile after = load(f, DeleteEdits.deleteConnections(file, type, List.of(e.id())));
					assertCleanParse(after);
					assertFalse(where, edgeIds(after, type).contains(e.id()));
					if (e.fromLiteral() || e.intercept() != null
							|| !handleType(d, e.source(), e.sourceHandle()).equals(handleType(d, e.target(), e.targetHandle()))) {
						// Literals and interceptions are not drawn by dragging; nor are
						// connections through struct fields ("status.level"), whose ports
						// have different types.
						continue;
					}
					LoadedFile again = load(f, ConnectionEdits.connect(after, type,
							at(e.source(), e.sourceHandle()), at(e.target(), e.targetHandle())));
					assertCleanParse(again);
					assertTrue(where, edgeIds(again, type).contains(e.id()));
					roundTrips++;
				}

				List<Endpoint> sources = new java.util.ArrayList<>();
				List<Endpoint> targets = new java.util.ArrayList<>();
				for (DiagramNode n : d.nodes()) {
					n.outPorts().stream().filter(DiagramPort::connectable)
							.forEach(p -> sources.add(at(n.id(), Diagram.outPortHandle(p.name()))));
					n.inPorts().stream().filter(DiagramPort::connectable)
							.forEach(p -> targets.add(at(n.id(), Diagram.inPortHandle(p.name()))));
					if (n.outConnectable()) {
						sources.add(at(n.id(), Diagram.OUT));
					}
					if (n.inConnectable()) {
						targets.add(at(n.id(), Diagram.IN));
					}
				}
				for (Endpoint s : sources) {
					for (Endpoint t : targets) {
						try {
							assertCleanParse(load(f, ConnectionEdits.connect(file, type, s, t)));
						} catch (IllegalArgumentException refused) {
							// e.g. incompatible types or a cycle
						}
					}
				}
			}
		}
		assertTrue("round trips: " + roundTrips, roundTrips > 50);
	}
}
