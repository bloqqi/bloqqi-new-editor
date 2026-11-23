package org.bloqqi.web;

import java.util.List;

import org.bloqqi.compiler.ast.CompilationUnit;
import org.bloqqi.compiler.ast.Program;

/**
 * The result of loading one Bloqqi file.
 *
 * @param fileName       name of the file relative to the workspace
 * @param source         the file's text, as parsed
 * @param program        standard library + this file, or null if the file could not be parsed
 * @param syntaxErrors   syntax errors, including ones the parser recovered from
 * @param semanticErrors errors from {@code CompilationUnit.errors()} (empty if not parsed)
 */
public record LoadedFile(
		String fileName,
		String source,
		Program program,
		List<Problem> syntaxErrors,
		List<Problem> semanticErrors) {

	public boolean isParsed() {
		return program != null;
	}

	/** The compilation unit of the file itself (the standard library is separate). */
	public CompilationUnit compilationUnit() {
		return program == null ? null : program.getCompilationUnit(0);
	}

	public boolean hasErrors() {
		return !syntaxErrors.isEmpty() || !semanticErrors.isEmpty();
	}
}
