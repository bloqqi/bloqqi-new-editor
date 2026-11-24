package org.bloqqi.web;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.Anchor;
import org.bloqqi.compiler.ast.Annotation;
import org.bloqqi.compiler.ast.BloqqiParser;
import org.bloqqi.compiler.ast.BloqqiScanner;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.Port;
import org.bloqqi.compiler.ast.TypeDecl;
import org.bloqqi.compiler.ast.VarUse;

import beaver.Symbol;

/**
 * The text machinery behind every diagram edit: text edits and their
 * application, source positions (headers, bodies, statements, annotation
 * blocks), and the check that a new text adds no errors. Each edit returns the
 * new source text; it does not write anything.
 */
public final class SourceEdits {
	private SourceEdits() {
	}

	/** A replacement of the text in [start, end). */
	record TextEdit(int start, int end, String text) {
	}

	/** Applies non-overlapping edits to a text. */
	static String apply(String text, List<TextEdit> edits) {
		List<TextEdit> sorted = new ArrayList<>(edits);
		sorted.sort(Comparator.comparingInt(TextEdit::start).reversed());
		StringBuilder b = new StringBuilder(text);
		int limit = Integer.MAX_VALUE;
		for (TextEdit e : sorted) {
			if (e.end() > limit) {
				throw new IllegalStateException("Overlapping edits");
			}
			b.replace(e.start(), e.end(), e.text());
			limit = e.start();
		}
		return b.toString();
	}

	/** The type's annotation entries, keyed by their text without whitespace (e.g. "P::K"). */
	static TreeMap<String, Layout.Rect> annotations(SourceText source, DiagramType dt) {
		TreeMap<String, Layout.Rect> entries = new TreeMap<>();
		for (Annotation a : dt.getAnnotations()) {
			String key = source.text(a.getVarUse()).replaceAll("\\s+", "");
			entries.put(key, new Layout.Rect(a.getX(), a.getY(), a.getWidth(), a.getHeight()));
		}
		return entries;
	}

	/**
	 * The edit that makes the type's {@code annotation(...)} block hold exactly
	 * these entries, sorted by key as the old editor wrote them: the block is
	 * replaced, or inserted after the type's closing brace (before any
	 * {@code wiring}), or removed with its line(s) if there are no entries.
	 */
	static TextEdit annotationEdit(SourceText source, DiagramType dt, TreeMap<String, Layout.Rect> entries) {
		String nl = source.lineSeparator();
		StringJoiner block = new StringJoiner("," + nl, "annotation(" + nl, nl + ")");
		entries.forEach((key, r) -> block.add(
				"  " + key + " = (" + r.x() + ", " + r.y() + ", " + r.width() + ", " + r.height() + ")"));

		if (dt.getNumAnnotation() > 0) {
			// The annotation list spans from the "annotation" keyword to its ")".
			int start = source.start(dt.getAnnotationList());
			int end = source.end(dt.getAnnotationList());
			if (entries.isEmpty()) {
				int[] lines = statementLines(source, start, end);
				return new TextEdit(lines[0], lines[1], "");
			}
			return new TextEdit(start, end, block.toString());
		}
		if (entries.isEmpty()) {
			return new TextEdit(0, 0, "");
		}
		if (dt.hasLocalWiring()) {
			int at = source.start(dt.getLocalWiring());
			return new TextEdit(at, at, block + nl);
		}
		int at = source.end(dt);
		return new TextEdit(at, at, nl + block);
	}

	/** The written (positioned) children of a list. */
	static List<ASTNode<?>> positioned(ASTNode<?> list) {
		List<ASTNode<?>> result = new ArrayList<>();
		for (int i = 0; i < list.getNumChild(); i++) {
			ASTNode<?> child = list.getChild(i);
			if (SourceText.hasPosition(child)) {
				result.add(child);
			}
		}
		return result;
	}

