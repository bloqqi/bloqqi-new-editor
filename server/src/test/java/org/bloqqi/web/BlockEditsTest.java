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
import org.bloqqi.web.Diagram.PaletteEntry;
import org.junit.Test;

/** Adding and deleting blocks: the new text is as expected and reparses without errors. */
public class BlockEditsTest {

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

	/** The other nodes are where they were. */
	private static void assertUnmoved(Map<String, DiagramNode> before, Map<String, DiagramNode> after) {
		for (DiagramNode n : before.values()) {
			if (!n.kind().equals(Diagram.LITERAL)) {
				DiagramNode m = after.get(n.id());
				assertEquals(n.id(), List.of(n.x(), n.y()), List.of(m.x(), m.y()));
			}
		}
	}

	@Test
	public void paletteFollowsTheOldEditor() throws Exception {
		Diagram d = DiagramBuilder.build(load("PLib.dia", source("PLib.dia")), "P");
		List<String> local = d.palette().stream().filter(e -> e.group().equals(Diagram.LOCAL))
				.map(PaletteEntry::name).toList();
		assertEquals("the file's types except the shown one",
				List.of("IPart", "DPart", "Rid", "PI", "PD", "PTrack", "Main"), local);
		List<String> standard = d.palette().stream().filter(e -> e.group().equals(Diagram.STANDARD))
				.map(PaletteEntry::name).toList();
		assertTrue(standard.containsAll(List.of("Add", "RMul", "SelReal")));
		assertTrue(d.palette().stream().filter(e -> e.group().equals(Diagram.LOCAL))
				.allMatch(e -> e.kind().equals("diagramType")));
	}

	@Test
	public void addingABlockInsertsItAfterTheLastBlock() throws Exception {
		String before = source("PLib.dia");
		LoadedFile file = load("PLib.dia", before);
		Map<String, DiagramNode> old = nodes(file, "P");

		String after = DiagramEdits.addBlock(file, "P", "RAdd", 40, 90);

		assertTrue(after.startsWith("diagramtype P(SP: Real, PV: Real, K: Real => Out: Real) {\n"
				+ "  e: RSub;\n  PPart: RMul;\n  RAdd_1;\n  connect(e.out, PPart.in1);"));
		// Two input ports: minimum height 30 (Layout.minHeight).
		assertTrue("position stored", after.contains("  RAdd_1 = (40, 90, 60, 30)"));
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		Map<String, DiagramNode> now = nodes(reparsed, "P");
		assertEquals("RAdd", now.get("RAdd_1").label());
		assertEquals(40, now.get("RAdd_1").x());
		assertUnmoved(old, now);
	}

	@Test
	public void newBlocksGetTheFirstFreeNumber() throws Exception {
		// IPart already has RAdd_1.
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		String once = DiagramEdits.addBlock(file, "IPart", "RAdd", 10, 10);
		assertTrue(once.contains("  RAdd_2;\n"));
		String twice = DiagramEdits.addBlock(load("PLib.dia", once), "IPart", "RAdd", 10, 60);
		assertTrue(twice.contains("  RAdd_2;\n  RAdd_3;\n"));
	}

