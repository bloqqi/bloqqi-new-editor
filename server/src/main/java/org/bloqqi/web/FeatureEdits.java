package org.bloqqi.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.bloqqi.compiler.ast.ASTNode;
import org.bloqqi.compiler.ast.AnonymousDiagramType;
import org.bloqqi.compiler.ast.AnonymousType;
import org.bloqqi.compiler.ast.Block;
import org.bloqqi.compiler.ast.DiagramType;
import org.bloqqi.compiler.ast.FeatureDeclStmt;
import org.bloqqi.compiler.ast.FeatureSelection;
import org.bloqqi.compiler.ast.FeatureSelectionOptional;
import org.bloqqi.compiler.ast.FeatureUse;
import org.bloqqi.compiler.ast.InheritedConnection;
import org.bloqqi.compiler.ast.Node;
import org.bloqqi.compiler.ast.Port;
import org.bloqqi.compiler.ast.TypeDecl;
import org.bloqqi.web.DiagramEdits.TextEdit;

/**
 * Feature selection (docs/editor-spec.md §4.1): a diagram type B can declare
 * optional features, {@code features B { f1: F1; f2: F2; f1 excludes f2; f1 before f3; }},
 * and a block of type B selects some of them in an anonymous type,
 * {@code b: B { feature f1; feature f3; };}. The compiler computes what can be
 * selected ({@code DiagramType.featureSelection()}) and in which order the
 * features are written (the {@code before} rules, ties by name).
 */
final class FeatureEdits {
	private FeatureEdits() {
	}

	/**
	 * A feature that can be selected.
	 *
	 * @param excludes the features it cannot be used together with
	 * @param before   the features it must come before
	 */
	record Option(String name, String typeName, boolean selected, List<String> excludes, List<String> before) {
	}

	/** The features of a block: its base type's optional features, and which are selected. */
	record Features(String block, String base, List<Option> options) {
	}

	/**
	 * The type whose features a block can select: its own type, or the first super
	 * type of its anonymous type ({@code b: B { feature f; }}); null if that type
	 * declares no features.
	 */
	static DiagramType featureBase(Block declared) {
		TypeDecl type = declared.hasAnonymousDiagramType()
				? firstSuperType(declared.anonymousDiagramType())
				: declared.type();
		return type instanceof DiagramType dt && !dt.featureDecls().isEmpty() ? dt : null;
	}

	private static TypeDecl firstSuperType(AnonymousDiagramType adt) {
		return adt.getNumSuperType() > 0 ? adt.getSuperType(0).type() : null;
	}

	/** The block's features; the caller holds the compiler lock. */
	static Features features(LoadedFile file, String diagramTypeName, String blockId) {
		Block declared = declaredBlock(file, diagramTypeName, blockId);
		DiagramType base = requireBase(declared, blockId);
		List<Option> options = new ArrayList<>();
		for (FeatureSelectionOptional o : selection(declared, base).getOptionalFeatures()) {
			FeatureDeclStmt decl = o.getFeatureDecl();
			options.add(new Option(o.getName(), decl.getTypeUse().getID(), o.isSelected(),
					names(decl.excludesFeaturesTransitive(), decl), names(decl.after(), decl)));
		}
		return new Features(blockId, base.name(), options);
	}

