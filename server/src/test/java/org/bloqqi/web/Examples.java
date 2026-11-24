package org.bloqqi.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Access to the compiler's example files used by the tests. */
final class Examples {
	/**
	 * Examples that do not load cleanly with this compiler version and are
	 * excluded from the "loads without errors" test for now:
	 * <ul>
	 * <li>Example-Pregulator.dia: obsolete syntax ({@code variable Real x;}), does not parse</li>
	 * <li>Example-Motor.dia: same obsolete syntax, parses with 20 errors</li>
	 * <li>P_PFF_PGS_Regulator.dia: 3 errors (Mux3 declared twice, Mux3_1.k not found), and
	 *     old annotation keys {@code P:PV} that the parser only accepts through error recovery</li>
	 * <li>BloqqiMovie.dia: old annotation keys {@code Gain:Out} (now {@code Gain::Out});
	 *     the parser only accepts them through error recovery</li>
	 * <li>SimpleAddDiagram.dia: starts with {@code @Application}; error recovery
	 *     drops the whole file (it parses as empty)</li>
	 * </ul>
	 */
	static final Set<String> EXCLUDED = Set.of(
			"Example-Pregulator.dia",
			"Example-Motor.dia",
			"P_PFF_PGS_Regulator.dia",
			"BloqqiMovie.dia",
			"SimpleAddDiagram.dia");

	private Examples() {
	}

	/** The examples directory; set by the Ant build through -Dbloqqi.examples. */
	static Path dir() {
		String dir = System.getProperty("bloqqi.examples",
				"bloqqi-compiler/examples");
		return Path.of(dir);
	}

	static Workspace workspace() {
		return new Workspace(dir());
	}

	static LoadedFile load(String fileName) throws IOException {
		return workspace().load(fileName);
	}
}
