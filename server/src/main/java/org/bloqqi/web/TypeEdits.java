package org.bloqqi.web;

import static org.bloqqi.web.NodeEdits.*;
import static org.bloqqi.web.RenameEdits.*;
import static org.bloqqi.web.SourceEdits.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.Block;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.TypeDecl;

/**
 * Edits of types: new diagram types, subtypes and external functions,
 * renaming and deleting types, and a type's super types.
 */
public final class TypeEdits {
	private TypeEdits() {
	}

	/** A parameter of a new external function. */
	public record Parameter(String name, String typeName) {
	}

	/**
	 * Adds an empty diagram type {@code diagramtype <name> {}} at the end of the
	 * file (written as the old editor wrote types without parameters).
	 *
	 * @throws IllegalArgumentException if the name is invalid or a type of that name exists
	 */
	public static String addDiagramType(LoadedFile file, String name) {
		requireEditable(file);
		requireNewTypeName(file, name);
		SourceText source = new SourceText(file.source());
		String nl = source.lineSeparator();
		String text = file.source();
		boolean endsWithNewline = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r");
		String newText = text + (endsWithNewline ? "" : nl) + "diagramtype " + name + " {" + nl + "}" + nl;
		LoadedFile after = check(file, newText);
		if (!DiagramBuilder.diagramTypeNames(after.compilationUnit()).contains(name)) {
			throw new IllegalStateException("Could not add diagram type " + name);
		}
		return newText;
	}

	/**
	 * Adds an external function {@code external <name>(a: Int, b: Real => r: Bool);}
	 * with zero or more input parameters and an optional output parameter, after
	 * the file's last external function (or at the end of the file).
	 *
	 * @param output the output parameter, or null
	 * @throws IllegalArgumentException if a name or type is invalid, or names clash
	 */
	public static String addExternalFunction(LoadedFile file, String name, List<Parameter> inputs, Parameter output) {
		requireEditable(file);
		requireNewTypeName(file, name);
		List<Parameter> all = new ArrayList<>(inputs);
		if (output != null) {
			all.add(output);
		}
		Set<String> names = new HashSet<>();
		for (Parameter p : all) {
			if (p.name() == null || !org.bloqqi.compiler.ast.Program.isIdValid(p.name())) {
				throw new IllegalArgumentException("Not a valid parameter name: \"" + p.name() + "\"");
			}
			if (!names.add(p.name())) {
				throw new IllegalArgumentException("Two parameters are called " + p.name());
			}
			requireSimpleType(p.typeName());
		}
		StringJoiner ins = new StringJoiner(", ");
		inputs.forEach(p -> ins.add(p.name() + ": " + p.typeName()));
		String out = output == null ? ""
				: (inputs.isEmpty() ? "=> " : " => ") + output.name() + ": " + output.typeName();
		String declaration = "external " + name + "(" + ins + out + ");";

		SourceText source = new SourceText(file.source());
		String nl = source.lineSeparator();
		String text = file.source();
		int lastExternal = -1;
		for (TypeDecl td : file.compilationUnit().typeDecls()) {
			if (td.isExternalFunction() && SourceText.hasPosition(td)) {
				lastExternal = Math.max(lastExternal, source.end(td));
			}
		}
		String newText;
		if (lastExternal >= 0) {
			int eol = endOfLine(text, lastExternal);
			newText = text.substring(0, eol) + nl + declaration + text.substring(eol);
		} else {
			boolean endsWithNewline = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r");
			newText = text + (endsWithNewline ? "" : nl) + declaration + nl;
		}
		LoadedFile after = check(file, newText);
		TypeDecl added = after.program().lookupType(name);
		if (added == null || !added.isExternalFunction()) {
			throw new IllegalStateException("Could not add external function " + name);
		}
		return newText;
	}