	/**
	 * Selects exactly the given features for a block declared in the type, in
	 * the order the {@code before} rules give. The block becomes
	 * {@code name: Base { feature ...; }} (other contents of its braces are
	 * kept); with no features and nothing else in the braces, {@code name: Base;}.
	 * Connections of the type on ports the block no longer has (a deselected
	 * feature's parameters) are deleted in the same edit. Like every edit, it is
	 * refused if it adds errors (e.g. a subtype still uses such a port).
	 *
	 * @throws IllegalArgumentException if the block or a feature does not exist,
	 *         two features exclude each other, or the edit adds errors
	 */
	static String setFeatures(LoadedFile file, String diagramTypeName, String blockId, List<String> features) {
		DiagramEdits.requireEditable(file);
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		Block declared = declaredBlock(file, diagramTypeName, blockId);
		DiagramType base = requireBase(declared, blockId);

		// Validate and order the selection.
		FeatureSelection selection = base.featureSelection();
		Set<String> chosen = new LinkedHashSet<>();
		for (String name : features) {
			FeatureSelectionOptional option = selection.getOptionalFeatures().stream()
					.filter(o -> o.getName().equals(name)).findFirst()
					.orElseThrow(() -> new IllegalArgumentException(base.name() + " has no feature " + name));
			if (!chosen.add(name)) {
				throw new IllegalArgumentException("Feature " + name + " is listed twice");
			}
			for (String excluded : names(option.getFeatureDecl().excludesFeaturesTransitive(), option.getFeatureDecl())) {
				if (chosen.contains(excluded)) {
					throw new IllegalArgumentException(
							"Feature " + name + " cannot be used together with " + excluded);
				}
			}
			option.setSelected(true);
		}
		Block ordered = selection.newAnonymousBlock(declared.name());
		List<String> order = new ArrayList<>();
		for (FeatureUse use : ((AnonymousType) ordered.getType()).getAnonymousDiagramType().getFeatureUses()) {
			order.add(use.getFeatureIdUse().getID());
		}

		SourceText source = new SourceText(file.source());
		List<TextEdit> edits = new ArrayList<>(declarationEdits(source, declared, base, order));
		if (edits.isEmpty()) {
			return file.source(); // nothing to change
		}

		// Connections on ports the block will no longer have.
		LoadedFile changed = BloqqiLoader.load(file.fileName(), DiagramEdits.apply(file.source(), edits));
		Set<String> ports = changed.isParsed() ? portHandles(changed, diagramTypeName, blockId) : null;
		DiagramType dt = diagram.diagramType();
		if (ports != null) {
			Set<ASTNode<?>> removed = new HashSet<>();
			for (InheritedConnection c : dt.getConnections()) {
				String gone = goneEnd(c, blockId, ports);
				if (gone != null && DiagramBuilder.isDeletable(c, dt) && removed.add(c.getDeclaredFlowDecl())) {
					ASTNode<?> d = c.getDeclaredFlowDecl();
					int[] r = DiagramEdits.statementLines(source, source.start(d), source.end(d));
					edits.add(new TextEdit(r[0], r[1], ""));
				}
			}
		}
		// A stored size: the height that fits the block's ports now.
		Integer height = changed.isParsed() ? newHeight(diagram, changed, diagramTypeName, blockId) : null;
		if (height != null) {
			TreeMap<String, Layout.Rect> entries = DiagramEdits.annotations(source, dt);
			Layout.Rect r = entries.get(blockId);
			if (r != null && r.height() != height) {
				entries.put(blockId, new Layout.Rect(r.x(), r.y(), r.width(), height));
				edits.add(DiagramEdits.annotationEdit(source, dt, entries));
			}
		}
		String newText = DiagramEdits.apply(file.source(), edits);
		DiagramEdits.check(file, newText);
		return newText;
	}

	/**
	 * The minimum height of the block after the edit, if the edit changes how
	 * many ports it has on a side (otherwise null: its stored height is kept).
	 */
	private static Integer newHeight(DiagramBuilder.Builder before, LoadedFile changed, String diagramTypeName,
			String blockId) {
		Node old = before.node(blockId);
		Node now;
		try {
			now = DiagramBuilder.resolve(changed, diagramTypeName).node(blockId);
		} catch (IllegalArgumentException noSuchType) {
			return null;
		}
		if (!(old instanceof Block o) || !(now instanceof Block b)) {
			return null;
		}
		int portsBefore = Math.max(o.getNumInPort(), o.getNumOutPort());
		int portsAfter = Math.max(b.getNumInPort(), b.getNumOutPort());
		return portsBefore == portsAfter ? null : Layout.minHeight(b);
	}

	/** The edits that write the selected features into the block's declaration. */
	private static List<TextEdit> declarationEdits(SourceText source, Block declared, DiagramType base,
			List<String> order) {
		String text = source.text();
		String indent = lineIndent(text, source.start(declared));
		List<String> lines = order.stream().map(f -> indent + "  feature " + f + ";").toList();
		List<TextEdit> edits = new ArrayList<>();
		if (declared.hasAnonymousDiagramType()) {
			AnonymousDiagramType adt = declared.anonymousDiagramType();
			int[] braces = DiagramEdits.bodyBraces(source, adt);
			// The existing feature uses go; the new ones are written first in the braces.
			List<int[]> uses = new ArrayList<>();
			for (FeatureUse use : adt.getFeatureUses()) {
				int end = DiagramEdits.semicolonAfter(text, source.end(use), braces[1]);
				uses.add(DiagramEdits.statementLines(source, source.start(use), end));
			}
			String rest = withoutRanges(text.substring(braces[0] + 1, braces[1]), braces[0] + 1, uses);
			if (rest.isBlank()) {
				int start = source.start(declared);
				if (order.isEmpty()) {
					// b: B { } -> b: B
					edits.add(new TextEdit(start, braces[1] + 1, text.substring(start, braces[0]).stripTrailing()));
				} else {
					edits.add(new TextEdit(braces[0] + 1, braces[1],
							"\n" + String.join("\n", lines) + "\n" + indent));
				}
			} else {
				for (int[] r : uses) {
					edits.add(new TextEdit(r[0], r[1], ""));
				}
				if (!order.isEmpty()) {
					edits.add(new TextEdit(braces[0] + 1, braces[0] + 1, "\n" + String.join("\n", lines)));
				}
			}
		} else if (!order.isEmpty()) {
			// B_1 or b: B -> b: B { feature ...; }
			String head = declared.getModifiers().isOnSimpleNameForm()
					? declared.name() + ": " + base.name()
					: source.text(declared);
			edits.add(new TextEdit(source.start(declared), source.end(declared),
					head + " {\n" + String.join("\n", lines) + "\n" + indent + "}"));
		}
		return edits;
	}

