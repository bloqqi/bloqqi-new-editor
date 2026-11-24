package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.bloqqi.web.Diagram.DiagramEdge;
import org.bloqqi.web.DiagramBuilder.Endpoint;
import org.junit.Test;

/** Intercepting inherited connections (docs/editor-spec.md §3.7). */
public class InterceptEditsTest {

	private static String source(String fileName) throws Exception {
		return Files.readString(Examples.dir().resolve(fileName), StandardCharsets.UTF_8);
	}

	private static LoadedFile load(String text) {
		return BloqqiLoader.load("PLib.dia", text);
	}

	private static Set<String> edgeIds(LoadedFile file, String type) {
		return DiagramBuilder.build(file, type).edges().stream().map(DiagramEdge::id).collect(Collectors.toSet());
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

	private static final Endpoint SEL_IN2 = new Endpoint("SelReal_1", "in:in2");
	private static final Endpoint SEL_OUT = new Endpoint("SelReal_1", "out:out");
	/** P's connection PPart.out -> Out, as seen in its subtype PTrack. */
	private static final String P_OUT = "P::PPart.out:out->P::Out.in";

	/** PLib.dia without PTrack's "intercept Out with SelReal_1.in2, SelReal_1.out;". */
	private static LoadedFile withoutInterception() throws Exception {
		LoadedFile file = load(source("PLib.dia"));
		String text = DeleteEdits.deleteConnections(file, "PTrack", List.of("P::PPart.out:out->SelReal_1.in:in2"));
		LoadedFile without = load(text);
		assertTrue("the inherited connection is back", edgeIds(without, "PTrack").contains(P_OUT));
		return without;
	}

	@Test
	public void interceptingAConnectionPutsABlockIntoIt() throws Exception {
		LoadedFile original = load(source("PLib.dia"));
		DiagramEdge inherited = DiagramBuilder.build(withoutInterception(), "PTrack").edges().stream()
				.filter(e -> e.id().equals(P_OUT)).findFirst().orElseThrow();
		assertTrue(inherited.interceptable());

		String after = ConnectionEdits.intercept(withoutInterception(), "PTrack", P_OUT, false, SEL_IN2, SEL_OUT);

		assertTrue(after.contains("  connect(TrackValue, SelReal_1.in1);\n  intercept Out with SelReal_1.in2, SelReal_1.out;\n}"));
		LoadedFile reparsed = load(after);
		assertCleanParse(reparsed);
		assertEquals("the same connections as PLib.dia's own interception",
				edgeIds(original, "PTrack"), edgeIds(reparsed, "PTrack"));
	}

	@Test
	public void interceptingAtTheSource() throws Exception {
		String after = ConnectionEdits.intercept(withoutInterception(), "PTrack", P_OUT, true, SEL_IN2, SEL_OUT);
		assertTrue(after.contains("  intercept source PPart.out with SelReal_1.in2, SelReal_1.out;\n}"));
		LoadedFile reparsed = load(after);
		assertCleanParse(reparsed);
		Set<String> edges = edgeIds(reparsed, "PTrack");
		assertTrue(edges.toString(), edges.contains("P::PPart.out:out->SelReal_1.in:in2"));
		assertTrue(edges.toString(), edges.contains("SelReal_1.out:out->P::Out.in"));
	}

	@Test
	public void onlyInheritedConnectionsCanBeIntercepted() throws Exception {
		LoadedFile file = withoutInterception();
		assertRefused("Only inherited connections",
				() -> ConnectionEdits.intercept(file, "PTrack", "Track.out->SelReal_1.in:sel", false, SEL_IN2, SEL_OUT));
		assertTrue(DiagramBuilder.build(file, "PTrack").edges().stream()
				.filter(e -> e.id().equals("Track.out->SelReal_1.in:sel")).noneMatch(DiagramEdge::interceptable));
		assertRefused("No connection", () -> ConnectionEdits.intercept(file, "PTrack", "nosuch", false, SEL_IN2, SEL_OUT));
	}

	@Test
	public void theInputMustBeFreeAndTheTypesMustFit() throws Exception {
		LoadedFile file = withoutInterception();
		// sel is taken by Track.
		Endpoint sel = new Endpoint("SelReal_1", "in:sel");
		assertRefused("already has an incoming connection",
				() -> ConnectionEdits.intercept(file, "PTrack", P_OUT, false, sel, SEL_OUT));
		// Freed, sel is a Bool and PPart.out a Real.
		LoadedFile freed = load(DeleteEdits.deleteConnections(file, "PTrack", List.of("Track.out->SelReal_1.in:sel")));
		assertRefused("Not allowed", () -> ConnectionEdits.intercept(freed, "PTrack", P_OUT, false, sel, SEL_OUT));
		assertRefused("cannot come out of",
				() -> ConnectionEdits.intercept(file, "PTrack", P_OUT, false, SEL_IN2, SEL_IN2));
	}

	@Test
	public void anotherInheritedConnectionCanBeInterceptedToo() throws Exception {
		// PTrack already intercepts Out; with a new block, also intercept e.out -> PPart.in1.
		// (Once intercepted, a connection is replaced by the two halves, so it
		// cannot be intercepted twice from the diagram.)
		LoadedFile withBlock = load(NodeEdits.addBlock(load(source("PLib.dia")), "PTrack", "RAdd", 300, 150));
		String after = ConnectionEdits.intercept(withBlock, "PTrack", "P::e.out:out->P::PPart.in:in1", false,
				new Endpoint("RAdd_1", "in:in1"), new Endpoint("RAdd_1", "out:out"));
		assertTrue(after.contains("intercept PPart.in1 with RAdd_1.in1, RAdd_1.out;"));
		LoadedFile reparsed = load(after);
		assertCleanParse(reparsed);
		Set<String> edges = edgeIds(reparsed, "PTrack");
		assertTrue(edges.toString(), edges.contains("P::e.out:out->RAdd_1.in:in1"));
		assertTrue(edges.toString(), edges.contains("RAdd_1.out:out->P::PPart.in:in1"));
		assertTrue("still intercepted", edges.contains("SelReal_1.out:out->P::Out.in"));
	}
}
