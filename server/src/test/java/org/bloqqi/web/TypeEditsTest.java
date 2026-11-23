package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.bloqqi.web.Diagram.PaletteEntry;
import org.bloqqi.web.DiagramEdits.Parameter;
import org.junit.Test;

/** Adding diagram types and external functions. */
public class TypeEditsTest {

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

	// ----- Diagram types -----

	@Test
	public void aNewDiagramTypeIsAppendedToTheFile() throws Exception {
		String before = source("TwoCounters.dia");
		String after = DiagramEdits.addDiagramType(load("TwoCounters.dia", before), "Counter");

		assertEquals(before + "diagramtype Counter {\n}\n", after);
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		assertEquals(List.of("Main", "Acc", "Counter"), DiagramBuilder.diagramTypeNames(reparsed.compilationUnit()));
		Diagram d = DiagramBuilder.build(reparsed, "Counter");
		assertTrue("empty", d.nodes().isEmpty());
		assertTrue(d.palette().stream().map(PaletteEntry::name).toList().containsAll(List.of("Print", "Main", "Acc")));
	}

	@Test
	public void filesWithoutAFinalNewlineOrEmptyFiles() {
		assertEquals("diagramtype A {\n}\ndiagramtype B {\n}\n",
				DiagramEdits.addDiagramType(load("x.dia", "diagramtype A {\n}"), "B"));
		assertEquals("diagramtype A {\n}\n", DiagramEdits.addDiagramType(load("x.dia", ""), "A"));
		assertEquals("diagramtype A {\r\n}\r\ndiagramtype B {\r\n}\r\n",
				DiagramEdits.addDiagramType(load("x.dia", "diagramtype A {\r\n}\r\n"), "B"));
	}

	@Test
	public void typeNamesMustBeNew() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("already a type called Acc", () -> DiagramEdits.addDiagramType(file, "Acc"));
		assertRefused("already a type called Print", () -> DiagramEdits.addDiagramType(file, "Print"));
		assertRefused("already a type called Add", () -> DiagramEdits.addDiagramType(file, "Add")); // standard library
		assertRefused("already a type called Int", () -> DiagramEdits.addDiagramType(file, "Int"));
		assertRefused("Not a valid name", () -> DiagramEdits.addDiagramType(file, "2x"));
		assertRefused("Not a valid name", () -> DiagramEdits.addDiagramType(file, "a b"));
		assertRefused("already a type called Acc",
				() -> DiagramEdits.addExternalFunction(file, "Acc", List.of(), null));
	}

	// ----- External functions -----

	@Test
	public void externalFunctionsGoAfterTheLastOne() throws Exception {
		// TwoCounters starts with "external Print(in: Int);".
		String before = source("TwoCounters.dia");
		LoadedFile file = load("TwoCounters.dia", before);
		String after = DiagramEdits.addExternalFunction(file, "Log",
				List.of(new Parameter("msg", "Int"), new Parameter("level", "Int")), new Parameter("ok", "Bool"));

		assertEquals(before.replace("external Print(in: Int);\n",
				"external Print(in: Int);\nexternal Log(msg: Int, level: Int => ok: Bool);\n"), after);
		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		PaletteEntry log = DiagramBuilder.build(reparsed, "Main").palette().stream()
				.filter(e -> e.name().equals("Log")).findFirst().orElseThrow();
		assertEquals("externalFunction", log.kind());

		// It can be used as a block right away: two inputs, one output.
		String withBlock = DiagramEdits.addBlock(reparsed, "Main", "Log", 10, 200);
		var block = DiagramBuilder.build(load("TwoCounters.dia", withBlock), "Main").nodes().stream()
				.filter(n -> n.id().equals("Log_1")).findFirst().orElseThrow();
		assertEquals(List.of("msg", "level"), block.inPorts().stream().map(Diagram.DiagramPort::name).toList());
		assertEquals(List.of("ok"), block.outPorts().stream().map(Diagram.DiagramPort::name).toList());
	}

	@Test
	public void inputsAndOutputAreOptional() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertTrue(DiagramEdits.addExternalFunction(file, "Now", List.of(), new Parameter("t", "Real"))
				.contains("external Now(=> t: Real);\n"));
		assertTrue(DiagramEdits.addExternalFunction(file, "Beep", List.of(new Parameter("on", "Bool")), null)
				.contains("external Beep(on: Bool);\n"));
		String tick = DiagramEdits.addExternalFunction(file, "Tick", List.of(), null);
		assertTrue(tick.contains("external Tick();\n"));
		assertCleanParse(load("TwoCounters.dia", tick));
	}

	@Test
	public void withoutExternalFunctionsTheyGoAtTheEnd() throws Exception {
		String before = source("PLib.dia");
		String after = DiagramEdits.addExternalFunction(load("PLib.dia", before), "Sensor", List.of(),
				new Parameter("value", "Real"));
		assertEquals(before + "external Sensor(=> value: Real);\n", after);
		assertCleanParse(load("PLib.dia", after));
	}

	@Test
	public void parametersAreChecked() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		assertRefused("Two parameters are called a", () -> DiagramEdits.addExternalFunction(file, "F",
				List.of(new Parameter("a", "Int")), new Parameter("a", "Int")));
		assertRefused("Not a valid parameter name", () -> DiagramEdits.addExternalFunction(file, "F",
				List.of(new Parameter("1a", "Int")), null));
		assertRefused("The type must be one of", () -> DiagramEdits.addExternalFunction(file, "F",
				List.of(new Parameter("a", "Acc")), null));
	}
}
