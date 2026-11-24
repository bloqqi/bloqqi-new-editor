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

import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.TypeDecl;
import org.bloqqi.web.Diagram.DiagramNode;
import org.bloqqi.web.LayoutEdits.Move;
import org.junit.Test;

/** Moving nodes: the new text reparses, and the AST and diagram are as expected. */
public class LayoutEditsTest {

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

	private static DiagramType type(LoadedFile file, String name) {
		for (TypeDecl td : file.compilationUnit().typeDecls()) {
			if (td.name().equals(name)) {
				return (DiagramType) td;
			}
		}
		throw new AssertionError("no type " + name);
	}

	private static void assertPosition(DiagramNode n, int x, int y) {
		assertEquals(n.id() + " x", x, n.x());
		assertEquals(n.id() + " y", y, n.y());
	}

	private static void assertCleanParse(LoadedFile file) {
		assertTrue("parsed", file.isParsed());
		assertEquals("syntax errors", List.of(), file.syntaxErrors());
		assertEquals("semantic errors", List.of(), file.semanticErrors());
	}

	@Test
	public void movingABlockChangesOnlyItsAnnotation() throws Exception {
		String before = source("PLib.dia");
		LoadedFile file = load("PLib.dia", before);

		String after = LayoutEdits.moveNodes(file, "P", List.of(new Move("e", 200, 100)));

		// PLib.dia was written by the old editor (sorted, complete annotations),
		// so the only change is e's line.
		assertEquals(before.replace("  e = (76, 7, 24, 30)", "  e = (200, 100, 24, 30)"), after);
		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		DiagramNode e = nodes(reparsed, "P").get("e");
		assertPosition(e, 200, 100);
		assertEquals("size is kept", 24, e.width());
		assertEquals(30, e.height());
	}

