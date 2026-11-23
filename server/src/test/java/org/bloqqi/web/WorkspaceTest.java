package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WorkspaceTest {
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void replaceOnlyWritesIfTheFileIsUnchanged() throws Exception {
		Path dir = temp.newFolder().toPath();
		Files.writeString(dir.resolve("A.dia"), "one");
		Workspace ws = new Workspace(dir);

		assertEquals("one", ws.replace("A.dia", "one", "two"));
		assertEquals("two", Files.readString(dir.resolve("A.dia")));

		try {
			ws.replace("A.dia", "one", "three");
			fail("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("has been changed since"));
		}
		assertEquals("two", Files.readString(dir.resolve("A.dia")));
	}

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
