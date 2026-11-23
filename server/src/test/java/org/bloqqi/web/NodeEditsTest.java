package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bloqqi.web.Diagram.DiagramEdge;
import org.bloqqi.web.Diagram.DiagramNode;
import org.bloqqi.web.DiagramBuilder.Endpoint;
import org.junit.Test;

/** Literals, variables and parameters: adding and deleting them. */
public class NodeEditsTest {

	private static String source(String fileName) throws Exception {
		return Files.readString(Examples.dir().resolve(fileName), StandardCharsets.UTF_8);
	}

	private static LoadedFile load(String fileName, String text) {
		return BloqqiLoader.load(fileName, text);
	}

	private static Map<String, DiagramNode> nodes(LoadedFile file, String type) {
		return DiagramBuilder.build(file, type).nodes().stream()
				.collect(Collectors.toMap(DiagramNode::id, Function.identity()));
	}

	private static void assertCleanParse(LoadedFile file) {
		assertTrue("parsed", file.isParsed());
		assertEquals("syntax errors", List.of(), file.syntaxErrors());
		assertEquals("semantic errors", List.of(), file.semanticErrors());
	}

	private static void assertRefused(String expectedMessage, Runnable edit) {
		try {
			edit.run();
			fail("expected the edit to be refused: " + expectedMessage);
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
		}
	}

	private static void assertUnmoved(Map<String, DiagramNode> before, Map<String, DiagramNode> after) {
		for (DiagramNode n : before.values()) {
			if (!n.kind().equals(Diagram.LITERAL)) {
				DiagramNode m = after.get(n.id());
				assertEquals(n.id(), List.of(n.x(), n.y()), List.of(m.x(), m.y()));
			}
		}
	}

	private static Endpoint at(String node, String handle) {
		return new Endpoint(node, handle);
	}

	// ----- Literals -----

	@Test
	public void addingALiteralConnectsIt() throws Exception {
		// TwoCounters' Main: connect(1, acc1.in); free acc1.in first.
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		LoadedFile freed = load("TwoCounters.dia",
				DiagramEdits.deleteConnections(file, "Main", List.of("#literal:acc1.in:in.out->acc1.in:in")));

		String after = DiagramEdits.addLiteral(freed, "Main", at("acc1", "in:in"), " 42 ");

		assertTrue(after.contains("  connect(acc2.out, print2.in);\n  connect(42, acc1.in);\n}"));
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		DiagramEdge e = DiagramBuilder.build(reparsed, "Main").edges().stream()
				.filter(x -> x.target().equals("acc1")).findFirst().orElseThrow();
		assertTrue(e.fromLiteral());
		assertEquals("42", nodes(reparsed, "Main").get(e.source()).label());
	}