	/** Statements of a type's body, in the grammar's order: variables, blocks, connections. */
	static List<ASTNode<?>> bodyStatements(DiagramType dt) {
		List<ASTNode<?>> statements = new ArrayList<>();
		dt.getLocalVariableList().forEach(statements::add);
		dt.getLocalBlockList().forEach(statements::add);
		dt.getFlowDeclList().forEach(statements::add);
		return statements;
	}

	/**
	 * Where things are in a type's header {@code diagramtype Name(a: Int => b: Int)}:
	 * the offset after the name, of '(' and ')' (-1 if there are no parentheses),
	 * of {@code =>} (-1 if none), and whether parameters are written type first.
	 */
	record Header(int nameEnd, int lpar, int rpar, int arrow, boolean typeFirst) {
	}

	static Header header(SourceText source, DiagramType dt) {
		int from = source.start(dt);
		int open = bodyBraces(source, dt)[0];
		int nameEnd = -1;
		int lpar = -1;
		int rpar = -1;
		int arrow = -1;
		boolean afterKeyword = false;
		BloqqiScanner scanner = new BloqqiScanner(new StringReader(source.text()));
		try {
			for (Symbol token = scanner.nextToken(); token.getId() != BloqqiParser.Terminals.EOF;
					token = scanner.nextToken()) {
				int start = source.offset(Symbol.getLine(token.getStart()), Symbol.getColumn(token.getStart()));
				if (start < from) {
					continue;
				}
				if (start >= open) {
					break;
				}
				int end = source.offset(Symbol.getLine(token.getEnd()), Symbol.getColumn(token.getEnd())) + 1;
				short id = token.getId();
				if (id == BloqqiParser.Terminals.DIAGRAMTYPE) {
					afterKeyword = true;
				} else if (afterKeyword && nameEnd < 0 && id == BloqqiParser.Terminals.ID) {
					nameEnd = end;
				} else if (nameEnd >= 0 && lpar < 0 && rpar < 0 && id == BloqqiParser.Terminals.LPAR
						&& start == skipSpaces(source.text(), nameEnd)) {
					lpar = start;
				} else if (lpar >= 0 && rpar < 0 && id == BloqqiParser.Terminals.ARROW) {
					arrow = start;
				} else if (lpar >= 0 && rpar < 0 && id == BloqqiParser.Terminals.RPAR) {
					rpar = start;
				}
			}
		} catch (IOException | beaver.Scanner.Exception | BloqqiScanner.ScannerError e) {
			throw new IllegalStateException("Could not scan " + dt.name(), e);
		}
		if (nameEnd < 0) {
			throw new IllegalStateException("No header found for " + dt.name());
		}
		boolean typeFirst = false;
		for (List<ASTNode<?>> params : List.of(positioned(dt.getLocalInParameterList()),
				positioned(dt.getLocalOutParameterList()))) {
			for (ASTNode<?> p : params) {
				String typeName = ((org.bloqqi.compiler.ast.Parameter) p).getTypeUse().getID();
				String text = source.text(p);
				typeFirst = text.startsWith(typeName) && text.length() > typeName.length()
						&& Character.isWhitespace(text.charAt(typeName.length()));
			}
		}
		return new Header(nameEnd, lpar, rpar, arrow, typeFirst);
	}

