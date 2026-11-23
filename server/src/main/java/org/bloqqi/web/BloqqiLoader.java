package org.bloqqi.web;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.bloqqi.compiler.ast.BloqqiParser;
import org.bloqqi.compiler.ast.BloqqiScanner;
import org.bloqqi.compiler.ast.CompilationUnit;
import org.bloqqi.compiler.ast.ErrorMessage;
import org.bloqqi.compiler.ast.Program;

import beaver.Parser;
import beaver.Scanner;
import beaver.Symbol;

/**
 * Parses Bloqqi source text into a {@link Program} the same way the old editor
 * did: the standard library plus a single compilation unit.
 */
public final class BloqqiLoader {
	/**
	 * Guards all use of the compiler. The JastAdd-generated AST keeps its attribute
	 * evaluation state in a static field ({@code ASTNode.state}), so evaluating
	 * attributes on two ASTs at the same time from different threads corrupts it,
	 * even if the ASTs are unrelated. Hold this lock while parsing or evaluating.
	 */
	public static final Object COMPILER_LOCK = new Object();

	private BloqqiLoader() {
	}

	public static LoadedFile load(String fileName, String source) {
		List<Problem> syntaxErrors = new ArrayList<>();
		CompilationUnit cu;
		try {
			cu = (CompilationUnit) new RecordingParser(syntaxErrors)
					.parse(new BloqqiScanner(new StringReader(source)));
		} catch (Parser.Exception | BloqqiScanner.ScannerError | IOException e) {
			syntaxErrors.add(new Problem(0, 0, String.valueOf(e.getMessage())));
			return new LoadedFile(fileName, source, null, List.copyOf(syntaxErrors), List.of());
		} catch (RuntimeException e) {
			// Beaver's error recovery can itself crash (e.g. an
			// ArrayIndexOutOfBoundsException in TokenStream.insert for
			// "annotation(a = (-1, 0, 1, 1))"); report it as a syntax error.
			syntaxErrors.add(new Problem(0, 0, "The parser failed while recovering from a syntax error: " + e));
			return new LoadedFile(fileName, source, null, List.copyOf(syntaxErrors), List.of());
		}

		Program program = new Program();
		try {
			program.setStandardLibrary(Program.loadStandardLibrary());
		} catch (Exception e) {
			throw new IllegalStateException("Could not load the Bloqqi standard library", e);
		}
		program.addCompilationUnit(cu);

		List<Problem> semanticErrors = new ArrayList<>();
		for (ErrorMessage e : new TreeSet<>(cu.errors())) {
			semanticErrors.add(new Problem(e.getLine(), e.getCol(), e.getMessage()));
		}
		return new LoadedFile(fileName, source, program, List.copyOf(syntaxErrors), List.copyOf(semanticErrors));
	}

	/**
	 * Beaver reports errors it recovers from to stderr by default. This parser
	 * records them instead, so a recovered parse is not silently accepted.
	 */
	private static final class RecordingParser extends BloqqiParser {
		RecordingParser(List<Problem> problems) {
			report = new Events() {
				@Override
				public void scannerError(Scanner.Exception e) {
					problems.add(new Problem(e.line, e.column, "Scanner error: " + e.getMessage()));
				}

				@Override
				public void syntaxError(Symbol token) {
					problems.add(at(token, "Syntax error: unexpected " + describe(token)));
				}

				@Override
				public void unexpectedTokenRemoved(Symbol token) {
					problems.add(at(token, "Recovered by removing unexpected " + describe(token)));
				}

				@Override
				public void missingTokenInserted(Symbol token) {
					problems.add(at(token, "Recovered by inserting missing " + describe(token)));
				}

				@Override
				public void misspelledTokenReplaced(Symbol token) {
					problems.add(at(token, "Recovered by replacing misspelled " + describe(token)));
				}

				@Override
				public void errorPhraseRemoved(Symbol error) {
					problems.add(at(error, "Recovered by removing an erroneous phrase"));
				}
			};
		}

		private static Problem at(Symbol token, String message) {
			return new Problem(Symbol.getLine(token.getStart()), Symbol.getColumn(token.getStart()), message);
		}

		private static String describe(Symbol token) {
			String[] names = BloqqiParser.Terminals.NAMES;
			String name = token.getId() >= 0 && token.getId() < names.length
					? names[token.getId()]
					: "token";
			return token.value != null ? name + " \"" + token.value + "\"" : name;
		}
	}
}
