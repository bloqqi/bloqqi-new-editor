package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;

import org.bloqqi.compiler.ast.TypeDecl;
import org.junit.Test;

/** Renaming and deleting types, subtypes and super types. */
public class TypeManagementTest {

	private static String source(String fileName) throws Exception {
		return Files.readString(Examples.dir().resolve(fileName), StandardCharsets.UTF_8);
	}

	private static LoadedFile load(String fileName, String text) {
		return BloqqiLoader.load(fileName, text);
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

	private static boolean containsWord(String text, String word) {
		return Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(word) + "(?![A-Za-z0-9_$])").matcher(text).find();
	}

	// ----- Rename -----

	@Test
	public void renamingADiagramTypeUpdatesItsUses() throws Exception {
		String before = source("PLib.dia");
		String after = DiagramEdits.renameType(load("PLib.dia", before), "P", "Ctrl");

		assertTrue(after.startsWith("diagramtype Ctrl(SP: Real, PV: Real, K: Real => Out: Real) {"));
		assertTrue(after.contains("diagramtype PI(Track: Bool, TrackValue: Real, Ti: Real) extends Ctrl {"));
		assertTrue(after.contains("diagramtype PTrack(Track: Bool, TrackValue: Real) extends Ctrl {"));
		assertTrue(after.contains("  Ctrl::e = ("));
		assertFalse("no P left", containsWord(after, "P"));
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		assertTrue(DiagramBuilder.build(reparsed, "PTrack").nodes().stream().anyMatch(n -> n.id().equals("Ctrl::e")));
		assertEquals("the stored layout of inherited nodes is kept",
				DiagramBuilder.build(load("PLib.dia", before), "PTrack").nodes().stream()
						.filter(n -> n.id().equals("P::e")).findFirst().orElseThrow().x(),
				DiagramBuilder.build(reparsed, "PTrack").nodes().stream()
						.filter(n -> n.id().equals("Ctrl::e")).findFirst().orElseThrow().x());
	}

	@Test
	public void renamingAnExternalFunctionUpdatesBlocksOfIt() throws Exception {
		String before = source("TwoCounters.dia");
		String after = DiagramEdits.renameType(load("TwoCounters.dia", before), "Print", "Show");
		assertEquals(before.replace("external Print(", "external Show(")
				.replace("print1: Print;", "print1: Show;").replace("print2: Print;", "print2: Show;"), after);
		assertCleanParse(load("TwoCounters.dia", after));
	}

	@Test
	public void simpleNamedBlocksFollowTheirType() {
		String text = "diagramtype A(x: Int) {\n}\n"
				+ "diagramtype M(v: Int) {\n  A_1;\n  A_2;\n  connect(v, A_1.x);\n}\n"
				+ "annotation(\n  A_1 = (10, 10, 60, 20)\n)\n";
		String after = DiagramEdits.renameType(load("x.dia", text), "A", "B");
		assertEquals("diagramtype B(x: Int) {\n}\n"
				+ "diagramtype M(v: Int) {\n  B_1;\n  B_2;\n  connect(v, B_1.x);\n}\n"
				+ "annotation(\n  B_1 = (10, 10, 60, 20)\n)\n", after);
		assertCleanParse(load("x.dia", after));
	}