	/**
	 * Adds {@code diagramtype <name> extends <superType> {}} at the end of the
	 * file (the old editor's "specialise").
	 */
	public static String addSubtype(LoadedFile file, String superType, String name) {
		requireEditable(file);
		TypeDecl sup = file.program().lookupType(superType);
		if (!(sup instanceof DiagramType)) {
			throw new IllegalArgumentException(superType + " is not a diagram type");
		}
		requireNewTypeName(file, name);
		SourceText source = new SourceText(file.source());
		String nl = source.lineSeparator();
		String text = file.source();
		boolean endsWithNewline = text.isEmpty() || text.endsWith("\n") || text.endsWith("\r");
		String newText = text + (endsWithNewline ? "" : nl)
				+ "diagramtype " + name + " extends " + superType + " {" + nl + "}" + nl;
		LoadedFile after = check(file, newText);
		if (!DiagramBuilder.diagramTypeNames(after.compilationUnit()).contains(name)) {
			throw new IllegalStateException("Could not add diagram type " + name);
		}
		return newText;
	}

	/**
	 * Renames a diagram type or external function of the file, and every use:
	 * block types ({@code x: T}), {@code extends T}, wirings {@code T[...]},
	 * qualified names {@code T::x} (also in annotation keys), and simple-named
	 * blocks of the type ({@code T_1} becomes {@code U_1}, with their uses).
	 * As for nodes, the uses are counted again afterwards, so a rename cannot
	 * change what a name refers to.
	 */
	public static String renameType(LoadedFile file, String typeName, String newName) {
		requireEditable(file);
		TypeDecl td = localType(file, typeName);
		if (typeName.equals(newName)) {
			return file.source();
		}
		requireNewTypeName(file, newName);
		SourceText source = new SourceText(file.source());
		TreeMap<Integer, TextEdit> edits = new TreeMap<>();
		TextEdit declaration = typeNameEdit(source, td, typeName, newName);
		edits.put(declaration.start(), declaration);
		int uses = collectTypeUses(file.compilationUnit(), td, source, typeName, newName, edits);

		// Simple-named blocks T_1, T_2, ... are named after their type.
		for (Block b : simpleBlocksOf(file.compilationUnit(), td)) {
			String oldBlock = b.name();
			String newBlock = newName + oldBlock.substring(typeName.length());
			TextEdit e = new TextEdit(source.start(b), source.end(b), newBlock);
			edits.put(e.start(), e);
			collectUses(file.compilationUnit(), b, source, oldBlock, newBlock, edits);
		}

		String newText = apply(file.source(), new ArrayList<>(edits.values()));
		LoadedFile after = check(file, newText);
		TypeDecl renamed = after.program().lookupType(newName);
		if (renamed == null) {
			throw new IllegalStateException("Could not rename " + typeName + " to " + newName);
		}
		int usesAfter = collectTypeUses(after.compilationUnit(), renamed, new SourceText(newText), newName, newName,
				new TreeMap<>());
		if (usesAfter != uses) {
			throw new IllegalArgumentException("Not allowed: the rename would change what some names refer to");
		}
		return newText;
	}

	/**
	 * Deletes a diagram type (with its annotations and wiring) or an external
	 * function of the file. Refused while anything else uses it.
	 */
	public static String deleteType(LoadedFile file, String typeName) {
		requireEditable(file);
		TypeDecl td = localType(file, typeName);
		java.util.Set<String> users = new java.util.LinkedHashSet<>();
		findUsers(file.compilationUnit(), td, null, users);
		if (!users.isEmpty()) {
			throw new IllegalArgumentException("Cannot delete " + typeName + ": it is used in "
					+ String.join(", ", users));
		}
		SourceText source = new SourceText(file.source());
		int[] r = statementLines(source, source.start(td), source.end(td));
		String newText = apply(file.source(), List.of(new TextEdit(r[0], r[1], "")));
		check(file, newText);
		return newText;
	}

	/**
	 * Sets the super types of a diagram type: rewrites its {@code extends}
	 * clause ({@code extends A, B}, or none). Type cycles, and uses of members
	 * that would no longer be inherited, are refused by the reparse check.
	 */
	public static String setSuperTypes(LoadedFile file, String typeName, List<String> superTypes) {
		requireEditable(file);
		DiagramType dt = topLevelDiagramType(file, typeName);
		Set<String> seen = new HashSet<>();
		for (String s : superTypes) {
			if (!seen.add(s)) {
				throw new IllegalArgumentException(s + " is listed twice");
			}
			if (s.equals(typeName)) {
				throw new IllegalArgumentException("A type cannot extend itself");
			}
			if (!(file.program().lookupType(s) instanceof DiagramType)) {
				throw new IllegalArgumentException(s + " is not a diagram type");
			}
		}
		SourceText source = new SourceText(file.source());
		Header h = header(source, dt);
		int start = h.rpar() >= 0 ? h.rpar() + 1 : h.nameEnd();
		int end = bodyBraces(source, dt)[0];
		String clause = superTypes.isEmpty() ? " " : " extends " + String.join(", ", superTypes) + " ";
		String newText = apply(file.source(), List.of(new TextEdit(start, end, clause)));
		check(file, newText);
		return newText;
	}

