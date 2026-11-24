package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

/** Feature selection (docs/editor-spec.md §4.1). PLib.dia declares {@code features P}. */
public class FeatureEditsTest {

	private static String plib() throws Exception {
		return Files.readString(Examples.dir().resolve("PLib.dia"), StandardCharsets.UTF_8);
	}

	private static LoadedFile load(String text) {
		return BloqqiLoader.load("PLib.dia", text);
	}

	private static FeatureEdits.Features features(LoadedFile file, String type, String block) {
		synchronized (BloqqiLoader.COMPILER_LOCK) {
			return FeatureEdits.features(file, type, block);
		}
	}

	private static String set(LoadedFile file, String type, String block, String... features) {
		synchronized (BloqqiLoader.COMPILER_LOCK) {
			return FeatureEdits.setFeatures(file, type, block, List.of(features));
		}
	}

	private static void assertClean(String text) {
		LoadedFile f = load(text);
		assertEquals("syntax errors", List.of(), f.syntaxErrors());
		assertEquals("semantic errors", List.of(), f.semanticErrors());
	}

	private static void assertRefused(String message, Runnable edit) {
		try {
			edit.run();
			fail("expected a refusal: " + message);
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains(message));
		}
	}

	private static String mainOf(String text) {
		return text.substring(text.indexOf("diagramtype Main {"));
	}

	@Test
	public void theFeaturesOfABlock() throws Exception {
		FeatureEdits.Features f = features(load(plib()), "Main", "pid");
		assertEquals("P", f.base());
		assertEquals(List.of(
				new FeatureEdits.Option("Derivative", "PD", true, List.of(), List.of("Integral", "PTrack")),
				new FeatureEdits.Option("Integral", "PI", true, List.of("PTrack"), List.of()),
				new FeatureEdits.Option("PTrack", "PTrack", false, List.of("Integral"), List.of())),
				f.options());
	}

	@Test
	public void selectingWritesTheFeaturesInTheirOrder() throws Exception {
		LoadedFile file = load(plib());
		String one = set(file, "Main", "pid", "Derivative");
		assertTrue(mainOf(one), mainOf(one).startsWith("diagramtype Main {\n  pid: P {\n    feature Derivative;\n  };\n}"));
		assertClean(one);
		// Given in any order, written in the order of the before rules.
		String two = set(load(one), "Main", "pid", "PTrack", "Derivative");
		assertTrue(mainOf(two), mainOf(two).startsWith(
				"diagramtype Main {\n  pid: P {\n    feature Derivative;\n    feature PTrack;\n  };\n}"));
		assertClean(two);
		// None: a plain block again.
		String none = set(load(two), "Main", "pid");
		assertTrue(mainOf(none), mainOf(none).startsWith("diagramtype Main {\n  pid: P;\n}"));
		assertClean(none);
		// And back.
		String again = set(load(none), "Main", "pid", "Integral", "Derivative");
		assertEquals(plib(), again);
	}

	@Test
	public void aPlainOrSimpleNamedBlockGetsFeatures() throws Exception {
		String text = plib() + "diagramtype M2 {\n  P_1;\n  q: P;\n}\n";
		String simple = set(load(text), "M2", "P_1", "Integral");
		assertTrue(simple, simple.contains("diagramtype M2 {\n  P_1: P {\n    feature Integral;\n  };\n  q: P;\n}"));
		assertClean(simple);
		String named = set(load(simple), "M2", "q", "PTrack");
		assertTrue(named, named.contains("  q: P {\n    feature PTrack;\n  };\n}"));
		assertClean(named);
		assertEquals("nothing to do", text, set(load(text), "M2", "q"));
	}

	@Test
	public void featuresThatExcludeEachOtherAreRefused() throws Exception {
		LoadedFile file = load(plib());
		assertRefused("cannot be used together with", () -> set(file, "Main", "pid", "Integral", "PTrack"));
		assertRefused("has no feature Nope", () -> set(file, "Main", "pid", "Nope"));
		assertRefused("The type of SelReal_1 has no features", () -> set(file, "PTrack", "SelReal_1"));
		assertRefused("P::PPart is not declared in PTrack", () -> set(file, "PTrack", "P::PPart"));
		assertRefused("not declared in PTrack", () -> set(file, "PTrack", "P::e"));
	}

	@Test
	public void otherContentsOfTheBracesAreKept() throws Exception {
		String text = plib().replace("    feature Integral;\n  };", "    feature Integral;\n    var v: Int;\n  };");
		assertClean(text);
		String changed = set(load(text), "Main", "pid", "PTrack");
		assertTrue(mainOf(changed), mainOf(changed).startsWith(
				"diagramtype Main {\n  pid: P {\n    feature PTrack;\n    var v: Int;\n  };\n}"));
		assertClean(changed);
		String none = set(load(changed), "Main", "pid");
		assertTrue(mainOf(none), mainOf(none).startsWith("diagramtype Main {\n  pid: P {\n    var v: Int;\n  };\n}"));
		assertClean(none);
	}

	/** PLib.dia with a stored position for Main's pid: 8 in-ports, height 14 * 8 + 2. */
	private static String withLayout() throws Exception {
		String text = plib().replace("    feature Integral;\n  };\n}\n", "    feature Integral;\n  };\n}\nannotation(\n  pid = (20, 30, 60, 114)\n)\n");
		assertTrue(text.contains("pid = (20, 30, 60, 114)"));
		return text;
	}

	@Test
	public void theStoredHeightFitsThePortsAfterwards() throws Exception {
		// Derivative only: SP, PV, K (P) and Td, N (PD): 5 ports.
		String fewer = set(load(withLayout()), "Main", "pid", "Derivative");
		assertTrue(fewer, fewer.contains("pid = (20, 30, 60, 72)"));
		assertClean(fewer);
		// And PTrack: Track, TrackValue: 7 ports.
		String more = set(load(fewer), "Main", "pid", "Derivative", "PTrack");
		assertTrue(more, more.contains("pid = (20, 30, 60, 100)"));
		assertClean(more);
	}

	@Test
	public void aBlockIsNeverDrawnShorterThanItsPortsNeed() throws Exception {
		String text = withLayout().replace("pid = (20, 30, 60, 114)", "pid = (20, 30, 60, 20)");
		Diagram d;
		synchronized (BloqqiLoader.COMPILER_LOCK) {
			d = DiagramBuilder.build(load(text), "Main");
		}
		Diagram.DiagramNode pid = d.nodes().stream().filter(n -> n.id().equals("pid")).findFirst().orElseThrow();
		assertEquals(114, pid.height());
		assertEquals(List.of(20, 30, 60), List.of(pid.x(), pid.y(), pid.width()));
	}

	@Test
	public void connectionsOnPortsOfDeselectedFeaturesAreDeleted() throws Exception {
		// Integral (PI) adds the in-port Ti, Derivative (PD) Td and N.
		String text = plib().replace("    feature Integral;\n  };\n}", "    feature Integral;\n  };\n  connect(2.0, pid.Ti);\n  connect(0.1, pid.Td);\n}");
		assertClean(text);
		String changed = set(load(text), "Main", "pid", "Derivative");
		assertFalse(changed, changed.contains("pid.Ti"));
		assertTrue(changed, changed.contains("connect(0.1, pid.Td);"));
		assertClean(changed);
	}
}
