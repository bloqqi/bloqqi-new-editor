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

import org.bloqqi.web.Diagram.DiagramNode;
import org.junit.Test;

/** Renaming, changing literal values and auto layout. */
public class RenameEditsTest {

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

	/** The text of a diagram type, from "diagramtype Name" to the next "diagramtype". */
	private static String typeText(String text, String name) {
		int start = text.indexOf("diagramtype " + name + "(");
		if (start < 0) {
			start = text.indexOf("diagramtype " + name + " ");
		}
		int end = text.indexOf("diagramtype ", start + 1);
		return text.substring(start, end < 0 ? text.length() : end);
	}

	// ----- Rename -----

	@Test
	public void renamingABlockUpdatesItsUsesEverywhere() throws Exception {
		String before = source("PLib.dia");
		String after = DiagramEdits.rename(load("PLib.dia", before), "P", "e", "err");

		String p = typeText(after, "P");
		assertTrue(p, p.contains("  err: RSub;"));
		assertTrue(p, p.contains("connect(err.out, PPart.in1);"));
		assertTrue(p, p.contains("connect(SP, err.in1);"));
		assertTrue(p, p.contains("  err = (76, 7, 24, 30)"));
		assertFalse(p, p.matches("(?s).*\\be\\b.*"));
		// The subtype PI connects e.out to IPart.e: only the first e is P's block.
		String pi = typeText(after, "PI");
		assertTrue(pi, pi.contains("connect(err.out, IPart.e);"));
		assertTrue(pi, pi.contains("  P::err = ("));
		assertTrue(typeText(after, "PTrack").contains("  P::err = ("));
		// Nothing else changed: IPart has a parameter e of its own.
		assertEquals(typeText(before, "IPart"), typeText(after, "IPart"));

		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		assertTrue(nodes(reparsed, "P").containsKey("err"));
		assertTrue(nodes(reparsed, "PTrack").containsKey("P::err"));
		assertEquals("the stored position follows",
				nodes(load("PLib.dia", before), "PTrack").get("P::e").x(), nodes(reparsed, "PTrack").get("P::err").x());
	}

	@Test
	public void aSimpleNamedBlockGetsAnOrdinaryDeclaration() throws Exception {
		// PTrack: SelReal_1; connect(Track, SelReal_1.sel); intercept Out with SelReal_1.in2, SelReal_1.out;
		String after = DiagramEdits.rename(load("PLib.dia", source("PLib.dia")), "PTrack", "SelReal_1", "selector");
		String ptrack = typeText(after, "PTrack");
		assertTrue(ptrack, ptrack.contains("  selector: SelReal;\n"));
		assertTrue(ptrack, ptrack.contains("connect(Track, selector.sel);"));
		assertTrue(ptrack, ptrack.contains("intercept Out with selector.in2, selector.out;"));
		assertTrue(ptrack, ptrack.contains("  selector = ("));
		assertFalse(ptrack, ptrack.contains("SelReal_1"));
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		assertEquals("selector", nodes(reparsed, "PTrack").get("selector").label());
	}

	@Test
	public void renamingAVariable() throws Exception {
		String before = source("TwoCounters.dia");
		String after = DiagramEdits.rename(load("TwoCounters.dia", before), "Acc", "state", "total");
		assertEquals(before.replace("var state: Int;", "var total: Int;")
				.replace("connect(state, add.in2);", "connect(total, add.in2);")
				.replace("connect(add.out, state);", "connect(add.out, total);"), after);
		assertCleanParse(load("TwoCounters.dia", after));
	}

	@Test
	public void renamingAParameterUpdatesPortUsesInOtherTypes() throws Exception {
		// Main connects 1 and 2 to acc1.in and acc2.in, blocks of type Acc.
		String before = source("TwoCounters.dia");
		String after = DiagramEdits.rename(load("TwoCounters.dia", before), "Acc", "in", "start");
		assertEquals(before.replace("Acc(in: Int => out: Int)", "Acc(start: Int => out: Int)")
				.replace("connect(in, add.in1);", "connect(start, add.in1);")
				.replace("connect(1, acc1.in);", "connect(1, acc1.start);")
				.replace("connect(2, acc2.in);", "connect(2, acc2.start);"), after);
		assertCleanParse(load("TwoCounters.dia", after));
	}