	/** A diagram type or external function declared in the file. */
	private static TypeDecl localType(LoadedFile file, String name) {
		for (TypeDecl td : file.compilationUnit().typeDecls()) {
			if (td.name().equals(name) && (td.isDiagramType() || td.isExternalFunction())) {
				return td;
			}
		}
		throw new IllegalArgumentException("No diagram type or external function " + name + " in " + file.fileName());
	}

	/** The name in {@code diagramtype <name>} / {@code external <name>}. */
	private static TextEdit typeNameEdit(SourceText source, TypeDecl td, String oldName, String newName) {
		String text = source.text(td);
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("\\b(diagramtype|external)\\s+(" + java.util.regex.Pattern.quote(oldName) + ")(?![A-Za-z0-9_$])")
				.matcher(text);
		if (!m.find()) {
			throw new IllegalStateException("Cannot find the name of " + oldName);
		}
		int start = source.start(td) + m.start(2);
		return new TextEdit(start, start + oldName.length(), newName);
	}

	/**
	 * Adds an edit for every type use of {@code td} (where the name is written)
	 * and returns how many uses there are.
	 */
	private static int collectTypeUses(ASTNode<?> n, TypeDecl td, SourceText source, String oldName,
			String newName, TreeMap<Integer, TextEdit> edits) {
		int count = 0;
		if (n instanceof org.bloqqi.compiler.ast.TypeUse use && use.decl() == td && SourceText.hasPosition(use)) {
			TextEdit e = replaceLastWordOrNull(source, use, oldName, newName);
			if (e != null) {
				edits.putIfAbsent(e.start(), e);
				count++;
			}
		}
		for (int i = 0; i < n.getNumChild(); i++) {
			count += collectTypeUses(n.getChild(i), td, source, oldName, newName, edits);
		}
		return count;
	}

	/** The blocks written in the simple name form ({@code T_1;}) whose type is {@code td}. */
	private static List<Block> simpleBlocksOf(ASTNode<?> n, TypeDecl td) {
		List<Block> result = new ArrayList<>();
		if (n instanceof Block b && b.getModifiers().isOnSimpleNameForm() && b.type() == td
				&& SourceText.hasPosition(b)) {
			result.add(b);
		}
		for (int i = 0; i < n.getNumChild(); i++) {
			result.addAll(simpleBlocksOf(n.getChild(i), td));
		}
		return result;
	}

	/** Names of the declarations (other than {@code td}) that use {@code td}. */
	private static void findUsers(ASTNode<?> n, TypeDecl td, String enclosing, Set<String> users) {
		String here = enclosing;
		if (n instanceof org.bloqqi.compiler.ast.Declaration d && n.getParent() != null
				&& n.getParent().getParent() instanceof org.bloqqi.compiler.ast.CompilationUnit) {
			here = d instanceof TypeDecl t ? t.name() : "a " + d.getClass().getSimpleName().toLowerCase();
		}
		if (n == td) {
			return;
		}
		if (n instanceof org.bloqqi.compiler.ast.TypeUse use && use.decl() == td && here != null) {
			users.add(here);
		}
		for (int i = 0; i < n.getNumChild(); i++) {
			findUsers(n.getChild(i), td, here, users);
		}
	}

	/** A new type name: an identifier no type has (in the file, the standard library or built in). */
	private static void requireNewTypeName(LoadedFile file, String name) {
		if (name == null || !org.bloqqi.compiler.ast.Program.isIdValid(name)) {
			throw new IllegalArgumentException("Not a valid name: \"" + name + "\"");
		}
		if (file.program().lookupType(name) != null) {
			throw new IllegalArgumentException("There is already a type called " + name);
		}
	}
}