	@Test
	public void typeRenamesAreChecked() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("already a type called Acc", () -> DiagramEdits.renameType(file, "Main", "Acc"));
		assertRefused("already a type called Add", () -> DiagramEdits.renameType(file, "Main", "Add"));
		assertRefused("Not a valid name", () -> DiagramEdits.renameType(file, "Main", "1M"));
		assertRefused("No diagram type or external function Add", () -> DiagramEdits.renameType(file, "Add", "Plus"));
		assertEquals(file.source(), DiagramEdits.renameType(file, "Main", "Main"));
	}

	// ----- Delete -----

	@Test
	public void typesInUseCannotBeDeleted() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("Cannot delete Acc: it is used in Main", () -> DiagramEdits.deleteType(file, "Acc"));
		assertRefused("Cannot delete Print: it is used in Main", () -> DiagramEdits.deleteType(file, "Print"));
		LoadedFile plib = load("PLib.dia", source("PLib.dia"));
		assertRefused("it is used in PI, PD, PTrack", () -> DiagramEdits.deleteType(plib, "P"));
	}

	@Test
	public void deletingAnUnusedTypeRemovesItsDeclaration() throws Exception {
		String before = source("TwoCounters.dia");
		String after = DiagramEdits.deleteType(load("TwoCounters.dia", before), "Main");
		int start = before.indexOf("diagramtype Main()");
		int end = before.indexOf("diagramtype Acc(");
		assertEquals(before.substring(0, start) + before.substring(end), after);
		assertCleanParse(load("TwoCounters.dia", after));

		// With annotations: they go too.
		String withLayout = DiagramEdits.moveNodes(load("TwoCounters.dia", after), "Acc",
				List.of(new DiagramEdits.Move("add", 100, 100)));
		String noAcc = DiagramEdits.deleteType(load("TwoCounters.dia", withLayout), "Acc");
		// (The file's final blank line stays.)
		assertEquals("external Print(in: Int);\n\n\n", noAcc);
	}

	// ----- Subtypes and super types -----

	@Test
	public void aSubtypeInheritsEverything() throws Exception {
		String before = source("PLib.dia");
		LoadedFile file = load("PLib.dia", before);
		String after = DiagramEdits.addSubtype(file, "P", "PX");
		assertEquals(before + "diagramtype PX extends P {\n}\n", after);
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		Diagram px = DiagramBuilder.build(reparsed, "PX");
		assertTrue(px.nodes().stream().allMatch(Diagram.DiagramNode::inherited));
		assertEquals(DiagramBuilder.build(file, "P").edges().size(), px.edges().size());

		LoadedFile counters = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("Print is not a diagram type", () -> DiagramEdits.addSubtype(counters, "Print", "X"));
		assertRefused("already a type called PI", () -> DiagramEdits.addSubtype(file, "P", "PI"));
	}

	@Test
	public void superTypesCanBeChanged() {
		String text = "diagramtype A(x: Int) {\n}\ndiagramtype B(=> y: Int) {\n}\n";
		String extended = DiagramEdits.setSuperTypes(load("x.dia", text), "B", List.of("A"));
		assertEquals("diagramtype A(x: Int) {\n}\ndiagramtype B(=> y: Int) extends A {\n}\n", extended);
		assertCleanParse(load("x.dia", extended));
		assertEquals(text, DiagramEdits.setSuperTypes(load("x.dia", extended), "B", List.of()));

		String noParens = "diagramtype A {\n}\ndiagramtype B {\n}\n";
		assertEquals("diagramtype A {\n}\ndiagramtype B extends A {\n}\n",
				DiagramEdits.setSuperTypes(load("x.dia", noParens), "B", List.of("A")));
	}

	@Test
	public void theOrderOfSuperTypesMatters() {
		String text = "diagramtype A(x: Int) {\n}\ndiagramtype B(y: Int) {\n}\ndiagramtype C {\n}\n";
		String ab = DiagramEdits.setSuperTypes(load("x.dia", text), "C", List.of("A", "B"));
		assertTrue(ab.contains("diagramtype C extends A, B {"));
		String ba = DiagramEdits.setSuperTypes(load("x.dia", ab), "C", List.of("B", "A"));
		assertTrue(ba.contains("diagramtype C extends B, A {"));
		// Inherited in that order: e.g. C's parameters (its ports as a block).
		assertEquals(List.of("A::x", "B::y"), inParameters(load("x.dia", ab), "C"));
		assertEquals(List.of("B::y", "A::x"), inParameters(load("x.dia", ba), "C"));
		assertEquals(List.of("A", "B"), DiagramBuilder.build(load("x.dia", ab), "C").superTypes());
	}

	private static List<String> inParameters(LoadedFile file, String type) {
		return DiagramBuilder.build(file, type).nodes().stream()
				.filter(n -> n.kind().equals(Diagram.IN_PARAMETER)).map(Diagram.DiagramNode::id).toList();
	}

	@Test
	public void superTypeChangesThatBreakThingsAreRefused() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		// PTrack intercepts P's out-parameter Out.
		assertRefused("Not allowed", () -> DiagramEdits.setSuperTypes(file, "PTrack", List.of()));
		// P extends PTrack, which extends P.
		assertRefused("Not allowed", () -> DiagramEdits.setSuperTypes(file, "P", List.of("PTrack")));
		assertRefused("cannot extend itself", () -> DiagramEdits.setSuperTypes(file, "P", List.of("P")));
		assertRefused("RAdd is not a diagram type", () -> DiagramEdits.setSuperTypes(file, "P", List.of("RAdd")));
	}

	/** Every diagram type and external function in every example can be renamed (or is refused with a reason). */
	@Test
	public void typeRenamesInEveryExample() throws Exception {
		int renamed = 0;
		for (String f : Examples.workspace().files()) {
			if (Examples.EXCLUDED.contains(f)) {
				continue;
			}
			LoadedFile file = load(f, source(f));
			for (TypeDecl td : file.compilationUnit().typeDecls()) {
				if (!td.isDiagramType() && !td.isExternalFunction()) {
					continue;
				}
				String newName = td.name() + "Renamed";
				try {
					LoadedFile after = load(f, DiagramEdits.renameType(file, td.name(), newName));
					assertCleanParse(after);
					// (The old name may remain as the name of something else, e.g.
					// IPart's out-parameter IPart.)
					assertEquals(f + " " + td.name(), null, after.program().lookupType(td.name()));
					assertTrue(f + " " + td.name(), after.program().lookupType(newName) != null);
					renamed++;
				} catch (IllegalArgumentException refused) {
					assertTrue(refused.getMessage(), refused.getMessage().startsWith("Not allowed"));
				}
			}
		}
		assertTrue("renamed " + renamed, renamed > 30);
	}
}