	@Test
	public void literalsMustBeOneTokenOfTheRightType() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		LoadedFile freed = load("PLib.dia", DiagramEdits.deleteConnections(file, "PTrack",
				List.of("Track.out->SelReal_1.in:sel")));
		for (String bad : List.of("abc", "1 2", "-3", "")) {
			assertRefused("Not a literal",
					() -> DiagramEdits.addLiteral(freed, "PTrack", at("SelReal_1", "in:sel"), bad));
		}
		// sel is a Bool.
		assertRefused("Not allowed", () -> DiagramEdits.addLiteral(freed, "PTrack", at("SelReal_1", "in:sel"), "3"));
		String ok = DiagramEdits.addLiteral(freed, "PTrack", at("SelReal_1", "in:sel"), "true");
		assertTrue(ok.contains("connect(true, SelReal_1.sel);"));
		assertCleanParse(load("PLib.dia", ok));
		// Taken targets are refused.
		assertRefused("already has an incoming connection",
				() -> DiagramEdits.addLiteral(file, "PTrack", at("SelReal_1", "in:sel"), "true"));
	}

	// ----- Variables -----

	@Test
	public void addingAVariableInsertsItAfterTheLastVariable() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		Map<String, DiagramNode> before = nodes(file, "Acc");
		String after = DiagramEdits.addVariable(file, "Acc", "var", "count", "Int", 200, 160);

		assertTrue(after.contains("  var state: Int;\n  var count: Int;\n  add: Add;"));
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		Map<String, DiagramNode> now = nodes(reparsed, "Acc");
		assertEquals(Diagram.VARIABLE, now.get("count").kind());
		assertEquals("var count", now.get("count").label());
		assertEquals(List.of(200, 160), List.of(now.get("count").x(), now.get("count").y()));
		assertUnmoved(before, now);
	}

	@Test
	public void variablesComeFirstInTheBody() throws Exception {
		// P has no variables: the new one goes before the blocks.
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		String input = DiagramEdits.addVariable(file, "P", "input", "h", "Real", 0, 100);
		assertTrue(input.startsWith("diagramtype P(SP: Real, PV: Real, K: Real => Out: Real) {\n"
				+ "  input h: Real;\n  e: RSub;\n"));
		assertCleanParse(load("PLib.dia", input));
		String output = DiagramEdits.addVariable(file, "P", "output", "o", "Bool", 0, 100);
		assertTrue(output.contains("  output o: Bool;\n  e: RSub;\n"));
		assertCleanParse(load("PLib.dia", output));
	}

	@Test
	public void namesAndTypesAreChecked() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("Not a valid name", () -> DiagramEdits.addVariable(file, "Acc", "var", "1x", "Int", 0, 0));
		assertRefused("Not a valid name", () -> DiagramEdits.addVariable(file, "Acc", "var", "a b", "Int", 0, 0));
		assertRefused("already used", () -> DiagramEdits.addVariable(file, "Acc", "var", "state", "Int", 0, 0));
		assertRefused("already used", () -> DiagramEdits.addVariable(file, "Acc", "var", "add", "Int", 0, 0));
		assertRefused("already used", () -> DiagramEdits.addParameter(file, "Acc", true, "in", "Int", 0, 0));
		assertRefused("The type must be one of", () -> DiagramEdits.addVariable(file, "Acc", "var", "s", "String", 0, 0));
		assertRefused("Unknown kind", () -> DiagramEdits.addVariable(file, "Acc", "state", "s", "Int", 0, 0));
	}

	@Test
	public void deletingAVariableRemovesItsConnections() throws Exception {
		String before = source("TwoCounters.dia");
		String after = DiagramEdits.delete(load("TwoCounters.dia", before), "Acc", List.of(), List.of("state"));
		assertEquals(before.replace("  var state: Int;\n", "")
				.replace("  connect(state, add.in2);\n", "")
				.replace("  connect(add.out, state);\n", ""), after);
		assertCleanParse(load("TwoCounters.dia", after));
	}

	// ----- Parameters -----

	@Test
	public void parametersGoIntoTheHeader() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		String in = DiagramEdits.addParameter(file, "Acc", true, "reset", "Bool", 0, 100);
		assertTrue(in.contains("diagramtype Acc(in: Int, reset: Bool => out: Int) {"));
		assertCleanParse(load("TwoCounters.dia", in));
		assertEquals(Diagram.IN_PARAMETER, nodes(load("TwoCounters.dia", in), "Acc").get("reset").kind());

		String out = DiagramEdits.addParameter(file, "Acc", false, "carry", "Int", 300, 100);
		assertTrue(out.contains("diagramtype Acc(in: Int => out: Int, carry: Int) {"));
		assertCleanParse(load("TwoCounters.dia", out));

		// Main() has empty parentheses.
		assertTrue(DiagramEdits.addParameter(file, "Main", true, "x", "Int", 0, 0)
				.contains("diagramtype Main(x: Int) {"));
		assertTrue(DiagramEdits.addParameter(file, "Main", false, "y", "Int", 0, 0)
				.contains("diagramtype Main(=> y: Int) {"));
	}

	@Test
	public void headersWithoutParenthesesOrWithOnlyOneSide() {
		String none = "diagramtype A {\n}\n";
		assertEquals("diagramtype A(a: Int) {\n}\n", stripAnnotations(
				DiagramEdits.addParameter(load("x.dia", none), "A", true, "a", "Int", 0, 0)));
		assertEquals("diagramtype A(=> b: Int) {\n}\n", stripAnnotations(
				DiagramEdits.addParameter(load("x.dia", none), "A", false, "b", "Int", 0, 0)));

		String onlyIn = "diagramtype A(a: Int) {\n}\n";
		assertEquals("diagramtype A(a: Int => b: Int) {\n}\n", stripAnnotations(
				DiagramEdits.addParameter(load("x.dia", onlyIn), "A", false, "b", "Int", 0, 0)));

		String onlyOut = "diagramtype A(=> b: Int) {\n}\n";
		assertEquals("diagramtype A(a: Int => b: Int) {\n}\n", stripAnnotations(
				DiagramEdits.addParameter(load("x.dia", onlyOut), "A", true, "a", "Int", 0, 0)));
	}

	private static String stripAnnotations(String text) {
		return text.replaceAll("(?s)\nannotation\\(.*?\n\\)", "");
	}

	@Test
	public void theHeadersParameterOrderIsKept() throws Exception {
		// InlineExample writes parameters type first: (Int In, Int Gain, Int FV => Int Out).
		LoadedFile file = load("InlineExample.dia", source("InlineExample.dia"));
		String after = DiagramEdits.addParameter(file, "FeedForward", true, "Bias", "Int", 0, 0);
		assertTrue(after.contains("diagramtype FeedForward(Int In, Int Gain, Int FV, Int Bias => Int Out) {"));
		assertCleanParse(load("InlineExample.dia", after));
	}

	@Test
	public void deletingParametersRewritesTheHeader() {
		String text = "diagramtype A(a: Int, b: Int => c: Int) {\n  connect(a, c);\n}\n";
		LoadedFile file = load("x.dia", text);
		assertEquals("diagramtype A(b: Int => c: Int) {\n}\n",
				DiagramEdits.delete(file, "A", List.of(), List.of("a")));
		assertEquals("diagramtype A(a: Int, b: Int) {\n}\n",
				DiagramEdits.delete(file, "A", List.of(), List.of("c")));
		assertEquals("diagramtype A() {\n}\n",
				DiagramEdits.delete(file, "A", List.of(), List.of("a", "b", "c")));
	}

	@Test
	public void parametersUsedElsewhereCannotBeDeleted() throws Exception {
		// TwoCounters' Main connects acc1.in, a block of type Acc.
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("acc1.in was not found", () -> DiagramEdits.delete(file, "Acc", List.of(), List.of("in")));

		// A parameter that nothing uses can be added and deleted again.
		String before = file.source();
		String added = DiagramEdits.addParameter(file, "Acc", true, "reset", "Bool", 0, 100);
		LoadedFile withReset = load("TwoCounters.dia", added);
		String deleted = DiagramEdits.delete(withReset, "Acc", List.of(), List.of("reset"));
		assertTrue(deleted.contains("diagramtype Acc(in: Int => out: Int) {"));
		assertFalse(deleted.contains("reset"));
		assertCleanParse(load("TwoCounters.dia", deleted));
		assertEquals("only the stored layout remains", stripAnnotations(before), stripAnnotations(deleted));
	}

	@Test
	public void deletingAParameterRemovesItsAnnotationEntry() throws Exception {
		// Rid(in: Real => out: Real) has annotations; add a parameter, then delete it.
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		String added = DiagramEdits.addParameter(file, "Rid", true, "gain", "Real", 5, 80);
		assertTrue(ridText(added).contains("  gain = (5, 80, "));
		String after = DiagramEdits.delete(load("PLib.dia", added), "Rid", List.of(), List.of("gain"));
		assertTrue(ridText(after), ridText(after).startsWith("diagramtype Rid(in: Real => out: Real) {"));
		assertFalse(ridText(after), ridText(after).contains("gain"));
		assertCleanParse(load("PLib.dia", after));
	}

	private static String ridText(String text) {
		int rid = text.indexOf("diagramtype Rid(");
		return text.substring(rid, text.indexOf("diagramtype", rid + 1));
	}

	@Test
	public void inheritedParametersCannotBeDeleted() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		assertFalse(nodes(file, "PTrack").get("P::SP").deletable());
		assertTrue(nodes(file, "PTrack").get("Track").deletable());
		assertRefused("not declared in PTrack", () -> DiagramEdits.delete(file, "PTrack", List.of(), List.of("P::SP")));
	}

	@Test
	public void parametersCanBeReordered() {
		String text = "diagramtype A(a: Int, b: Int => c: Int, d: Int) {\n  connect(a, c);\n  connect(b, d);\n}\n"
				+ "diagramtype U {\n  x: A;\n}\n";
		LoadedFile file = load("x.dia", text);
		String after = DiagramEdits.setParameters(file, "A", List.of("b", "a"), List.of("d", "c"));
		assertEquals(text.replace("A(a: Int, b: Int => c: Int, d: Int)", "A(b: Int, a: Int => d: Int, c: Int)"), after);
		LoadedFile reparsed = load("x.dia", after);
		assertCleanParse(reparsed);
		// The order of the ports of blocks of that type follows.
		DiagramNode x = nodes(reparsed, "U").get("x");
		assertEquals(List.of("b", "a"), x.inPorts().stream().map(Diagram.DiagramPort::name).toList());
		assertEquals(List.of("d", "c"), x.outPorts().stream().map(Diagram.DiagramPort::name).toList());
	}

	@Test
	public void parametersLeftOutAreDeleted() {
		String text = "diagramtype A(a: Int, b: Int => c: Int, d: Int) {\n  connect(a, c);\n  connect(b, d);\n}\n";
		String after = DiagramEdits.setParameters(load("x.dia", text), "A", List.of("b"), List.of("d", "c"));
		assertEquals("diagramtype A(b: Int => d: Int, c: Int) {\n  connect(b, d);\n}\n", after);
		assertEquals("diagramtype A() {\n}\n",
				DiagramEdits.setParameters(load("x.dia", text), "A", List.of(), List.of()));
	}

	@Test
	public void reorderingKeepsHowParametersAreWritten() throws Exception {
		LoadedFile file = load("InlineExample.dia", source("InlineExample.dia"));
		String after = DiagramEdits.setParameters(file, "FeedForward", List.of("FV", "In", "Gain"), List.of("Out"));
		assertTrue(after.contains("diagramtype FeedForward(Int FV, Int In, Int Gain => Int Out) {"));
		assertCleanParse(load("InlineExample.dia", after));
	}

	@Test
	public void onlyTheTypesOwnParametersCanBeReordered() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		// PTrack(Track: Bool, TrackValue: Real) extends P: P's parameters come first.
		String after = DiagramEdits.setParameters(file, "PTrack", List.of("TrackValue", "Track"), List.of());
		assertTrue(after.contains("diagramtype PTrack(TrackValue: Real, Track: Bool) extends P {"));
		List<String> ins = DiagramBuilder.build(load("PLib.dia", after), "PTrack").nodes().stream()
				.filter(n -> n.kind().equals(Diagram.IN_PARAMETER)).map(DiagramNode::id).toList();
		assertEquals(List.of("P::SP", "P::PV", "P::K", "TrackValue", "Track"), ins);

		assertRefused("SP is not an in-parameter declared in PTrack",
				() -> DiagramEdits.setParameters(file, "PTrack", List.of("SP", "Track", "TrackValue"), List.of()));
		assertRefused("Track is not an out-parameter",
				() -> DiagramEdits.setParameters(file, "PTrack", List.of("TrackValue"), List.of("Track")));
		assertRefused("listed twice",
				() -> DiagramEdits.setParameters(file, "PTrack", List.of("Track", "Track", "TrackValue"), List.of()));
	}

	@Test
	public void parametersUsedElsewhereAreNotDeletedByReordering() throws Exception {
		// TwoCounters' Main connects acc1.in.
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("acc1.in was not found",
				() -> DiagramEdits.setParameters(file, "Acc", List.of(), List.of("out")));
	}

	/**
	 * In every example and type: a variable and both kinds of parameter can be
	 * added (everything else stays in place), and every deletable variable and
	 * parameter can be deleted, or the deletion is refused with a reason.
	 */
	@Test
	public void editsInEveryExample() throws Exception {
		int deleted = 0;
		int types = 0;
		int refusedAdds = 0;
		for (String f : Examples.workspace().files()) {
			if (Examples.EXCLUDED.contains(f)) {
				continue;
			}
			LoadedFile file = load(f, source(f));
			for (String type : DiagramBuilder.diagramTypeNames(file.compilationUnit())) {
				Map<String, DiagramNode> before = nodes(file, type);
				List<java.util.function.Supplier<String>> additions = List.of(
						() -> DiagramEdits.addVariable(file, type, "var", "zzVariable", "Int", 500, 400),
						() -> DiagramEdits.addParameter(file, type, true, "zzIn", "Bool", 0, 400),
						() -> DiagramEdits.addParameter(file, type, false, "zzOut", "Real", 600, 400));
				for (var addition : additions) {
					String added;
					try {
						added = addition.get();
					} catch (IllegalArgumentException refused) {
						// E.g. a connection elsewhere uses a block of this type by its
						// name alone (its single port), which a new parameter breaks.
						assertTrue(refused.getMessage(), refused.getMessage().startsWith("Not allowed"));
						refusedAdds++;
						continue;
					}
					LoadedFile reparsed = load(f, added);
					assertCleanParse(reparsed);
					Map<String, DiagramNode> after = nodes(reparsed, type);
					assertEquals(f + " " + type, before.size() + 1, after.size());
					assertUnmoved(before, after);
				}
				types++;

				for (DiagramNode n : before.values()) {
					if (!n.deletable() || n.kind().equals(Diagram.BLOCK)) {
						continue;
					}
					try {
						LoadedFile without = load(f, DiagramEdits.delete(file, type, List.of(), List.of(n.id())));
						assertCleanParse(without);
						assertFalse(f + " " + type + " " + n.id(), nodes(without, type).containsKey(n.id()));
						deleted++;
					} catch (IllegalArgumentException refused) {
						assertTrue(refused.getMessage(), refused.getMessage().startsWith("Not allowed"));
					}
				}
			}
		}
		assertTrue("types " + types, types > 30);
		assertTrue("refused additions " + refusedAdds, refusedAdds < types);
		// Many parameters are used elsewhere (refused); enough are not.
		assertTrue("deleted " + deleted, deleted > 10);
	}
}
