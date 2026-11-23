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
	public void filesCanBeCreatedRenamedAndDeleted() throws Exception {
		Path dir = temp.newFolder().toPath();
		Workspace ws = new Workspace(dir);
		ws.create("A.dia", "diagramtype A {\n}\n");
		assertEquals(List.of("A.dia"), ws.files());
		assertEquals("diagramtype A {\n}\n", Files.readString(dir.resolve("A.dia")));
		try (var stream = Files.list(dir)) {
			assertEquals("no temporary files left", 1, stream.count());
		}

		ws.rename("A.dia", "My file-2.dia");
		assertEquals(List.of("My file-2.dia"), ws.files());
		ws.delete("My file-2.dia");
		assertEquals(List.of(), ws.files());
	}

	@Test
	public void newFileNamesAreChecked() throws Exception {
		Path dir = temp.newFolder().toPath();
		Workspace ws = new Workspace(dir);
		ws.create("A.dia", "");
		for (String bad : List.of("A.dia", "B", "B.txt", ".B.dia", "../B.dia", "x/B.dia", "B?.dia", "")) {
			try {
				ws.create(bad, "");
				fail("expected rejection of " + bad);
			} catch (IllegalArgumentException expected) {
				// ok
			}
		}
		try {
			ws.rename("A.dia", "A.dia");
			fail("expected rejection");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("already a file called A.dia"));
		}
	}

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
