package org.bloqqi.web;

import java.util.ArrayList;
import java.util.List;

import org.bloqqi.compiler.ast.ASTNode;

/**
 * Source text with conversions from the parser's (line, column) positions to
 * character offsets, so that AST nodes can be located and replaced in the text.
 *
 * Positions follow the scanner (JFlex {@code %line %column}, 1-based): a line
 * ends at {@code \n}, {@code \r\n} or {@code \r}, and every character, tabs
 * included, is one column. AST end positions are inclusive.
 */
final class SourceText {
	private final String text;
	/** Offset of the first character of each line (index 0 = line 1). */
	private final List<Integer> lineStarts = new ArrayList<>();

	SourceText(String text) {
		this.text = text;
		lineStarts.add(0);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
				i++;
				lineStarts.add(i + 1);
			} else if (c == '\n' || c == '\r') {
				lineStarts.add(i + 1);
			}
		}
	}

	String text() {
		return text;
	}

	/** The line separator used in the text: the first one found, or {@code \n}. */
	String lineSeparator() {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\r') {
				return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? "\r\n" : "\r";
			}
			if (c == '\n') {
				return "\n";
			}
		}
		return "\n";
	}

	/** Whether the parser recorded a position for the node (empty lists and opts have none). */
	static boolean hasPosition(ASTNode<?> node) {
		return node.getStartLine() > 0 && node.getEndLine() > 0;
	}

	/** Orders two (line, column) positions. */
	static int compare(int line1, int column1, int line2, int column2) {
		return line1 != line2 ? Integer.compare(line1, line2) : Integer.compare(column1, column2);
	}

	/** Offset of the node's first character. */
	int start(ASTNode<?> node) {
		return offset(node.getStartLine(), node.getStartColumn());
	}

	/** Offset just after the node's last character. */
	int end(ASTNode<?> node) {
		return offset(node.getEndLine(), node.getEndColumn()) + 1;
	}

	/** The node's text. */
	String text(ASTNode<?> node) {
		return text.substring(start(node), end(node));
	}

	int offset(int line, int column) {
		if (line < 1 || line > lineStarts.size() || column < 1) {
			throw new IllegalArgumentException("No position " + line + ":" + column);
		}
		int offset = lineStarts.get(line - 1) + column - 1;
		if (offset > text.length()) {
			throw new IllegalArgumentException("No position " + line + ":" + column);
		}
		return offset;
	}
}
