package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.TypeDecl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Every file in the compiler's examples/ directory loads without syntax or
 * semantic errors, and a diagram can be built for each of its diagram types:
 * every parameter, block and variable is a node, every connection an edge
 * between handles that exist.
 * Files in {@link Examples#EXCLUDED} are reported as skipped.
 */
@RunWith(Parameterized.class)
public class ExamplesLoadTest {
	@Parameters(name = "{0}")
	public static Collection<Object[]> files() throws Exception {
		List<Object[]> params = new ArrayList<>();
		for (String f : Examples.workspace().files()) {
			params.add(new Object[] { f });
		}
		return params;
	}

	private final String fileName;

	public ExamplesLoadTest(String fileName) {
		this.fileName = fileName;
	}

	@Test
	public void loadsWithoutErrors() throws Exception {
		assumeFalse(fileName + " is excluded (see Examples.EXCLUDED)", Examples.EXCLUDED.contains(fileName));

		LoadedFile file = Examples.load(fileName);
		assertTrue("parsed", file.isParsed());
		assertEquals("syntax errors", List.of(), file.syntaxErrors());
		assertEquals("semantic errors", List.of(), file.semanticErrors());
	}

	@Test
	public void buildsDiagramForEveryDiagramType() throws Exception {
		assumeFalse(fileName + " is excluded (see Examples.EXCLUDED)", Examples.EXCLUDED.contains(fileName));

		LoadedFile file = Examples.load(fileName);
		List<String> names = DiagramBuilder.diagramTypeNames(file.compilationUnit());
		assertFalse("has diagram types", names.isEmpty());
		for (TypeDecl td : file.compilationUnit().typeDecls()) {
			if (!td.isDiagramType()) {
				continue;
			}
			DiagramType dt = (DiagramType) td;
			Diagram d = DiagramBuilder.build(file, dt.name());
			assertEquals(dt.name(), d.diagramType());
			int expectedNodes = dt.getNumInParameter() + dt.getNumOutParameter()
					+ dt.getNumBlock() + dt.getNumVariable();
			long literals = d.nodes().stream().filter(n -> n.kind().equals(Diagram.LITERAL)).count();
			assertEquals("one node per parameter, block and variable in " + dt.name(),
					expectedNodes, d.nodes().size() - literals);
			assertEquals("one edge per connection in " + dt.name(), dt.getNumConnection(), d.edges().size());

			Map<String, Set<String>> handles = new HashMap<>();
			for (Diagram.DiagramNode n : d.nodes()) {
				assertTrue("positive size for " + n.id(), n.width() > 0 && n.height() > 0);
				assertTrue("unique id " + n.id(), handles.put(n.id(), handles(n)) == null);
			}
			Set<String> edgeIds = new HashSet<>();
			for (Diagram.DiagramEdge e : d.edges()) {
				assertTrue("unique edge id " + e.id(), edgeIds.add(e.id()));
				assertTrue(e.id() + ": source handle exists",
						handles.getOrDefault(e.source(), Set.of()).contains(e.sourceHandle()));
				assertTrue(e.id() + ": target handle exists",
						handles.getOrDefault(e.target(), Set.of()).contains(e.targetHandle()));
			}
		}
	}

	/** The handles the client renders for a node (see client/src/nodes.tsx). */
	private static Set<String> handles(Diagram.DiagramNode n) {
		Set<String> h = new HashSet<>();
		if (n.kind().equals(Diagram.BLOCK)) {
			n.inPorts().forEach(p -> h.add(Diagram.inPortHandle(p.name())));
			n.outPorts().forEach(p -> h.add(Diagram.outPortHandle(p.name())));
		} else {
			h.add(Diagram.IN);
			h.add(Diagram.OUT);
		}
		return h;
	}
}