	@Test
	public void addingToAnAutoLaidOutTypeKeepsTheOtherNodesWhereTheyAre() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		Map<String, DiagramNode> old = nodes(file, "Acc");
		String after = DiagramEdits.addBlock(file, "Acc", "Add", 300, 150);
		assertTrue(after.contains("  add: Add;\n  Add_1;\n"));
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		assertUnmoved(old, nodes(reparsed, "Acc"));
	}

	@Test
	public void blocksGoBeforeConnectionsAndAfterVariables() {
		String onlyConnections = "diagramtype A(a: Int => b: Int) {\n  connect(a, b);\n}\n";
		assertTrue(DiagramEdits.addBlock(load("x.dia", onlyConnections), "A", "Add", 0, 0)
				.startsWith("diagramtype A(a: Int => b: Int) {\n  Add_1;\n  connect(a, b);\n}\n"));

		String onlyVariables = "diagramtype A(a: Int => b: Int) {\n  var v: Int;\n  connect(a, b);\n}\n";
		assertTrue(DiagramEdits.addBlock(load("x.dia", onlyVariables), "A", "Add", 0, 0)
				.startsWith("diagramtype A(a: Int => b: Int) {\n  var v: Int;\n  Add_1;\n  connect(a, b);\n}\n"));

		String empty = "diagramtype A() {\n}\n";
		assertTrue(DiagramEdits.addBlock(load("x.dia", empty), "A", "Add", 0, 0)
				.startsWith("diagramtype A() {\n  Add_1;\n}\n"));
	}

	@Test
	public void onlyPaletteTypesCanBeAdded() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		assertRefused("Cannot add a block of P", () -> DiagramEdits.addBlock(file, "P", "P", 0, 0));
		assertRefused("Cannot add a block of Real", () -> DiagramEdits.addBlock(file, "P", "Real", 0, 0));
	}

	@Test
	public void blocksThatWouldMakeATypeCycleAreRefused() throws Exception {
		// PTrack extends P, so a PTrack block inside P would contain itself.
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		assertRefused("Not allowed", () -> DiagramEdits.addBlock(file, "P", "PTrack", 0, 0));
	}

	@Test
	public void deletingABlockRemovesItsConnectionsToo() throws Exception {
		String before = source("TwoCounters.dia");
		String after = DiagramEdits.delete(load("TwoCounters.dia", before), "Acc", List.of(), List.of("add"));

		assertEquals(before.replace("  add: Add;\n", "")
				.replace("  connect(in, add.in1);\n", "")
				.replace("  connect(state, add.in2);\n", "")
				.replace("  connect(add.out, out);\n", "")
				.replace("  connect(add.out, state);\n", ""), after);
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		assertFalse(nodes(reparsed, "Acc").containsKey("add"));
		assertTrue(DiagramBuilder.build(reparsed, "Acc").edges().isEmpty());
	}

	@Test
	public void deletingABlockRemovesItsAnnotationEntry() throws Exception {
		String before = source("PLib.dia");
		LoadedFile file = load("PLib.dia", before);
		String added = DiagramEdits.addBlock(file, "Rid", "RAdd", 50, 50);
		assertTrue(typeText(added, "Rid").contains("RAdd_1 = ("));
		String deleted = DiagramEdits.delete(load("PLib.dia", added), "Rid", List.of(), List.of("RAdd_1"));
		assertFalse(typeText(deleted, "Rid").contains("RAdd_1"));
		assertCleanParse(load("PLib.dia", deleted));
	}

	@Test
	public void blocksUsedElsewhereCannotBeDeleted() throws Exception {
		// PI (extends P) connects e.out.
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		assertRefused("Not allowed", () -> DiagramEdits.delete(file, "P", List.of(), List.of("e")));
	}

	@Test
	public void inheritedBlocksCannotBeDeleted() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		assertFalse(nodes(file, "PTrack").get("P::e").deletable());
		assertTrue(nodes(file, "PTrack").get("SelReal_1").deletable());
		assertRefused("not declared in PTrack", () -> DiagramEdits.delete(file, "PTrack", List.of(), List.of("P::e")));
		assertRefused("No block", () -> DiagramEdits.delete(file, "PTrack", List.of(), List.of("nosuch")));
	}

	@Test
	public void blocksAndConnectionsAreDeletedInOneEdit() throws Exception {
		String before = source("PLib.dia");
		String after = DiagramEdits.delete(load("PLib.dia", before), "PTrack",
				List.of("TrackValue.out->SelReal_1.in:in1"), List.of("SelReal_1"));
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		String ptrack = typeText(after, "PTrack");
		assertFalse(ptrack, ptrack.contains("SelReal_1"));
		assertFalse(ptrack.contains("intercept"));
	}

	/**
	 * In every example: a standard block can be added to every type, and every
	 * deletable block can be deleted, or the deletion is refused with a reason
	 * (something else uses the block), never by a broken file.
	 */
	@Test
	public void editsInEveryExample() throws Exception {
		int deleted = 0;
		int added = 0;
		for (String f : Examples.workspace().files()) {
			if (Examples.EXCLUDED.contains(f)) {
				continue;
			}
			LoadedFile file = load(f, source(f));
			for (String type : DiagramBuilder.diagramTypeNames(file.compilationUnit())) {
				Map<String, DiagramNode> before = nodes(file, type);

				LoadedFile withAdd = load(f, DiagramEdits.addBlock(file, type, "Add", 400, 300));
				assertCleanParse(withAdd);
				Map<String, DiagramNode> after = nodes(withAdd, type);
				assertUnmoved(before, after);
				assertEquals(before.size() + 1, after.size());
				added++;

				for (DiagramNode n : before.values()) {
					if (!n.deletable()) {
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
		assertTrue("added " + added, added > 30);
		assertTrue("deleted " + deleted, deleted > 30);
	}
}