	private static int skipSpaces(String text, int from) {
		int i = from;
		while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
			i++;
		}
		return i;
	}

	/**
	 * How a connection end is written: {@code Anchor.access()} (as in the old
	 * editor), but without the {@code Super::} qualification of an inherited block
	 * when its name alone is unambiguous ({@code e.out} rather than {@code P::e.out}).
	 */
	static String accessText(Anchor a) {
		VarUse use = a.access();
		if (use == null) {
			throw new IllegalArgumentException("Cannot refer to " + a + " here");
		}
		String text = use.toString();
		if (a instanceof Port p && p.block().isInherited() && !p.block().isNameAmbiguous()) {
			String qualifier = p.block().declaredInDiagramType().name() + ASTNode.DECLARED_IN_SEP;
			if (text.startsWith(qualifier)) {
				text = text.substring(qualifier.length());
			}
		}
		return text;
	}

	/**
	 * Inserts a statement into a diagram type's body, on its own line: after the
	 * last of {@code after} (with its indentation), or, if none of them is
	 * written, before the body's first statement, or into the empty body.
	 * Trailing comments on the line before stay where they are.
	 */
	static String insertStatement(SourceText source, DiagramType dt, String statement, List<ASTNode<?>> after) {
		String text = source.text();
		String nl = source.lineSeparator();
		int[] braces = bodyBraces(source, dt);
		int open = braces[0];
		int close = braces[1];

		// The end (after its ';') of the last of "after".
		int lastStart = -1;
		int lastEnd = -1;
		for (ASTNode<?> n : after) {
			if (SourceText.hasPosition(n)) {
				int end = semicolonAfter(text, source.end(n), close);
				if (end > lastEnd) {
					lastEnd = end;
					lastStart = source.start(n);
				}
			}
		}

		if (lastEnd < 0) {
			// Before the first statement of the body, if there is one.
			int first = Integer.MAX_VALUE;
			List<ASTNode<?>> statements = new ArrayList<>();
			dt.getLocalVariableList().forEach(statements::add);
			dt.getLocalBlockList().forEach(statements::add);
			dt.getFlowDeclList().forEach(statements::add);
			for (ASTNode<?> n : statements) {
				if (SourceText.hasPosition(n)) {
					first = Math.min(first, source.start(n));
				}
			}
			if (first != Integer.MAX_VALUE) {
				String indent = indentation(text, first);
				int lineStart = first - indent.length();
				if (text.substring(lineStart, first).isBlank()) {
					return text.substring(0, lineStart) + indent + statement + nl + text.substring(lineStart);
				}
				return text.substring(0, first) + statement + " " + text.substring(first);
			}
		}

		if (lastEnd < 0) {
			// Empty body: "{\n  statement\n}".
			boolean sameLine = text.substring(open, close).indexOf('\n') < 0
					&& text.substring(open, close).indexOf('\r') < 0;
			String indent = defaultIndent(text);
			return text.substring(0, open + 1) + nl + indent + statement + (sameLine ? nl : "")
					+ text.substring(open + 1);
		}
		int eol = endOfLine(text, lastEnd);
		if (close < eol) {
			// "{ a: Add; }": the body is on one line.
			return text.substring(0, close) + statement + " " + text.substring(close);
		}
		String indent = indentation(text, lastStart);
		return text.substring(0, eol) + nl + indent + statement + text.substring(eol);
	}

	/**
	 * Offsets of the '{' and '}' of a diagram type's body, from the scanner's
	 * tokens (so braces in comments and strings don't count).
	 */
	static int[] bodyBraces(SourceText source, DiagramType dt) {
		int from = source.start(dt);
		int open = -1;
		int depth = 0;
		BloqqiScanner scanner = new BloqqiScanner(new StringReader(source.text()));
		try {
			for (Symbol token = scanner.nextToken(); token.getId() != BloqqiParser.Terminals.EOF;
					token = scanner.nextToken()) {
				int offset = source.offset(Symbol.getLine(token.getStart()), Symbol.getColumn(token.getStart()));
				if (offset < from) {
					continue;
				}
				if (token.getId() == BloqqiParser.Terminals.LBRA) {
					if (depth++ == 0) {
						open = offset;
					}
				} else if (token.getId() == BloqqiParser.Terminals.RBRA && open >= 0 && --depth == 0) {
					return new int[] { open, offset };
				}
			}
		} catch (IOException | beaver.Scanner.Exception | BloqqiScanner.ScannerError e) {
			throw new IllegalStateException("Could not scan " + dt.name(), e);
		}
		throw new IllegalStateException("No body found for " + dt.name());
	}

	/** Offset just after the ';' that ends a statement whose node ends at {@code end}. */
	static int semicolonAfter(String text, int end, int limit) {
		if (end > 0 && text.charAt(end - 1) == ';') {
			return end;
		}
		int i = text.indexOf(';', end);
		return i >= 0 && i < limit ? i + 1 : end;
	}

	/**
	 * The range to delete for a statement at [start, end): its whole line(s) if
	 * nothing else is on them, otherwise just the statement and the spaces after it.
	 */
	static int[] statementLines(SourceText source, int start, int end) {
		String text = source.text();
		int lineStart = start;
		while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}
		int eol = endOfLine(text, end);
		boolean aloneBefore = text.substring(lineStart, start).isBlank();
		boolean aloneAfter = text.substring(end, eol).isBlank();
		if (aloneBefore && aloneAfter) {
			int next = eol;
			if (text.startsWith("\r\n", next)) {
				next += 2;
			} else if (next < text.length()) {
				next++;
			}
			return new int[] { lineStart, next };
		}
		int stop = end;
		while (stop < eol && (text.charAt(stop) == ' ' || text.charAt(stop) == '\t')) {
			stop++;
		}
		return new int[] { start, stop };
	}

	static int endOfLine(String text, int from) {
		int i = from;
		while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
			i++;
		}
		return i;
	}

	private static String indentation(String text, int offset) {
		int lineStart = offset;
		while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
			lineStart--;
		}
		int i = lineStart;
		while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
			i++;
		}
		return text.substring(lineStart, i);
	}

	/** Indentation for a statement in an empty body: a tab if the file indents with tabs. */
	private static String defaultIndent(String text) {
		for (String line : text.split("\\R")) {
			if (line.startsWith("\t")) {
				return "\t";
			}
			if (line.startsWith(" ")) {
				return "  ";
			}
		}
		return "  ";
	}

	/**
	 * Reparses the edited text and rejects the edit if it introduced syntax
	 * errors or new semantic errors: a failed edit must not damage the file.
	 *
	 * @return the reparsed file
	 */
	static LoadedFile check(LoadedFile before, String newText) {
		LoadedFile after = BloqqiLoader.load(before.fileName(), newText);
		if (!after.syntaxErrors().isEmpty()) {
			throw new IllegalStateException("The edit would introduce syntax errors: " + after.syntaxErrors());
		}
		if (after.semanticErrors().size() > before.semanticErrors().size()) {
			Set<String> old = new HashSet<>();
			before.semanticErrors().forEach(p -> old.add(p.message()));
			List<String> added = new ArrayList<>();
			after.semanticErrors().stream().map(Problem::message).filter(m -> !old.contains(m)).forEach(added::add);
			throw new IllegalArgumentException("Not allowed: it would give "
					+ (added.isEmpty() ? after.semanticErrors().toString() : String.join("; ", added)));
		}
		return after;
	}

	/**
	 * Why diagrams of the file cannot be edited, or null if they can: edits are
	 * located through AST source positions, which are unreliable after the parser
	 * has recovered from syntax errors.
	 */
	public static String readOnlyReason(LoadedFile file) {
		if (!file.isParsed() || !file.syntaxErrors().isEmpty()) {
			return file.fileName() + " has syntax errors; fix them in a text editor to edit the diagram";
		}
		return null;
	}

	static void requireEditable(LoadedFile file) {
		String reason = readOnlyReason(file);
		if (reason != null) {
			throw new IllegalArgumentException(reason);
		}
	}

	static DiagramType topLevelDiagramType(LoadedFile file, String name) {
		for (TypeDecl td : file.compilationUnit().typeDecls()) {
			if (td.isDiagramType() && td.name().equals(name)) {
				return (DiagramType) td;
			}
		}
		throw new IllegalArgumentException("No diagram type " + name + " in " + file.fileName());
	}
}
