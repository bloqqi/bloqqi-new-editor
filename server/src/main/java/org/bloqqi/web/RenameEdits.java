package org.bloqqi.web;

import static org.bloqqi.web.NodeEdits.*;
import static org.bloqqi.web.SourceEdits.*;

import java.util.ArrayList;
import java.util.TreeMap;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.Block;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.Port;
import org.bloqqi.compiler.ast.VarUse;

/**
 * Renaming a block, variable or parameter, with all its uses in the file.
 */
public final class RenameEdits {
	private RenameEdits() {
	}

	/**
	 * Renames a block, variable or parameter declared in the type
	 * (docs/editor-spec.md §3.8): its declaration and every use in the file,
	 * e.g. {@code x}, {@code x.in1}, {@code P::x} in subtypes, annotation entries,
	 * and, for a parameter, port uses {@code blk.p} in other types. A block in
	 * the simple name form ({@code Add_1;}) becomes {@code newName: Add;}.
	 *
	 * <p>The uses are found by walking every name use in the file and following
	 * it to the declaration it means (through the wrappers that inheritance
	 * creates, and from a port to its parameter); the name is then replaced in
	 * the use's text. Uses that the compiler made up (e.g. the implicit port in
	 * {@code connect(Sub_1, x)}) have no text of their own; if the old name does
	 * not appear in the text around them, there is nothing to change.
	 *
	 * @throws IllegalArgumentException if the node is not declared here, the
	 *         name is invalid or taken, or the result would have errors
	 */
	public static String rename(LoadedFile file, String diagramTypeName, String nodeId, String newName) {
		requireEditable(file);
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		DiagramType dt = diagram.diagramType();
		org.bloqqi.compiler.ast.Node node = diagram.node(nodeId);
		if (node == null || node instanceof org.bloqqi.compiler.ast.Literal) {
			throw new IllegalArgumentException("No block, variable or parameter " + nodeId + " in " + dt.name());
		}
		if (!DiagramBuilder.isDeletable(node, dt)) {
			throw new IllegalArgumentException(nodeId + " is not declared in " + dt.name()
					+ " (it is inherited or inlined), so it cannot be renamed here");
		}
		String oldName = node.name();
		if (oldName.equals(newName)) {
			return file.source();
		}
		requireNewName(dt, newName);
		SourceText source = new SourceText(file.source());
		ASTNode<?> declaration = DiagramBuilder.declaration(node);

		TreeMap<Integer, TextEdit> edits = new TreeMap<>();
		// The declaration.
		if (declaration instanceof Block b) {
			if (b.getModifiers().isOnSimpleNameForm()) {
				String typeName = b.type().name();
				TextEdit e = new TextEdit(source.start(b), source.end(b), newName + ": " + typeName);
				edits.put(e.start(), e);
			} else {
				TextEdit e = replaceLastWord(source, b.getName(), oldName, newName);
				edits.put(e.start(), e);
			}
		} else {
			TextEdit e = replaceDeclaredName(source, declaration, oldName, newName);
			edits.put(e.start(), e);
		}
		// The uses.
		int declarationEdits = edits.size();
		collectUses(file.compilationUnit(), declaration, source, oldName, newName, edits);
		int uses = edits.size() - declarationEdits;

		String newText = apply(file.source(), new ArrayList<>(edits.values()));
		LoadedFile after = check(file, newText);
		DiagramBuilder.Builder renamed = DiagramBuilder.resolve(after, diagramTypeName);
		org.bloqqi.compiler.ast.Node newNode = renamed.node(newName);
		if (newNode == null) {
			throw new IllegalStateException("Could not rename " + nodeId + " to " + newName);
		}
		// The new name must mean the same things: a use could otherwise end up
		// meaning something else that has the new name (e.g. a block of the same
		// name in a subtype), or an existing use of the new name could end up
		// meaning the renamed one. Both change the number of uses.
		TreeMap<Integer, TextEdit> usesAfter = new TreeMap<>();
		collectUses(after.compilationUnit(), DiagramBuilder.declaration(newNode), new SourceText(newText),
				newName, newName, usesAfter);
		if (usesAfter.size() != uses) {
			throw new IllegalArgumentException("Not allowed: " + newName + " already means something else where "
					+ oldName + " is used (e.g. in a subtype), so the rename would change the diagram");
		}
		return newText;
	}

