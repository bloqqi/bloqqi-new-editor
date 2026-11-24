package org.bloqqi.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The C view: the code is what the compiler's command line generates. */
public class CCodeTest {
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	private static LoadedFile example(String name) throws Exception {
		return BloqqiLoader.load(name, Files.readString(Examples.dir().resolve(name), StandardCharsets.UTF_8));
	}

	private static CCode.Result generate(LoadedFile file, String variant) {
		synchronized (BloqqiLoader.COMPILER_LOCK) {
			return CCode.generate(file, variant);
		}
	}

	/** What {@code bloqqi-compiler <file> --c=<variant>} prints. */
	private static String commandLine(String file, String variant) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream old = System.out;
		synchronized (BloqqiLoader.COMPILER_LOCK) {
			System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
			try {
				new org.bloqqi.compiler.Compiler(
						new String[] { Examples.dir().resolve(file).toString(), "--c=" + variant });
			} finally {
				System.setOut(old);
			}
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	@Test
	public void theCodeIsWhatTheCompilerGenerates() throws Exception {
		for (String variant : CCode.VARIANTS) {
			CCode.Result result = generate(example("TwoCounters.dia"), variant);
			assertEquals(List.of(), result.problems());
			// (The command line prints the code with println.)
			assertEquals(variant, commandLine("TwoCounters.dia", variant), result.code() + System.lineSeparator());
		}
		String minimal = generate(example("TwoCounters.dia"), CCode.MINIMAL).code();
		assertTrue(minimal.contains("void bloqqi_main(Main_VARS *vars)"));
		assertFalse("no driver", minimal.contains("int main()"));
		assertTrue(generate(example("TwoCounters.dia"), CCode.WITH_DRIVER).code().contains("int main()"));
	}

	@Test
	public void theMinimalCodeCompiles() throws Exception {
		Assume.assumeTrue("gcc is installed", Files.isExecutable(Path.of("/usr/bin/gcc")));
		Path c = temp.newFile("TwoCounters.c").toPath();
		Files.writeString(c, generate(example("TwoCounters.dia"), CCode.MINIMAL).code());
		Process gcc = new ProcessBuilder("gcc", "-std=c99", "-c", c.toString(), "-o",
				temp.getRoot().toPath().resolve("TwoCounters.o").toString())
				.redirectErrorStream(true).start();
		assertTrue(gcc.waitFor(60, TimeUnit.SECONDS));
		assertEquals(new String(gcc.getInputStream().readAllBytes()), 0, gcc.exitValue());
	}

	@Test
	public void noCodeWhereTheCompilerWouldRefuse() throws Exception {
		// No diagram type Main.
		CCode.Result noMain = generate(example("Tank.dia"), CCode.MINIMAL);
		assertNull(noMain.code());
		assertTrue(noMain.problems().toString(), noMain.problems().toString().contains("A diagram type Main is missing"));
		// An in-port without a connection (only an error when generating code).
		CCode.Result missing = generate(example("PLib.dia"), CCode.MINIMAL);
		assertNull(missing.code());
		assertTrue(missing.problems().toString().contains("have no incoming connection: pid.SP"));
		// Main with parameters.
		CCode.Result withParameters = generate(BloqqiLoader.load("x.dia", "diagramtype Main(a: Int) {\n}\n"), CCode.MINIMAL);
		assertTrue(withParameters.problems().toString().contains("The diagram type Main cannot have parameters"));
		// Syntax errors.
		CCode.Result broken = generate(BloqqiLoader.load("x.dia", "diagramtype Main {\n  connect(\n}\n"), CCode.MINIMAL);
		assertNull(broken.code());
		assertFalse(broken.problems().isEmpty());
	}
}
