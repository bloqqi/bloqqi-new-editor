# Bloqqi graphical editor: specification of the old Eclipse/GEF editor

This document records what the old Eclipse/GEF Bloqqi editor does. It is the
starting point for a new standalone, browser-based editor backed by a Java
server that hosts the JastAdd model. It describes how things work today, not
how the new editor should work. Where the old behaviour looks accidental or
buggy, the text says so. Decisions are collected in
[Open questions](#9-open-questions) at the end.

## 0. Sources and versions

| What | Where | Version |
|---|---|---|
| Old editor (Eclipse plug-in `org.bloqqi.editor`) | `editor/bloqqi-editor/org.bloqqi.editor/` (~10 kLOC, 143 Java files) | 0.2.7 (2020-09-30) |
| Compiler jar the editor actually runs | `editor/bloqqi-editor/org.bloqqi.editor/lib/bloqqi-compiler.jar` (self-contained, includes beaver runtime and `StandardFunctions.dia`) | built 2020-09-30 |
| Compiler submodule inside the editor | `editor/bloqqi-editor/org.bloqqi.editor/bloqqi-compiler/` | `408e388` |
| Compiler used by the new editor | `editor/bloqqi-compiler/` (submodule of the new editor's repository, same commit) | `408e388` |
| Current compiler | `bloqqi-compiler/` | `5ed56ff` |
| Example programs | `bloqqi-compiler/examples/*.dia` | |

Notes on the layout: the brief mentioned `compiler/`, `editor/` and
`examples/` at the repository root. On disk they are `bloqqi-compiler/`,
`editor/bloqqi-editor/` and `bloqqi-compiler/examples/`. The
`spec/graphical-editor/*` aspects and the grammar are **identical** in the
editor's submodule and the current compiler. The two differ in the C back end,
name and type analysis, the scanner, the parser and `CodeProberSupport.jrag`.
Running every example through both versions gave the same results (see §6.5).

**Decided (milestone 1):** the new editor uses only `/workspace/editor` and
targets compiler `408e388` (the old editor's submodule version) and its
`examples/`. Since the new editor got its own repository, the compiler is its
submodule `editor/bloqqi-compiler/`; the old editor's clone is kept, git-ignored,
in `editor/bloqqi-editor/` for reference. That compiler's own `ant jar` fails on JDK 21
(`TimeMeasure.java` uses `java.lang.Compiler`, which JDK 21 removed), so the new
build runs only its `gen` target and compiles the sources with `release=8`.
Also note: the generated AST keeps attribute-evaluation state in a **static**
field (`ASTNode.state`), so compiler use must be serialised across threads,
even for unrelated ASTs.

`lib/bloqqi-editor-commands.jar` contains `PostConditionCommandStack` and
`PostConditionCommand` as **class files only**; the source is not in either
repository. Its behaviour was recovered with `javap` (§3.9).

---

## 1. Architecture of the old editor in one page

* One editor instance edits **one `.dia` file**. On load, the file is parsed
  into a `CompilationUnit` and a fresh `Program` is built containing that unit
  plus the standard library (`Program.loadStandardLibrary()`). Other files and
  `import` declarations are not loaded, so the editor sees only types from the
  open file and the standard library. Everything uses
  `program.getCompilationUnit(0)`.
* The canvas shows **one `DiagramType` at a time**. Initially this is the first
  diagram type in the file; if there is none, the user is prompted for a name
  (default `Main`) and one is created. Another type can be opened from the
  outline or with *Open Declaration*.
* The **model is the JastAdd AST**. Every edit is a GEF `Command` that changes
  the AST directly (`addLocalBlock`, `getFlowDeclList().removeChild`, …). It
  then calls `program().flushAllAttributes()` and `notifyObservers()` on the
  affected `DiagramType` or `CompilationUnit`. The observer mechanism comes from
  the compiler aspect `spec/graphical-editor/Observable.jadd`: `ASTNode`
  implements `ASTObservable`. Edit parts register as `ASTObserver` and refresh,
  re-reading attributes.
* **Everything drawn is derived from attributes**. Mostly these are the NTA
  lists that include inherited elements: `DiagramType.getInParameterList()`,
  `getOutParameterList()`, `getBlockList()`, `getVariableList()`,
  `getConnectionList()` (all in `frontend/Inheritance.jrag`). Because
  `flushAllAttributes()` throws all NTAs away, every edit creates new
  `InheritedBlock` and other wrapper objects. GEF matches edit parts to model
  objects by identity, so edit parts are recreated after every edit.
* **Layout is not in the AST while editing.** Positions live in a side table,
  `Coordinates`: `WeakHashMap<DiagramType, Map<String, Rectangle>>`, keyed by
  `Node.accessString()`. They are read from the `annotation(...)` clauses on
  load and written back into `annotation(...)` on save (§6).
* **Saving regenerates the whole file** with `CompilationUnit.prettyPrint()`
  (§6).

---

## 2. Diagram elements and how each is derived from the AST

The root edit part (`DiagramTypePart`) asks the open `DiagramType dt` for its
children, in this order (`DiagramTypePart.getModelChildren`):

1. `dt.getInParameters()`: `List<InheritedInParameter>`
2. `dt.getOutParameters()`: `List<InheritedOutParameter>`
3. `dt.getBlocks()`: `List<InheritedBlock>`
4. `dt.literals()`: `java.util.List<Literal>`
5. `dt.variables()`: `List<InheritedVariable>`

Connections are not children. Each node part reports its connections through
`Anchor.outgoingConnections()` and `Anchor.ingoingConnections()` (collection
attributes in `frontend/Anchor.jrag`, rooted at `DiagramType`). These collect
over the NTA `connections()`.

### 2.1 Identity: `accessString`

Every node has a string key, `Node.accessString()` (`graphical-editor/Access.jrag`):

* a local node is keyed by its `name()`, for example `Sub_1` or `SP`
* an inherited node is keyed by `declaredInDiagramType().name() + "::" + name()`, for example `P::Out`
  (`ASTNode.DECLARED_IN_SEP = "::"`)
* an inlined node is keyed by its inlined name, joined with `$`
  (`ASTNode.INLINE_SEP = "$"`), for example `loop$master$controller`

These keys identify nodes in the layout table and in `annotation(...)`. They
are the natural candidate for stable IDs in the new client/server protocol.

### 2.2 Element catalogue

| Element | AST node(s) | Key attributes used | Visual encoding (old editor) |
|---|---|---|---|
| **Diagram (canvas)** | `DiagramType` (or `AnonymousDiagramType` when opened from the outline) | the NTA lists above | Freeform layer with a 1 px border, background RGB(240,240,240), zoomable |
| **In-parameter** | `InheritedInParameter` (NTA wrapper; `getDeclaredInParameter()` is the real decl) | `isInherited()` (`= declaredInDiagramType() != diagramType()`), `name()`, `outgoingConnections()`/`ingoingConnections()` | Rectangle plus right-pointing triangle; label = name. Local: blue outline, white fill, dark-blue marker. Inherited: dashed black outline, grey fill RGB(220,220,220). Connection anchor on the right edge. |
| **Out-parameter** | `InheritedOutParameter` | same as above | As for in-parameters, but with a thin bar on the left; anchor on the left edge |
| **Block** | `InheritedBlock` (wraps `getDeclaredBlock()`), `InlinedBlock` (subclass) | see the block flags below | Rounded rectangle; label = name (or `Type` for simple names, see below); with *Show types* on, `name:Type` |
| **Port** | `Port` (NTA `Block.getInPortList()` and `getOutPortList()`, created from the block type's parameters by `TypeDecl.createInPortList()`) | `index()`, `isInParameter()`, `type()`, `canAccess()`, `canModifyToAccess()`, `declaredParameter()` | 6×6 square on the left (in) or right (out) edge, at `y = 5 + index·14`. Dark blue if the block is deletable, black otherwise. Hover shows a tooltip `name: Type`. |
| **Variable** | `InheritedVariable`, `InlinedVariable` | `prettyPrintKind()` (`var`/`input`/`output`), `inlinedName()`, `type()`, `isInherited()`, `isInlined()` | Rectangle with label `kind name` (`kind name:Type` with *Show types*). Inherited or inlined: dashed black, grey or white fill. Centre (chopbox) anchor. |
| **Literal** | `Literal` (source of a `Connection`) | `enclosingConnection()`, `toString()` | Plain text label placed just left of the target node, vertically aligned to the target port. **Not stored in the layout.** |
| **Connection** | `InheritedConnection` and subclasses (see §2.4) | `canDelete()`, `isIntercept()`, `hasErrors()`, `isOnDataflowCycle()`, `getIsFirstConnection()` | Manhattan-routed polyline with an arrow head (none if the source is a literal). Local: blue solid. Inherited or not deletable: black dashed. Errors: red, width 2. |

**Block flags** (`BlockPart.refreshVisuals` / `BlockFigure`):

| Flag | Attribute | Effect |
|---|---|---|
| deletable / local | `Node.canDelete()` = `!isInherited() && !isInlined()` (`graphical-editor/EditingOperations.jrag`) | solid blue border and white fill; otherwise a dashed black border |
| inherited | `Block.isInherited()` (`frontend/Inheritance.jrag`) | grey fill RGB(220,220,220) |
| inlined | `Node.isInlined()` (`frontend/Inline.jrag`, true for `InlinedBlock`) | white fill with a dashed border. **Selecting** an inlined node gives every node with the same `inlinedBlock()` a light-blue fill (`NodeSelectionEditPolicy`). |
| locally redeclared | `Block.isLocallyRedeclared()` (`frontend/Redeclare.jrag`) | grey inner rectangle over the top half of the block |
| error | `Block.isOnTypeCycle()` (`TypeAnalysis.jrag`) or `Block.isDataflowCircular()` (`Node.jrag`) | red border and red text |
| label | `Modifiers.isOnSimpleNameForm()` (`Modifiers.jrag`): `block.type().name()`. Otherwise `Node.inlinedName()`, prefixed with `declaredInDiagramType().name()+"::"` when `Node.isNameAmbiguous()` | |
| size | the stored rectangle; the figure is widened by 6 px on each side for the ports. Minimum height: `max(20, 10 + n·6 + (n−1)·8)` for `n = max(#in, #out)` | |

### 2.3 Inheritance and redeclaration

* Inheritance is flattened by the NTA lists. For every
  `superType ∈ dt.superTypesLinearized()`, the lists wrap the super type's
  *local* elements (`createInheritedInParameter`, `createInheritedBlocks`,
  `createInheritedConnection`, …). `superTypesLinearized()` is a
  `LinkedHashSet`, with super types first and `dt` last. Inherited elements are
  therefore drawn alongside local ones and only styled differently. They cannot
  be deleted or renamed.
* Inherited connections are **fully qualified** when copied
  (`Expr.copyFullQualification`, for example `connect(P::e.out, P::PPart.in1)`).
  Block references map to the most specific (redeclared) block through
  `DiagramType.mostSpecificBlock`.
* Wired blocks (`WiredBlock`, with `wiring T[...] { ... }` on the type)
  contribute extra parameters (`Block.inParameters()`/`outParameters()`) and
  connections (`Block.flowDecls()`, only for `isMostSpecificBlock(b)`) to the
  same lists. The old editor has no special rendering for them; see Q14.
* `redeclare b: T { ... }` / `redeclare b: super { ... }` (`RedeclareBlock`,
  `AnonymousTypeImplicitSuper`) is only visible as the "locally redeclared"
  marker. Editing the redeclared contents means opening the anonymous type from
  the outline.

### 2.4 Connection kinds, interceptions and inlining

`DiagramType.getConnectionList()` (`frontend/Inheritance.jrag`) builds the list
in three passes. The old editor reflects each pass visually:

1. **Plain connections** from every super type's `FlowDecl`s (and from wired
   blocks) become `InheritedConnection`s with `getDeclaredFlowDecl()` pointing
   at the source `Connection`. `Connection.isInherited()` is
   `declaredInDiagramType() != diagramType()`.
2. **Interceptions** (`FlowDecl.doInterception`):
   * `intercept X with T, S;`: `ConnectionInterception(Intercepted=X, Target=T, Source=S)`.
     The connection `A → X` is replaced by two `InterceptConnection`s:
     `A → T` (`IsFirstConnection=true`) and `S → X` (`IsFirstConnection=false`).
   * `intercept source X with T, S;`: `SourceInterception`. This adds
     `X → T` (first). Every connection `X → Y` that is declared in a type that
     is not a subtype of the intercepting type (or in the intercepting type
     itself) is replaced by `S → Y` (second).
   * Drawing (`ConnectionPart`, `SplitPolylineConnection`): the half that
     belongs to the original, inherited connection is dashed black. The new
     half is solid blue. A small rectangle marks the midpoint; it is large for
     the "far" side of a source interception and for the first half of a
     connection interception.
   * Both halves have `getDeclaredFlowDecl()` set to the `Interception`, so
     deleting either half deletes the interception (§3.4).
   * Example: in `PLib.dia`, `PTrack extends P` contains
     `intercept Out with SelReal_1.in2, SelReal_1.out;`. The probe gives
     `P::PPart.out → PTrack::SelReal_1.in2 (first)` and
     `PTrack::SelReal_1.out → Out (second)`.
3. **Inlining**: for each declared block with `Block.shouldBeInlined()`
   (`inline` modifier and `canBeInlined()`: the block's type is a diagram type
   with blocks or variables and is not on a type cycle), the block is replaced:
   * `Block.createInheritedBlocks()` produces one `InlinedBlock` per block of
     the block's type, recursively. Its name is
     `InlinedName(outer, inner)` → `outer$inner`.
   * `Block.createInlinedVariables()` produces `InlinedVariable`s.
   * `DiagramType.addInlinedConnections` copies the inner type's connections
     as `InlinedConnection`s and splices the outer connections through the
     inner parameters, so the block's own parameters disappear.
   * `InlinedConnection.canDelete()` is true only when the connection is
     declared in this type and its endpoints are not both inside the same
     inlined block.
   * Example: in `InlineExample.dia`, type `Tank` shows
     `loop$slave$controller`, `loop$slave$filter`, `loop$override`,
     `loop$master$filter` and `loop$master$controller` instead of `loop`.

### 2.5 Features and anonymous types

* A block `pid: P { feature Derivative; feature Integral; };` has an
  `AnonymousType(AnonymousDiagramType)` with super type `P` and `FeatureUse`s.
  `AnonymousDiagramType.directSuperTypes()` adds the feature implementation
  types (`PD`, `PI`). On the canvas this is an ordinary block whose type label
  is the anonymous type's name (`Main$pid`). The features are **not shown on
  the canvas**. They are visible in the "Change features…" wizard (§4.1), and
  the anonymous type appears in the outline.
* Anonymous types also come from recommendation specialisation (§4.2), from
  `T { ... } name` blocks in source, and from the "connect into inlined block"
  rewrite (§3.2).

### 2.6 What is not drawn

The following are not drawn: `annotation(...)`, `wiring` declarations,
`recommendation` and `features` declarations, structs, external functions,
functions, state machines (these four only appear as palette entries), doc
strings (shown as the palette tooltip), and diagram-type modifiers (`abstract`).

---

## 3. User actions on the canvas

### 3.1 Palette (`Palette.java`)

| Group | Entry | Tool class | Notes |
|---|---|---|---|
| Tools | Selection (default) | GEF `SelectionToolEntry` | move, resize, marquee |
| | Connection | `ConnectionCreationToolFeedback` | §3.2 |
| | Literal | `LiteralCreationTool` | §3.3 |
| | Parameter | `ParameterCreationTool` | dialog, §3.5 |
| | Variable | `VariableCreationTool` | dialog, §3.5 |
| **Local types** (pinned open) | one entry per `TypeDecl` of every compilation unit (in practice the open file) **except the open type**, where `(isDiagramType && !isAbstract) \|\| isFunction \|\| isExternalFunction \|\| isStateMachine` | `BlockCreationTool` | Tooltip: `Create a block of T` plus the first line of the doc string. Structs are excluded. Types that would create a type cycle are not filtered out. |
| **Standard lib** (closed) | same filter over `program.getStandardLibrary().typeDecls()` | `BlockCreationTool` | |

The palette is rebuilt every time another diagram type is shown.

### 3.2 Create connection

* **Tool feedback** (`PortsFeedback`): while the tool is active, every
  accessible port is coloured. A port counts as accessible when
  `Anchor.canAccess() || Anchor.canModifyToAccess()`. Green means valid:
  type-compatible with the start anchor (`TypeDecl.compatibleWith`) **and**
  without incoming connections. Yellow means anything else. Before the first
  click, out-ports are highlighted. After it, in-ports are highlighted if the
  start was a source, otherwise out-ports. **This is only feedback.** The
  command does not reject incompatible types or a second driver; those show up
  afterwards as red connections or errors.
* **Edit policy** (`NodeGraphicalNodeEditPolicy`, installed on ports,
  parameters and variables) creates `CreateConnectionCommand` if the anchor is
  accessible. Reconnecting an existing connection is not supported (both
  reconnect commands return `null`).
* **Command** (`CreateConnectionCommand`):
  1. If neither end `canAccess()`, it fails ("Connection could not be created").
  2. If the user drew it backwards (`!source.isSource() && target.isSource()`),
     source and target are swapped.
  3. Each end becomes a `VarUse` through `Anchor.access()`
     (`graphical-editor/Access.jrag`): `SimpleVarUse(name)` for parameters and
     variables, `DotVarUse(block, port)` for ports, and
     `DotVarUse(TypedSimpleVarUse(DeclType, block), port)` for ports on
     inherited blocks. For a port of an inlined block that is not directly
     accessible, `Port.modifyToAccess()` (`CanModifyToAccess` aspect) **rewrites
     the declared block**. It adds anonymous types where needed
     (`Block.addAnonymousTypesForNestedBlocks`), then adds a new parameter to
     each enclosing anonymous type and a connection from it
     (`ASTNode.addConnectionsParameters`), and returns
     `Pair<newBlock, VarUse>`. Only one end may need this rewrite.
  4. `dt.addFlowDecl(new Connection(src, tgt))`.
* Undo removes the connection and restores the original block if it was
  rewritten.

### 3.3 Create literal

`LiteralCreationTool` highlights in-ports. Clicking an accessible anchor opens
the dialog *"Enter literal value"*. `Program.parseLiteral(String)`
(`EditingOperations.jrag`) accepts exactly one `true`/`false`, `NUM`, `FLOAT`
or `STRING_LITERAL` token. Then `CreateLiteralCommand` adds
`connect(<literal>, <target access>)`. A literal that does not parse gives an
info dialog. Literals have no position of their own.

### 3.4 Delete (selection + Delete key/toolbar/context menu)

GEF `DeleteAction` works on a multi-selection; each edit part's policy supplies
the command:

| Selected | Policy → command | Allowed when | AST change |
|---|---|---|---|
| Block | `BlockComponentEditPolicy` → `DeleteBlockCommand` | `canDelete()` (local, not inlined) | Removes `getDeclaredBlock()` from `LocalBlockList`. Also removes from the local `FlowDeclList` the declared flow decl of every connection on the block's ports, which can be a `Connection` or an `Interception`. |
| Parameter | `ParameterComponentEditPolicy` → `DeleteParameterCommand` | not inherited | removes from `LocalIn/OutParameterList` plus attached flow decls |
| Variable | `VariableComponentEditPolicy` → `DeleteVariableCommand` | `canDelete()` | removes from `LocalVariableList` plus attached flow decls |
| Connection | `ConnectionEditPolicy` → `DeleteConnectionCommand` | `Connection.canDelete()` | removes `getDeclaredFlowDecl()`. For an intercept half this removes the whole `intercept` statement. |
| Literal | `LiteralComponentEditPolicy` → `DeleteLiteralCommand` | enclosing connection not inherited | removes the enclosing connection |

Undo restores a tree copy of the old `FlowDeclList` and re-inserts the
declaration at its old index. The layout entry is **not** removed on delete;
stale keys are dropped when saving (§6.3).

### 3.5 Create parameter / variable / block

* **Parameter**: a dialog (`AddParameterDialog`) asks for the kind
  (Input/Output), the name (validated with `Program.isIdValid`) and the type.
  **The type can only be `Int`, `Bool` or `Real`.** A name clash
  (`dt.lookup(name) != null`) gives an error dialog. The command calls
  `addLocalInParameter`/`addLocalOutParameter` and stores a 60×20 rectangle at
  the click point.
* **Variable**: `AddVariableDialog` asks for the kind (`state`/`input`/`output`
  → `StateVariable`/`InputVariable`/`OutputVariable`), the name and the type
  (`Int`/`Bool`/`Real` only). The command calls `addLocalVariable` and stores a
  60×20 rectangle.
* **Block** (`BlockCreationTool`, then `CreateBlockCommand`):
  1. If the type is a diagram type with `featureDecls()` non-empty, ask
     "select features?". Yes → the feature wizard (§4.1) yields
     `FeatureSelection.newAnonymousBlock(name)`.
  2. Otherwise, if the type `hasRecommendations()`, ask "specialize?". Yes →
     the specialisation wizard (§4.2) yields
     `FeatureConfiguration.newAnonymousBlock(name)` plus a set of inner
     parameters to expose.
  3. Otherwise, for diagram types and state machines, if the preference
     *Always ask for block name* is on (default **on**), a name dialog
     pre-filled with `Type_N` (the first unused N) appears. If the name is
     unchanged, the block gets the `simple` modifier and is printed as
     `Type_N;`. If it was changed, the block is printed as `name: Type;`.
     Functions and external functions never ask and always get a simple name.
  4. On execute, an invalid or clashing name falls back silently to a simple
     name. The command calls `dt.addLocalBlock`. For each exposed inner
     parameter it calls `DiagramType.addConnectionsParameters(blockName + "." +
     path, newName)` (`EditingOperations.jrag`), which adds parameters and
     connections inside the anonymous type tree. It then stores a rectangle at
     the drop point with the minimum block size.

### 3.6 Move / resize

`DiagramTypeXYLayoutEditPolicy` creates `ChangeConstraintNodeCommand` (or
`ChangeConstraintBlockCommand`, which strips the 6 px port margins). The
command only updates `Coordinates` for `(dt, accessString)` and does not touch
the AST. Height is clamped to at least 20. Inherited and inlined nodes can be
moved too; the position is stored under the subtype's key (for example
`P::e`), so each subtype has its own layout of inherited elements.

### 3.7 Context menu (canvas)

Items appear only when enabled (`ContextMenu.buildContextMenu`):

| Item | Enabled when | Effect |
|---|---|---|
| **Intercept** | exactly one connection selected, `isInherited() && !isInlined()` | Activates `InterceptTool(kind=CONNECTION)`. Compatible in-ports are highlighted. **First click** on an accessible block port → `T`; **second click** → `S`. `TargetInterceptCommand` adds `intercept <conn.target.access()> with T, S;` (`ConnectionInterception`). |
| **Intercept source port** | same | as above with `SourceInterceptCommand` → `intercept source <conn.source.access()> with T, S;` |
| Layout ▸ Show/hide types, Change all connection routers, Change connection router | preference *Show layout operations in context menu* (default off) | View only: the router toggles between Manhattan and straight lines and is **not persisted**. |
| **Open Declaration (T)** | one block selected whose `type()` is a diagram type | shows that type on the canvas (no back navigation) |
| **Inline / Collapse** | one block selected. Inline: `type().isDiagramType() && !isInherited()`. Collapse: block `isInlined()` and `inlinedBlock().declaredInDiagramType() == dt` | `SetBlockInlineCommand` toggles the `inline` modifier on the declared block (`Modifiers.setModifier("inline", …)`). On inline, positions are computed for the new inlined nodes by running KLay on the block's type, offset by (−50, −30) from the block. On collapse, the block gets the position of its inlined child closest to the origin. |
| **Rename…** | one local block, parameter or variable | §3.8 |
| **Change specialization…** | one local block with an anonymous type whose first direct super type `hasRecommendations()` | §4.2 (`ChangeBlockSpecializationCommand`) |
| **Change features…** | one local block with an anonymous type whose first direct super type has `featureDecls()` | §4.1 (`ChangeFeaturesBlockCommand`) |
| **Delete** | see §3.4 | |
| **Extract subtype as recommendation** | always shown | Error if `dt.isLocallyEmpty()` or `!dt.canExtractSubtypeAsBlock()` (`RecommendationSupport.jrag`). Asks for a new type name and a "feature name". `ExtractSubTypeAsRecommendationCommand` (needs exactly one direct super type, otherwise it does nothing) calls `dt.extractsubTypeAsWiredBlock(...)` (`SubTypeWrapperCreator`) and appends the new `DiagramType` with a `wiring` and a `recommendation Super { NewType[...] name; }` to the unit. The current type is left unchanged. |
| **Diagram properties…** | always | §4.3 |

**Toolbar** (`BloqqiActionBarContributor`): **Auto layout**
(`AutoLayoutCommand`: KLay on the whole type, undoable), **Show types**
(toggles a global preference and redraws), and a zoom combo. Undo, Redo and
Delete are *not* on the toolbar: the lines adding them are commented out
(`contributeToToolBar`); they are reached through Eclipse's Edit menu
(retarget actions), the canvas context menu (Delete) and the keyboard. A
Print action is registered. `ChangeNameDisplayedAction` (which cycles parameter labels between
showing and hiding names) is registered but **reachable from no menu**.
Keyboard: the standard Eclipse bindings for undo, redo and delete.

### 3.8 Rename (`RenameNodeCommand` and subclasses)

* Block: `Block.changeName(newName)` (`graphical-editor/Rename.jrag`) fails if
  `lookup(newName) != null`. It rewrites every use in `VarDecl.uses()` (a
  collection attribute over `VarUse.decl()`, including uses of ports through
  `declaredParameter()`), replaces the name with an `IdName`, and **drops the
  `simple` modifier**.
* Parameter and variable: `VarDecl.changeName`, which works the same way and
  sets the `ID` token.
* Layout keys are renamed in this type and in every type in `namedSubTypes()`
  (`Super::old` → `Super::new`).
* Rename runs as a `PostConditionCommand`: if `changeName` returns false, an
  info dialog "Could not change name to …" appears and the command is not put
  on the undo stack.

### 3.9 Command stack

`PostConditionCommandStack` (binary only) is a copy of GEF's `CommandStack`.
After `execute()`, if the command is a `PostConditionCommand` with
`couldExecute() == false`, the command is **not** pushed on the undo stack, and
an info dialog titled "Error" shows `getErrorMessage()`. This covers create
connection, create literal, rename, and rename diagram type. Commands with
`canUndo() == false` are `RenameDiagramTypeCommand` and the initial "create
Main" command.

---

## 4. Dialogs and wizards that change the model

### 4.1 Feature selection (`features B { ... }`, since 0.2.6)

* Model: `DiagramType.featureDecls()` (`frontend/Features.jrag`) and
  `DiagramType.featureSelection()` / `featureSelection(existingAnonType)`
  (`frontend/FeatureSelection.jrag`) → `FeatureSelection` with
  `FeatureSelectionOptional` items.
* UI (`wizards/features/*`): one page. It shows the base type (read-only), the
  block name (validated with `isIdValid` and `enclosing.lookup(name) == null`;
  read-only when changing an existing block), and a tree with one row per
  optional feature and a check icon. Features are selected and deselected
  through the row's context menu. `excludes` constraints are applied
  automatically, and the menu text lists what will be unselected.
* Result: `FeatureSelection.newAnonymousBlock(name)`, which is
  `name: B { feature f1; feature f2; }` with the feature uses topologically
  sorted by the `before` constraints.
  * New block: §3.5.
  * Change: `ChangeFeaturesBlockCommand` replaces the declared block with a
    copy that has the new anonymous type. It then removes every local flow decl
    whose anchor on that block no longer resolves
    (`FlowDecl.connectedNodesAnchors()` gives a pair with a null anchor).

### 4.2 Specialisation from recommendations (older mechanism)

* Model: `recommendation T { ... }` declarations become
  `DiagramType.getRecommendationGroups()` (optional features with alternatives)
  and `recommendedReplaceables()` (mandatory features with alternatives)
  (`frontend/Recommendation.jrag`). `DiagramType.hasRecommendations()`;
  `DiagramType.specialize()` / `specialize(existingSubType)` → a
  `FeatureConfiguration` (`frontend/RecommendationConfiguration.jrag`). An
  alternative can be specialised again recursively
  (`alt.specialize()`).
* UI (`wizards/specialize/*`), two pages:
  1. **Features**: the type to specialise, a new name (a type name or a block
     name depending on the entry point), and a tree of optional features
     (select/deselect), mandatory features and alternatives ("Select <Type>"),
     recursively.
  2. **Select parameters**: a checkbox table of
     `FeatureConfiguration.getNewInParameters()` (inner in-parameter paths such
     as `blk.inner.par`). Each has an editable new name, by default the first
     path segment plus the last one, and *Select all* is on by default. When
     changing an existing block, paths already exposed are pre-checked using
     `DiagramType.isInnerParameterExposed(path)`
     (`RecommendationSupport.jrag`).
* Entry points:
  * **New block** from the palette (§3.5).
  * **Change specialization…** → `ChangeBlockSpecializationCommand`: replace
    the block's type with `conf.newAnonymousBlock("").getType()`, expose the
    selected parameters, and remove dangling flow decls (as in §4.1).
  * **Specialize diagram…** in the outline →
    `CreateDiagramTypeSpecializationCommand`: append
    `conf.newDiagramType(name)` (a named subtype with the recommended blocks).
    For each selected parameter it calls
    `DiagramType.addConnectionsParametersToDiagramType(path, newName)`.

### 4.3 Diagram properties (`DiagramTypePropertiesWizard`)

* Shows the name (read-only), the local in- and out-parameters, and, for named
  types only, an ordered list of super types (add/remove/move up/down). Added
  super types are chosen from the diagram types in the file, sorted, excluding
  the type itself and those already listed.
* Parameters are added with `AddParameterDialog` (`Int`/`Bool`/`Real` only).
  The name is checked against the other parameters, local blocks, and
  `localLookup` in every super type.
* **Command** (`ChangeSuperTypesAndParametersCommand`):
  * Replaces `SuperTypeList`, `LocalInParameterList` and
    `LocalOutParameterList` **with new nodes** built from `name:type` strings.
  * Before that, it removes local flow decls that touch a node declared in a
    super type that is no longer reachable, and connections on removed
    parameters.
  * Undo restores the old lists. A commented-out "interception conflicts"
    panel (`DiagramType.interceptionConflicts()`) exists in the source.

### 4.4 Outline (`outline/*`)

* Tree: **Diagram types** → every named `DiagramType` in the unit. The label is
  `Name : [Super1, Super2]`, in bold for the open type. Each one has children
  for the anonymous diagram types of its local blocks
  (`Block.anonymousDiagramType()`, labelled with the enclosing block name),
  recursively. Selecting a node opens it on the canvas.
* Context menu:
  * **New diagram…**: `CreateDiagramTypeCommand` appends an empty
    `diagramtype Name {}`. The name is checked with `isIdValid` and
    `!program.isTypeDeclared`.
  * **Rename diagram…** (named types only): `RenameDiagramTypeCommand` calls
    `UserDefType.changeName` (`Rename.jrag`), which rewrites all `TypeUse`s.
    Simple-named blocks of that type are renamed `NewType_N`
    (`Block.updateTypeName`), and so is the block owning a wiring. All layout
    keys starting with `Old::` or `Old$` are renamed. **Not undoable.**
  * **Delete diagram…**: refused for the last named diagram type. Asks for
    confirmation and warns if `uses()` is non-empty, but **leaves dangling
    references**. Deleting an *anonymous* type replaces it with its first super
    type (`enclosingBlock().setType(getSuperType(0))`), which drops features and
    contents.
  * **Specialize diagram…** (§4.2).
  * **Diagram properties…** (§4.3).

---

## 5. Things the editor does outside the canvas

* **Problem markers.** `BloqqiEditor.updateErrors()` turns
  `CompilationUnit.errors()` (the collection attribute in
  `frontend/Errors.jrag` and the other `*Errors` aspects) into Eclipse
  `IMarker.PROBLEM` markers. The message is `"<DiagramType>: <message>"`, the
  location is `line:col`, and the severity is always error. **They are refreshed
  only when the editor opens and on save**, and deleted when it closes.
* **Live validation on the canvas** is limited to:
  * blocks: `isOnTypeCycle() || isDataflowCircular()` → red
  * connections: `Connection.hasErrors()` (type-kind errors,
    `!isTypeCompatible()`, unexpected source/target port) `||
    isOnDataflowCycle()` → red, width 2

  Other errors (unknown types, ambiguous accesses, duplicate names, interception
  errors, …) appear only as markers.
* **Port tooltips** (`name: Type`) and **connection feedback** (§3.2).
* **Preferences** (`preferences/*`): *Show types for blocks and variables*
  (default off), *Always ask for block name for new blocks* (on), *Show layout
  operations in context menu* (off), *Enable experimental features* (off;
  **unused**).
* **Missing diagram type** on open: the user is prompted to create one (§1).
* **Dirty tracking**: the command stack save point. *Save As* is not
  implemented.
* There is no text view, no simulation, and no code generation from the editor.

---

## 6. Persistence

### 6.1 Load

1. `BloqqiScanner` + `BloqqiParser` on the file contents → `CompilationUnit`.
   A parse exception is printed and `program` stays `null`, so the editor
   **cannot open files with unrecoverable syntax errors** and fails later. If
   Beaver recovers from an error, the editor opens the recovered tree.
2. `Program` = standard library + the unit.
3. For each top-level `DiagramType` with annotations, each
   `Annotation(VarUse, x, y, w, h)` whose `getVarUse().decl()` is a `Node` is
   stored as `coordinates[dt][node.accessString()] = (x, y, w, h)`. Annotation
   lookups go through `DiagramType.inheritedLookup` (`NameAnalysis.jrag`:
   `eq DiagramType.getAnnotation().lookup(name) = inheritedLookup(name)`), and
   `P::K` is parsed as a `TypedSimpleVarUse`. Annotations that do not resolve
   are dropped silently.

Annotation syntax, which follows the closing brace of a top-level diagram type:

```
diagramtype PTrack(Track: Bool, TrackValue: Real) extends P { ... }
annotation(
  P::K = (79, 74, 44, 20),
  SelReal_1 = (298, 10, 44, 46),
  Track = (213, 3, 68, 20)
)
```

### 6.2 Layout while editing

* Lookup for a node in type `dt` (`Coordinates.getRectangleNearestSuperType`)
  walks `dt.superTypesLinearizedReversed()` (most specific type first). It uses
  key `name` in the declaring type and `accessString` in the others, and takes
  the first hit. If the hit came from a super type, it is **copied** into
  `dt`'s map under `Super::name`. A subtype therefore inherits its super type's
  layout until the user moves something, and the inherited position is written
  into the subtype's annotations on the next save.
* If a type has **no** stored rectangles when it is shown, it is auto-laid-out
  with KLay (§6.4). This is not on the undo stack and marks nothing dirty.
* A node with no rectangle while others have one gets
  `RECTANGLE_NOT_FOUND = (10, 10, 60, 20)`. Such nodes pile up in the top-left
  corner. This happens for nodes added by text edits, and for parameters and
  connections created by wizards.
* Literals are never stored (§2.2). The connection router is never stored.

### 6.3 Save (`Utils.programToInputStream`)

1. For each **top-level** `DiagramType` in `cu.typeDecls()` with a non-empty
   layout map, build a fresh `Annotation` list. Each entry becomes a
   `SimpleVarUse` or `TypedSimpleVarUse` (split on `::`) and is kept only if
   `dt.inheritedLookup(...)` finds it. Then `dt.setAnnotations(list)`.
2. Write `cu.prettyPrint()` (`backend/PrettyPrint*.jadd`) over the whole file.

Consequences, confirmed with a round-trip probe on `examples/` (§6.5):

* The file is **regenerated, not patched**. Formatting is normalised: 2-space
  indentation, the `name: Type` form, one statement per line, and parameters
  as `(a: Int => b: Int)`.
* **Comments are lost**, because the scanner discards them (`Tank.dia`,
  `Tank2.dia` and `P_PFF_PGS_Regulator.dia` all lose theirs). Doc strings
  (`"""…"""` before a type) are kept.
* Name-analysis rewrites are written back. For example,
  `connect(Sub_1, Sub_2.in1)` becomes `connect(Sub_1.out, Sub_2.in1)` and
  `connect(GTZ_1, v41)` becomes `connect(GTZ_1.res, v41.open)`.
* **`package` and `import` declarations are dropped**, because
  `CompilationUnit.prettyPrint()` prints only `getDeclarations()`. This was read
  from the code; no example uses imports.
* **Layouts of anonymous diagram types are never saved**: `typeDecls()` holds
  only top-level declarations, and the grammar has no annotation slot for
  anonymous types. Each time an anonymous type is opened it is auto-laid-out
  again.
* Declaration order is preserved and new declarations are appended. Annotation
  entries are sorted by key, because `getAllRectangles` returns a `TreeMap`.
* A file the editor has saved is a fixpoint. `PLib.dia`, which was evidently
  written by the editor, round-trips byte-for-byte.

### 6.4 Automatic layout

* `AutoLayoutKlay` builds a KGraph (KIELER KLay 2015-06,
  `de.cau.cs.kieler.klay.layered.LayeredLayoutProvider`). It has one node per
  parameter, block and variable, one port per block port, and edges for
  `dt.connections()`. Options: direction RIGHT, spacing 36, port constraints
  `FIXED_POS` with the same port y-positions as the figures, and node width 60
  (+16 when the node has incoming literals, to leave room for the label).
* Uses: when a type with no layout is first shown, the *Auto layout* toolbar
  action, and inline/collapse (§3.7).
* `Utils.autoLayoutKeepDeclarationOrder` uses the compiler's own
  `AutoLayout.jrag` (`Block.autoLayoutX/Y`, based on `depth()` and
  `sinkPred()`), as do `AutoLayoutDraw2D` and `AutoLayoutKlayMain`. These are
  **unused** leftovers or experiments.

### 6.5 Check against `examples/`

I loaded every example the way the old editor does, dumped the derived
elements, pretty-printed, and re-parsed. I did this with both the editor's
bundled compiler jar and the current `bloqqi-compiler/ant-bin`; the results
were identical. The probe lives in the session scratchpad and is not committed.

Correction (milestone 1): the first probe ignored Beaver's error *recovery*,
which is only printed to stderr. The new server's loader records recoveries
as syntax errors, and that exposed two more broken files (`BloqqiMovie.dia`,
`SimpleAddDiagram.dia`). The table below includes them.

| Example | Parses | Errors | Exercises |
|---|---|---|---|
| `PLib.dia` | ✓ | 0 | inheritance, connection interceptions, `features` + `feature` uses, annotations including `P::x` keys; byte-identical round trip |
| `InlineExample.dia` | ✓ | 0 | `inline`, `redeclare`, nested anonymous types, wirings and wired blocks, connection and source interceptions, recommendations, `$` annotation keys |
| `vlhcc-large.dia` | ✓ | 0 | inheritance, interceptions, recommendations, wirings, annotations |
| `BloqqiMovie.dia` | ✓ (recovered) | 0 | as above, but its annotations use **old keys** `Gain:Out` (now `Gain::Out`); the parser only accepts them by recovering (`:` → `.`) |
| `P_PFF_PGS_Regulator.dia` | ✓ | 3 (`Mux3_1.k` not found, `Mux3` declared twice) | annotations; comments lost on save |
| `SimpleAddDiagram.dia` | ✓ (recovered) | 0 | starts with `@Application`; recovery inserts EOF, so the file **parses as empty**. The old editor would prompt for a new `Main` and overwrite the file on save. |
| `Tank.dia`, `Tank2.dia`, `TwoCounters.dia`, `Increament.dia`, `simple.dia`, `NestedVariableExample.dia` | ✓ | 0 | structs, external functions, no annotations (auto layout on open) |
| `Example-Motor.dia` | ✓ (recovered) | 20 | **obsolete syntax** (`variable Bool x;`), so name analysis fails throughout |
| `Example-Pregulator.dia` | ✗ "Cannot recover from the syntax error" | – | **obsolete syntax** (`variable Real Gain;`); the old editor cannot open it |

The probe matched §2.4 and §6.3. Examples:

* `PTrack` shows the two `InterceptConnection` halves.
* `InlineExample::Tank` shows five `InlinedBlock`s named `loop$…`; four of its
  `InlinedConnection`s are not deletable.
* The anonymous type of `Main.pid` in `PLib` is named `Main$pid` and has 8 in-ports.
* All inherited elements report `canDelete() == false`.

---

## 7. What depends on Eclipse/GEF, and what the new server can reuse

### 7.1 Reusable as-is (plain Java, in the compiler)

The JastAdd attributes and aspects listed below are the editor's real "back
end". They already live in `bloqqi-compiler` and have no Eclipse dependency.

| Concern | Compiler API |
|---|---|
| Parse / print | `BloqqiScanner`, `BloqqiParser`, `Program.loadStandardLibrary()`, `CompilationUnit.prettyPrint()`, `Program.isIdValid`, `Program.parseLiteral` |
| View model | `DiagramType.get{In,Out}Parameters/getBlocks/getVariables/getConnections()`, `literals()`, `Block.get{In,Out}Ports()`, `Anchor.{in,out}goingConnections()`, `Node.accessString()`, `isInherited()`, `isInlined()`, `inlinedBlock()`, `inlinedName()`, `canDelete()`, `isLocallyRedeclared()`, `isNameAmbiguous()`, `Connection.isIntercept()`, `InterceptConnection.getIsFirstConnection()`, `getDeclaredFlowDecl()`, `Modifiers.isOnSimpleNameForm()`, `Variable.prettyPrintKind()` |
| Validation | `CompilationUnit.errors()`, `Connection.hasErrors()`, `isOnDataflowCycle()`, `Block.isOnTypeCycle()`, `isDataflowCircular()`, `TypeDecl.compatibleWith()` |
| Edit operations | `Anchor.access()/canAccess()/canModifyToAccess()/modifyToAccess()` (`Access.jrag`); `DiagramType.addConnectionsParameters*`, `getUniqueName`, `addParameter`, `addConnection`, `Block.addAnonymousTypesForNestedBlocks` (`EditingOperations.jrag`); `UserDefType.changeName`, `VarDecl.changeName`, `Block.changeName`, `TypeDecl.uses()`, `VarDecl.uses()` (`Rename.jrag`); `extractsubTypeAsWiredBlock`, `isInnerParameterExposed`, `canExtractSubtypeAsBlock`, `isLocallyEmpty` (`RecommendationSupport.jrag`); `Modifiers.setModifier/addModifier/removeModifier` |
| Features / recommendations | `featureDecls()`, `featureSelection(...)` → `FeatureSelection`; `hasRecommendations()`, `specialize(...)` → `FeatureConfiguration` (`newAnonymousBlock`, `newDiagramType`, `getNewInParameters`) |
| Change notification | `ASTObservable`/`ASTObserver` (`Observable.jadd`). Optional: the server can just diff the derived view after `flushAllAttributes()`. |
| Simple layout | `AutoLayout.jrag` (`Block.autoLayoutX/Y`); unused by the old editor |

### 7.2 Plain logic trapped in Eclipse-typed classes (port, don't rewrite)

| Old code | Eclipse dependency | What to keep |
|---|---|---|
| `commands/*` (30 classes) | extend GEF `Command` (or `PostConditionCommand`, which extends it); several use `draw2d.geometry.Rectangle/Point` and the figure constants `BlockFigure.WIDTH/MIN_HEIGHT` and `PortPart.SIZE` | all the AST manipulation, undo bookkeeping (indices, tree copies), coordinate re-keying on rename, and connection cleanup. Swap in a 4-method `Command` interface and a plain rectangle record. |
| `Coordinates` | `draw2d.geometry.Rectangle` | the per-type key/rectangle map and nearest-supertype lookup with copy-down |
| `Utils.readProgram / readAnnotations / programToInputStream` | `IEditorInput`, `IFileEditorInput`, `Rectangle` | the load/save algorithm in §6 |
| `autolayout/AutoLayoutKlay` | KLay 2015 jar (KGraph is **EMF-based**, so it also needs `org.eclipse.emf.ecore`/`common` jars, which the plug-in got from the Eclipse platform); draw2d `Rectangle`; figure constants | the graph construction and options. Runs outside Eclipse with EMF on the classpath; `AutoLayoutKlayMain.main` does this. See Q8. |
| `tools/PortsFeedback` | GEF edit parts, SWT colours | the "valid target" rule: accessible ∧ type-compatible ∧ no incoming connection |
| `actions/*.calculateEnabled()` | GEF `SelectionAction` | the enablement predicates in §3.7 and §4.4 |
| `wizards/specialize/PageParameters.NewInParameter` | lives in an SWT page | default new-name rule (first segment + last segment) |
| `figures/*`, `editparts/*` constants | draw2d | geometry constants: block width 60, min height 20, port 6 px at `y=5+14i`, literal offset 12 px, parameter default 60×20 |

### 7.3 Eclipse/GEF only (replace with the web client)

`BloqqiEditor`, `BloqqiActionBarContributor`, `Activator`, `Actions`,
`ContextMenu`, `Palette`, `editparts/*`, `figures/*`, `policies/*`, `tools/*`
(the GEF tool state machines), `actions/*` (JFace actions), `wizards/*` (JFace
wizards and SWT dialogs), `outline/*` (GEF tree viewer), `preferences/*`
(Eclipse preference store), `Properties`, `plugin.xml`, `MANIFEST.MF`, and
`lib/bloqqi-editor-commands.jar` (GEF command stack, binary only). Guava
(`lib/guava-18.0.jar`) is on the classpath but is not referenced by any source
file.

---

## 8. Summary of model changes per user action

| Action | AST change (top-level unit unless noted) | Layout change |
|---|---|---|
| Create block | `+LocalBlock` (maybe with an anonymous type and features); maybe nested parameters and connections | `+rect` at the drop point |
| Create parameter | `+LocalIn/OutParameter` | `+rect` |
| Create variable | `+LocalVariable` | `+rect` |
| Create connection | `+FlowDecl Connection`; maybe the declared block is replaced by a rewritten copy (inlined access) | – |
| Create literal | `+FlowDecl Connection(Literal, …)` | – |
| Intercept / intercept source | `+FlowDecl ConnectionInterception / SourceInterception` | – |
| Delete block, parameter or variable | `−decl`, `−attached FlowDecls` | (stale key dropped on save) |
| Delete connection or literal | `−declared FlowDecl` | – |
| Move / resize | – | `rect := …` |
| Rename block, parameter or variable | name token and all uses; block drops `simple` | re-key in this type and its subtypes |
| Inline / collapse | toggle the `inline` modifier | rects for the new inlined nodes / the collapsed block |
| Change features / specialisation | the declared block is replaced (new anonymous type); dangling flow decls are removed | – |
| Diagram properties | `SuperTypeList`, `LocalIn/OutParameterList` replaced; affected flow decls removed | – |
| Extract subtype as recommendation | `+DiagramType` (with wiring), `+Recommendation` | – |
| New / rename / delete / specialise diagram type | `±DiagramType`; rename rewrites all `TypeUse`s and simple block names | rename re-keys; delete drops that type's map |
| Auto layout | – | whole type's map replaced |

---

## 9. Open questions

1. **Repository layout and target compiler.** The directories differ from the
   brief (`bloqqi-compiler/`, `editor/bloqqi-editor/`). The `/workspace` git
   repository also shows the editor's tracked files as deleted, because they
   now live under `editor/bloqqi-editor/` with their own `.git`.
   *Answered:* the new editor is its own repository in `/workspace/editor`,
   with the compiler (`408e388`) as the submodule `bloqqi-compiler/`; the old
   editor's clone stays in `bloqqi-editor/` as a git-ignored reference (see §0).
2. **Persistence strategy.** Keep "regenerate with `prettyPrint()`"? It loses
   comments and `import`/`package`, normalises formatting, and writes back
   rewritten connections. Or must the new editor preserve source text, for
   example by patching text through node positions? Do the files have
   hand-written comments people care about?
   *Partly answered:* layout edits patch only the type's `annotation(...)`
   block, located through AST source positions; the rest of the text is kept.
3. **Where layout lives.** Keep `annotation(...)` blocks in the `.dia` file, or
   use a sidecar file? How should layouts of anonymous types (never saved
   today), literal positions, and per-subtype positions of inherited elements
   be handled?
   *Answered (for top-level types):* `annotation(...)` blocks, written as the
   old editor saved them: the first move stores the type's whole current
   layout, entries sorted by key, inherited elements under `Super::name` in the
   subtype, literals not stored. Anonymous types are still open.
4. **Scope of a session.** One file per session as today, or a project with
   several files and working `import`s? Several types open at once?
5. **Which features to carry over.** Both specialisation mechanisms
   (`recommendation` wizards and `features` selection)? Extract subtype as
   recommendation? Multiple super types? Connection-router toggles and Print?
   The hidden `ChangeNameDisplayedAction` and the unused "experimental"
   preference look droppable.
6. **Permissive or strict connection creation.** The old editor lets users
   create type-incompatible or double-driven connections; feedback is only
   colour. Should the new editor refuse them?
   *Answered:* refuse them. Invalid targets are not offered while dragging
   (valid ones are highlighted), and the server refuses wrong direction,
   driven inputs, incompatible types, and edits that add errors (e.g. cycles).
7. **Live errors.** Today the marker list refreshes only on open and save, and
   the canvas shows only cycle and connection errors. Should every
   `errors()` entry be shown live and attached to its element?
8. **Auto-layout engine.** KLay 2015 is unmaintained and needs EMF. Should the
   server use ELK (its Java successor), or should the client lay out with
   `elkjs`? Must layouts be deterministic and reproducible on the server?
9. **Undo/redo model.** Should undo be a server-side command stack (as today,
   with diagram-type rename not undoable)? Is it per user or per file?
   *Answered:* per browser tab and per file, kept in the client (the server is
   stateless). Each entry is the file's text before and after an edit; undo
   and redo restore a text only if the file has not changed since, so they
   never overwrite edits made elsewhere. The history ends with the tab.
10. **Concurrency.** Single user per file, or several clients on one model?
    Should the server watch for external text edits and reload?
11. **Types in dialogs.** Parameters and variables are limited to `Int`, `Bool`
    and `Real` today. Should `String` and structs be added?
12. **Files with errors.** The old editor cannot open
    `Example-Pregulator.dia`, and silently works on a recovered tree for others.
    Should the new editor offer a text fallback? Should the two obsolete-syntax
    examples be migrated or deleted?
13. **Non-diagram declarations.** Functions, state machines, structs and
    external functions are palette-only today. Should the new editor display or
    edit them (for example as text)?
14. **Wired blocks.** Parameters and connections contributed by wired blocks
    and `wiring` declarations are drawn like ordinary ones. I did not establish
    how `isInherited()` classifies them visually. Should they get their own
    styling?
15. **Missing source.** The source of `PostConditionCommandStack` (in
    `bloqqi-editor-commands.jar`) is in neither repository. Does it matter, or
    is the `javap`-level description in §3.9 enough?
