package org.bloqqi.web;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.bloqqi.compiler.ast.CodeGenerationTarget;
import org.bloqqi.compiler.ast.CodeTargetMinimal;
import org.bloqqi.compiler.ast.CodeTargetWithDriver;
import org.bloqqi.compiler.ast.CompilationUnit;
import org.bloqqi.compiler.ast.ErrorMessage;
import org.bloqqi.compiler.ast.Program;

/**
 * The C code the Bloqqi compiler generates for a file, for the editor's C view.
 * It behaves like the compiler's command line ({@code org.bloqqi.compiler.Compiler}):
 * code is generated for the diagram type {@code Main}, which must exist and
 * have no parameters, and only if the file has no errors, including the errors
 * that only matter for code generation (e.g. an in-port without a connection).
 */
final class CCode {

	/** {@code --c=minimal}: the program only. */
	static final String MINIMAL = "minimal";
	/** {@code --c} (= {@code --c=with-driver}): with includes, helpers and a {@code main()} loop. */
	static final String WITH_DRIVER = "with-driver";
	static final List<String> VARIANTS = List.of(MINIMAL, WITH_DRIVER);

	/**
	 * The generated code, or null if the compiler would refuse to generate it,
	 * and then why.
	 */
	record Result(String code, List<Problem> problems) {
	}

	private CCode() {
	}

	/** Generates C; the caller holds {@link BloqqiLoader#COMPILER_LOCK}. */
	static Result generate(LoadedFile file, String variant) {
		if (!VARIANTS.contains(variant)) {
			throw new IllegalArgumentException("Unknown C variant " + variant + "; one of " + VARIANTS);
		}
		if (!file.isParsed() || !file.syntaxErrors().isEmpty() || !file.semanticErrors().isEmpty()) {
			List<Problem> problems = new ArrayList<>(file.syntaxErrors());
			problems.addAll(file.semanticErrors());
			if (problems.isEmpty()) {
				problems.add(new Problem(0, 0, "The file could not be parsed"));
			}
			return new Result(null, problems);
		}
		Program program = file.program();
		TreeSet<ErrorMessage> errors = new TreeSet<>(program.mainDiagramErrors());
		for (CompilationUnit cu : program.getCompilationUnits()) {
			errors.addAll(cu.codeGenerationErrors());
		}
		if (!errors.isEmpty()) {
			List<Problem> problems = new ArrayList<>();
			for (ErrorMessage e : errors) {
				problems.add(new Problem(e.getLine(), e.getCol(), e.getMessage()));
			}
			return new Result(null, problems);
		}
		CodeGenerationTarget target = variant.equals(MINIMAL)
				? new CodeTargetMinimal(program)
				: new CodeTargetWithDriver(program);
		return new Result(program.generateC(target), List.of());
	}
}