	/** The connection's end on the block that refers to a port the block will no longer have, or null. */
	private static String goneEnd(InheritedConnection c, String blockId, Set<String> ports) {
		for (var anchor : List.of(c.getSource().anchor(), c.getTarget().anchor())) {
			if (anchor instanceof Port p && p.block().accessString().equals(blockId)) {
				String handle = p.isInParameter() ? Diagram.inPortHandle(p.name()) : Diagram.outPortHandle(p.name());
				if (!ports.contains(handle)) {
					return handle;
				}
			}
		}
		return null;
	}

	/** The block's port handles in a (changed) file, or null if it cannot be found there. */
	private static Set<String> portHandles(LoadedFile file, String diagramTypeName, String blockId) {
		try {
			Diagram d = DiagramBuilder.build(file, diagramTypeName);
			for (Diagram.DiagramNode n : d.nodes()) {
				if (n.id().equals(blockId)) {
					Set<String> handles = new HashSet<>();
					n.inPorts().forEach(p -> handles.add(Diagram.inPortHandle(p.name())));
					n.outPorts().forEach(p -> handles.add(Diagram.outPortHandle(p.name())));
					return handles;
				}
			}
		} catch (IllegalArgumentException noSuchType) {
			// Then the check refuses the edit anyway.
		}
		return null;
	}

	private static Block declaredBlock(LoadedFile file, String diagramTypeName, String blockId) {
		DiagramBuilder.Builder diagram = DiagramBuilder.resolve(file, diagramTypeName);
		Node n = diagram.node(blockId);
		if (!(n instanceof Block)) {
			throw new IllegalArgumentException("No block " + blockId + " in " + diagramTypeName);
		}
		if (!DiagramBuilder.isDeletable(n, diagram.diagramType())
				|| !(DiagramBuilder.declaration(n) instanceof Block declared)) {
			throw new IllegalArgumentException(blockId + " is not declared in " + diagramTypeName
					+ " (it is inherited or inlined)");
		}
		return declared;
	}

	private static DiagramType requireBase(Block declared, String blockId) {
		DiagramType base = featureBase(declared);
		if (base == null) {
			throw new IllegalArgumentException("The type of " + blockId + " has no features");
		}
		return base;
	}

	private static FeatureSelection selection(Block declared, DiagramType base) {
		return declared.hasAnonymousDiagramType()
				? base.featureSelection(declared.anonymousDiagramType())
				: base.featureSelection();
	}

	/** Sorted feature names, without {@code self}. */
	private static List<String> names(Set<FeatureDeclStmt> decls, FeatureDeclStmt self) {
		Set<String> names = new TreeSet<>();
		for (FeatureDeclStmt d : decls) {
			if (d != self) {
				names.add(d.getID());
			}
		}
		return List.copyOf(names);
	}

	private static String lineIndent(String text, int offset) {
		int lineStart = offset;
		while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
			lineStart--;
		}
		int i = lineStart;
		while (i < offset && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
			i++;
		}
		return text.substring(lineStart, i);
	}

	/** A part of the text (starting at {@code offset}) without the given ranges. */
	private static String withoutRanges(String part, int offset, List<int[]> ranges) {
		StringBuilder sb = new StringBuilder(part);
		List<int[]> sorted = new ArrayList<>(ranges);
		sorted.sort((a, b) -> b[0] - a[0]);
		for (int[] r : sorted) {
			sb.delete(Math.max(0, r[0] - offset), Math.min(sb.length(), r[1] - offset));
		}
		return sb.toString();
	}
}