	@Test
	public void typeFirstParametersAreRenamedToo() throws Exception {
		// InlineExample: diagramtype FeedForward(Int In, Int Gain, Int FV => Int Out)
		String after = DiagramEdits.rename(load("InlineExample.dia", source("InlineExample.dia")),
				"FeedForward", "In", "Input");
		assertTrue(after.contains("diagramtype FeedForward(Int Input, Int Gain, Int FV => Int Out) {"));
		assertCleanParse(load("InlineExample.dia", after));
	}

	@Test
	public void renamesThatWouldClashAreRefused() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		assertRefused("Not a valid name", () -> DiagramEdits.rename(file, "P", "e", "2e"));
		assertRefused("already used", () -> DiagramEdits.rename(file, "P", "e", "PPart"));
		assertRefused("already used", () -> DiagramEdits.rename(file, "P", "e", "Out"));
		// The subtype PI has its own block Rid, and writes connect(e.out, IPart.e):
		// renamed, "Rid.out" there would mean PI's Rid.
		assertRefused("already means something else", () -> DiagramEdits.rename(file, "P", "e", "Rid"));
		// PTrack has its own SelReal_1 but no unqualified use of e: allowed (P's
		// block is then P::SelReal_1 in PTrack, as Bloqqi allows).
		assertCleanParse(load("PLib.dia", DiagramEdits.rename(file, "P", "e", "SelReal_1")));
		assertRefused("cannot be renamed here", () -> DiagramEdits.rename(file, "PTrack", "P::e", "x"));
		assertEquals("same name: no change", file.source(), DiagramEdits.rename(file, "P", "e", "e"));
	}

	/** Every renamable node in every example can be renamed (or is refused with a reason). */
	@Test
	public void renamesInEveryExample() throws Exception {
		int renamed = 0;
		for (String f : Examples.workspace().files()) {
			if (Examples.EXCLUDED.contains(f)) {
				continue;
			}
			LoadedFile file = load(f, source(f));
			for (String type : DiagramBuilder.diagramTypeNames(file.compilationUnit())) {
				Diagram before = DiagramBuilder.build(file, type);
				for (DiagramNode n : before.nodes()) {
					if (!n.deletable()) {
						continue;
					}
					String newName = n.id() + "Renamed";
					try {
						LoadedFile after = load(f, DiagramEdits.rename(file, type, n.id(), newName));
						assertCleanParse(after);
						Diagram d = DiagramBuilder.build(after, type);
						assertTrue(f + " " + type + " " + n.id(), d.nodes().stream().anyMatch(x -> x.id().equals(newName)));
						assertEquals(f + " " + type + " " + n.id(), before.edges().size(), d.edges().size());
						renamed++;
					} catch (IllegalArgumentException refused) {
						assertTrue(refused.getMessage(), refused.getMessage().startsWith("Not allowed"));
					}
				}
			}
		}
		assertTrue("renamed " + renamed, renamed > 100);
	}

	// ----- Literals -----

	@Test
	public void changingALiteralsValue() throws Exception {
		String before = source("TwoCounters.dia");
		LoadedFile file = load("TwoCounters.dia", before);
		String after = DiagramEdits.setLiteral(file, "Main", "#literal:acc1.in:in", " 15 ");
		assertEquals(before.replace("connect(1, acc1.in);", "connect(15, acc1.in);"), after);
		assertRefused("Not a literal", () -> DiagramEdits.setLiteral(file, "Main", "#literal:acc1.in:in", "x"));
		assertRefused("Not allowed", () -> DiagramEdits.setLiteral(file, "Main", "#literal:acc1.in:in", "true"));
		assertRefused("No literal", () -> DiagramEdits.setLiteral(file, "Main", "nosuch", "3"));
	}

	// ----- Auto layout -----

	@Test
	public void autoLayoutStoresAFreshLayout() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		String after = DiagramEdits.autoLayout(file, "IPart");
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		Map<String, DiagramNode> before = nodes(file, "IPart");
		Map<String, DiagramNode> now = nodes(reparsed, "IPart");
		assertEquals(before.keySet(), now.keySet());
		assertTrue("something moved", before.values().stream().anyMatch(n ->
				n.x() != now.get(n.id()).x() || n.y() != now.get(n.id()).y()));
		// Left to right: the in-parameters come before the out-parameter.
		assertTrue(now.get("e").x() < now.get("IPart").x());
		// Other types are untouched.
		assertEquals(typeText(file.source(), "P"), typeText(after, "P"));
	}
}