	/** Adds an edit for every use of {@code target} under {@code n}. */
	static void collectUses(ASTNode<?> n, ASTNode<?> target, SourceText source, String oldName,
			String newName, TreeMap<Integer, TextEdit> edits) {
		if (n instanceof VarUse use && refersTo(use, target)) {
			ASTNode<?> positioned = use;
			while (positioned != null && !SourceText.hasPosition(positioned)) {
				positioned = positioned.getParent();
			}
			if (positioned != null) {
				TextEdit e = replaceLastWordOrNull(source, positioned, oldName, newName);
				if (e != null) {
					edits.putIfAbsent(e.start(), e);
				}
			}
		}
		for (int i = 0; i < n.getNumChild(); i++) {
			collectUses(n.getChild(i), target, source, oldName, newName, edits);
		}
	}

	/** Whether a name use means the declaration (seen through inheritance wrappers and ports). */
	private static boolean refersTo(VarUse use, ASTNode<?> target) {
		org.bloqqi.compiler.ast.VarDecl d = use.decl();
		if (d instanceof Port p) {
			if (target instanceof org.bloqqi.compiler.ast.Parameter) {
				return original(p.getParameter()) == target;
			}
			return false;
		}
		return d != null && original(d) == target;
	}

	/** The declaration behind an inheritance (or inlining) wrapper. */
	private static ASTNode<?> original(ASTNode<?> d) {
		ASTNode<?> current = d;
		for (int i = 0; i < 100; i++) {
			ASTNode<?> next = current instanceof org.bloqqi.compiler.ast.Node n ? DiagramBuilder.declaration(n) : null;
			if (next == null || next == current) {
				return current;
			}
			current = next;
		}
		return current;
	}

	/** In a parameter or variable declaration: the name, which is before ':' or last. */
	private static TextEdit replaceDeclaredName(SourceText source, ASTNode<?> declaration, String oldName,
			String newName) {
		String text = source.text(declaration);
		int colon = text.indexOf(':');
		int at = colon >= 0 ? lastWord(text.substring(0, colon), oldName) : lastWord(text, oldName);
		if (at < 0) {
			throw new IllegalStateException("Cannot find " + oldName + " in " + text);
		}
		int start = source.start(declaration) + at;
		return new TextEdit(start, start + oldName.length(), newName);
	}

	private static TextEdit replaceLastWord(SourceText source, ASTNode<?> n, String oldName, String newName) {
		TextEdit e = replaceLastWordOrNull(source, n, oldName, newName);
		if (e == null) {
			throw new IllegalStateException("Cannot find " + oldName + " in " + source.text(n));
		}
		return e;
	}

	static TextEdit replaceLastWordOrNull(SourceText source, ASTNode<?> n, String oldName, String newName) {
		int at = lastWord(source.text(n), oldName);
		if (at < 0) {
			return null;
		}
		int start = source.start(n) + at;
		return new TextEdit(start, start + oldName.length(), newName);
	}

	/** Index of the last occurrence of {@code word} as a whole identifier in {@code text}, or -1. */
	private static int lastWord(String text, String word) {
		for (int i = text.lastIndexOf(word); i >= 0; i = i == 0 ? -1 : text.lastIndexOf(word, i - 1)) {
			boolean startOk = i == 0 || !isIdChar(text.charAt(i - 1));
			int end = i + word.length();
			boolean endOk = end >= text.length() || !isIdChar(text.charAt(end));
			if (startOk && endOk) {
				return i;
			}
		}
		return -1;
	}

	private static boolean isIdChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '$';
	}
}
