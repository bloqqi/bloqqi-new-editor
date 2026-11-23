package org.bloqqi.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

public class WorkspaceTest {
	@Test
	public void listsOnlyBloqqiFiles() throws Exception {
		List<String> files = Examples.workspace().files();
		assertTrue(files.contains("PLib.dia"));
		assertFalse(files.contains("PrintFunction.c"));
		assertTrue(files.stream().allMatch(f -> f.endsWith(".dia")));
	}

	@Test
	public void excludedExamplesExist() throws Exception {
		// Keeps the exclusion list honest: remove entries when files are fixed or removed.
		assertTrue(Examples.workspace().files().containsAll(Examples.EXCLUDED));
	}

	@Test
	public void rejectsPathsOutsideWorkspace() throws Exception {
		Workspace ws = Examples.workspace();
		for (String bad : List.of("../build.xml", "../examples/PLib.dia", "/etc/passwd", "PrintFunction.c",
				"Missing.dia", ".hidden.dia")) {
			try {
				ws.load(bad);
				fail("expected rejection of " + bad);
			} catch (IllegalArgumentException expected) {
				// ok
			}
		}
	}
}