	@Test
	public void parametersAndVariablesCanBeMoved() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		String after = LayoutEdits.moveNodes(file, "IPart",
				List.of(new Move("K", 5, 300), new Move("iPart", 400, 300)));
		Map<String, DiagramNode> nodes = nodes(load("PLib.dia", after), "IPart");
		assertPosition(nodes.get("K"), 5, 300);
		assertPosition(nodes.get("iPart"), 400, 300);
	}

	@Test
	public void firstMoveInAnAutoLaidOutTypeStoresTheWholeLayout() throws Exception {
		String before = source("TwoCounters.dia");
		LoadedFile file = load("TwoCounters.dia", before);
		Map<String, DiagramNode> old = nodes(file, "Main");

		String after = LayoutEdits.moveNodes(file, "Main", List.of(new Move("acc1", 300, 200)));

		LoadedFile reparsed = load("TwoCounters.dia", after);
		assertCleanParse(reparsed);
		assertEquals("one entry per block (literals are not stored)", 4, type(reparsed, "Main").getNumAnnotation());
		assertEquals("other types are untouched", 0, type(reparsed, "Acc").getNumAnnotation());
		Map<String, DiagramNode> moved = nodes(reparsed, "Main");
		assertPosition(moved.get("acc1"), 300, 200);
		for (String id : List.of("acc2", "print1", "print2")) {
			assertPosition(moved.get(id), old.get(id).x(), old.get(id).y());
		}

		// The block is inserted right after Main's closing brace; the rest is unchanged.
		int brace = before.indexOf("}\n") + 1;
		assertTrue(after.startsWith(before.substring(0, brace) + "\nannotation(\n  acc1 = (300, 200, "));
		assertTrue(after.endsWith(before.substring(brace)));
	}

	@Test
	public void inheritedNodesGetTheirOwnEntryInTheSubtype() throws Exception {
		LoadedFile file = load("PLib.dia", source("PLib.dia"));
		String after = LayoutEdits.moveNodes(file, "PTrack", List.of(new Move("P::e", 10, 150)));

		LoadedFile reparsed = load("PLib.dia", after);
		assertCleanParse(reparsed);
		assertPosition(nodes(reparsed, "PTrack").get("P::e"), 10, 150);
		assertPosition(nodes(reparsed, "P").get("e"), 76, 7);
		assertTrue(after.contains("  P::e = (10, 150, 34, 30)"));
	}

	@Test
	public void annotationGoesBeforeWiring() throws Exception {
		// FeedForward has a wiring declaration and no annotations.
		LoadedFile file = load("InlineExample.dia", source("InlineExample.dia"));
		String after = LayoutEdits.moveNodes(file, "FeedForward", List.of(new Move("Mul_1", 100, 50)));

		LoadedFile reparsed = load("InlineExample.dia", after);
		assertCleanParse(reparsed);
		DiagramType ff = type(reparsed, "FeedForward");
		assertTrue(ff.getNumAnnotation() > 0);
		assertTrue("wiring kept", ff.hasLocalWiring());
		assertPosition(nodes(reparsed, "FeedForward").get("Mul_1"), 100, 50);
	}

	@Test
	public void inlinedNodesCanBeMoved() throws Exception {
		LoadedFile file = load("InlineExample.dia", source("InlineExample.dia"));
		String after = LayoutEdits.moveNodes(file, "Tank",
				List.of(new Move("loop$master$controller", 160, 120)));
		LoadedFile reparsed = load("InlineExample.dia", after);
		assertCleanParse(reparsed);
		assertPosition(nodes(reparsed, "Tank").get("loop$master$controller"), 160, 120);
	}

	@Test
	public void commentsAndOtherTypesAreKept() throws Exception {
		String before = source("Tank.dia");
		assertTrue("example has comments", before.contains("//"));
		LoadedFile file = load("Tank.dia", before);
		String type = DiagramBuilder.diagramTypeNames(file.compilationUnit()).get(0);
		String block = DiagramBuilder.build(file, type).nodes().stream()
				.filter(n -> n.kind().equals(Diagram.BLOCK)).findFirst().orElseThrow().id();

		String after = LayoutEdits.moveNodes(file, type, List.of(new Move(block, 7, 7)));

		for (String line : before.split("\n")) {
			if (line.contains("//")) {
				assertTrue("kept: " + line, after.contains(line));
			}
		}
		assertPosition(nodes(load("Tank.dia", after), type).get(block), 7, 7);
	}

	@Test
	public void windowsLineEndingsAreKept() throws Exception {
		String before = source("TwoCounters.dia").replace("\n", "\r\n");
		String after = LayoutEdits.moveNodes(load("TwoCounters.dia", before), "Acc",
				List.of(new Move("add", 50, 50)));
		assertFalse("no bare \\n", after.replace("\r\n", "").contains("\n"));
		assertPosition(nodes(load("TwoCounters.dia", after), "Acc").get("add"), 50, 50);
	}

	@Test
	public void literalsAndUnknownNodesCannotBeMoved() throws Exception {
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		for (String id : List.of("#literal:acc1.in:in", "nosuch")) {
			try {
				LayoutEdits.moveNodes(file, "Main", List.of(new Move(id, 1, 1)));
				fail("expected IllegalArgumentException for " + id);
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage().contains(id));
			}
		}
	}

	@Test
	public void negativePositionsAreRefused() throws Exception {
		// Regression: "(-30, -10, ...)" does not parse (annotation numbers are
		// unsigned) and used to crash the parser's error recovery.
		LoadedFile file = load("TwoCounters.dia", source("TwoCounters.dia"));
		for (Move m : List.of(new Move("add", -30, 10), new Move("add", 10, -1))) {
			try {
				LayoutEdits.moveNodes(file, "Acc", List.of(m));
				fail("expected IllegalArgumentException for " + m);
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains("must not be negative"));
			}
		}
		assertPosition(nodes(load("TwoCounters.dia", LayoutEdits.moveNodes(file, "Acc",
				List.of(new Move("add", 0, 0)))), "Acc").get("add"), 0, 0);
	}

	@Test
	public void readOnlyReasonIsGivenForFilesWithSyntaxErrors() throws Exception {
		assertEquals(null, DiagramBuilder.build(load("PLib.dia", source("PLib.dia")), "P").readOnly());
		// BloqqiMovie.dia only parses through error recovery (Examples.EXCLUDED).
		Diagram movie = DiagramBuilder.build(load("BloqqiMovie.dia", source("BloqqiMovie.dia")), null);
		assertTrue(movie.readOnly(), movie.readOnly().contains("syntax errors"));
	}

	@Test
	public void filesWithSyntaxErrorsAreNotEdited() {
		LoadedFile file = load("x.dia", "diagramtype A { a: Add; connect(a.out, b) }");
		try {
			LayoutEdits.moveNodes(file, "A", List.of(new Move("a", 1, 1)));
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("syntax errors"));
		}
	}

	@Test
	public void movesInEveryExampleReparse() throws Exception {
		for (String f : Examples.workspace().files()) {
			if (Examples.EXCLUDED.contains(f)) {
				continue;
			}
			LoadedFile file = load(f, source(f));
			for (String type : DiagramBuilder.diagramTypeNames(file.compilationUnit())) {
				for (DiagramNode n : DiagramBuilder.build(file, type).nodes()) {
					if (n.kind().equals(Diagram.LITERAL)) {
						continue;
					}
					String after = LayoutEdits.moveNodes(file, type, List.of(new Move(n.id(), n.x() + 13, n.y() + 17)));
					LoadedFile reparsed = load(f, after);
					assertCleanParse(reparsed);
					DiagramNode m = nodes(reparsed, type).get(n.id());
					assertEquals(f + " " + type + " " + n.id(), List.of(n.x() + 13, n.y() + 17, n.width(), n.height()),
							List.of(m.x(), m.y(), m.width(), m.height()));
				}
			}
		}
	}
}
