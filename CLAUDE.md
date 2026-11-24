# Bloqqi web editor

## Architecture decisions
- The Bloqqi source text is the single source of truth. The diagram is
  computed from the JastAdd AST and attributes after every change.
- Diagram operations are applied as text edits to the source, followed by
  a reparse. No separate diagram model is stored.
- `/workspace/editor` is its own git repository (branch `main`). The compiler
  is the git submodule `bloqqi-compiler/` (github.com/bloqqi/bloqqi-compiler,
  pinned at `5ed56ff`, its latest commit as of 2026-11-23), used as a library;
  test files come from its `examples/`. Changing the compiler version is a
  deliberate commit of the submodule pointer.
- The old Eclipse editor is a separate, git-ignored clone in `bloqqi-editor/`
  (github.com/bloqqi/bloqqi-editor), kept only as a reference to check
  behaviour against; it is described in `docs/editor-spec.md`. It is not part
  of the build. Don't edit it. `docs/feature-comparison.md` compares its
  features with the new editor's; update it when a feature is added.
- Server (`server/`): Java 21, Jetty 12 (core, no servlets). It serves the built
  client and a WebSocket at `/ws`. Every request reloads the file from the
  workspace directory; the server keeps no model. Each socket only remembers
  what its client shows (file, type, and the text it was built from):
  `WorkspaceWatcher` reports changed `*.dia` files, and a socket whose file now
  has other text pushes an `update` (so other tabs and text-editor changes show
  up; the socket's own edits are not echoed). New/deleted files push `files`;
  a deleted shown file a `notice`. Pushed messages are never answers, so the
  client's request queue ignores them; an `update` clears that file's undo
  history.
- Client (`client/`): React + React Flow (`@xyflow/react`), built with Vite.
  `App.tsx` only composes: `useServer` (WebSocket, request queue, shown
  diagram, undo/redo), `useDiagramFlow` (React Flow nodes/edges, selection,
  dragging, connecting, deleting, adding blocks; helpers in `flow.ts`),
  `useDialogs` + `Dialogs.tsx` (the open menu and forms and what submitting
  them sends), `menus.ts` (menu contents), `Toolbar.tsx`, `Palette.tsx`,
  `Canvas.tsx` (React Flow setup, fit, drop), `nodes.tsx`/`FlowEdge.tsx`
  (drawing), `shortcuts.ts`.
- Protocol: JSON over the WebSocket, defined in `server/.../Protocol.java` and
  mirrored in `client/src/protocol.ts` (keep the two in sync). Node IDs are
  `Node.accessString()` (`name`, `Super::name`, `outer$inner`).
- Build: plain Ant (`build.xml`), with Java dependencies fetched from Maven
  Central and verified by SHA-1. No Maven or Gradle.
- Layout: ELK (layered, fixed port order, orthogonal edges). Stored positions
  from the file's `annotation(...)` blocks take precedence; ELK is used when a
  diagram type has none. Moving nodes writes the type's `annotation(...)` block
  (`DiagramEdits.moveNodes`): the first move stores the whole current layout of
  the type, inherited nodes get `Super::name` entries in the subtype, literals
  are never stored. Only that block is spliced into the text (via AST source
  positions, `SourceText`); comments and formatting elsewhere are kept.
- Connections (`DiagramEdits.connect` / `deleteConnections`): dragging from an
  output handle to an input handle appends `connect(a, b);` to the type's body
  (indented like the last statement; braces found with the scanner's tokens).
  Invalid connections are refused, not just coloured as in the old editor:
  wrong direction, input already driven, incompatible types, not nameable
  (`Anchor.canAccess()`: ports inside inlined blocks), or new errors such as a
  dataflow cycle. Deleting removes the declaring statement (the whole
  `intercept` for an interception half) and its line. Not supported yet:
  literals, struct-field connections (`a.status.level`), inlined-block ports.
- Blocks (`DiagramEdits.addBlock` / `delete`): dragging a palette type onto
  the canvas inserts `Type_N;` (first free N) after the last variable/block
  declaration (the grammar puts blocks before connections) and stores the
  whole layout with the new block at the drop point. Deleting a block removes
  its declaration, the statements of all connections on its ports and its
  annotation entry, in one edit; if anything else still uses it (e.g. a
  subtype), the reparse check refuses. The palette follows the old editor:
  the file's types except the shown one and abstract ones, then the standard
  library. Not supported yet: naming on drop, the feature/specialisation
  wizards.
- Literals, variables, parameters (palette "Add" section; a small form opens
  at the drop): `addLiteral` appends `connect(<value>, <target>);` (one token,
  `Program.parseLiteral`); `addVariable` inserts `<kind> <name>: <type>;`
  after the last variable; `addParameter` edits the header (keeping the
  file's `a: Int` / `Int a` order; parentheses added if missing). Names are
  checked with `Program.isIdValid` and `lookup`; types are `Int`/`Bool`/`Real`
  as in the old editor. Deleting parameters rewrites the text between the
  header's parentheses from the remaining parameters' own text. Deleting a
  parameter or variable that is used elsewhere (e.g. `acc1.in` in another
  type) is refused by the reparse check.
  `setParameters` (canvas or parameter menu "Parameters…", `ParametersForm`)
  rewrites the header with the type's own parameters in a new order, from
  their own text; ones left out are deleted as above. Inherited parameters
  come first (super types' order) and are only listed.
- Rename (`DiagramEdits.rename`): the declaration and every use in the file
  (`x`, `x.in1`, `P::x`, annotation keys, and port uses `blk.p` for a
  parameter) are found by walking all `VarUse`s and following `decl()`
  through inheritance wrappers (and from a port to its parameter), then
  replacing the name in each use's text; `Add_1;` becomes `newName: Add;`.
  Besides the reparse check, the uses are counted again after the rename:
  if the new name captures or loses a use (e.g. a subtype has its own block
  with that name), the rename is refused. Also `setLiteral` (change a
  literal's text) and `autoLayout` (ELK on the whole type, stored).
- New types (toolbar "New ▾"): `addDiagramType` appends `diagramtype Name {}`
  at the end of the file and the answer is that type's diagram (the view
  switches); `addExternalFunction` inserts `external Name(a: Int => r: Bool);`
  after the file's last external function (or at the end), and the answer is
  the shown type's diagram (an external function has no diagram). Type names
  must be free in `Program.lookupType` (file, standard library, built-ins).
- Type management (palette right-click, canvas menu): `renameType` rewrites
  the declaration, every `TypeUse` written with the name (`x: T`, `extends T`,
  `T::x`, annotation keys) and simple-named blocks `T_1` -> `U_1` with their
  uses, then recounts the uses; `deleteType` removes the whole declaration
  (annotations, wiring) and is refused while anything uses the type;
  `addSubtype` appends `diagramtype X extends T {}`; `setSuperTypes` rewrites
  the `extends` clause (between the parameters and `{`).
- Files (toolbar "File ▾"): `createFile` (empty, or with a first empty
  diagram type), `renameFile`, `deleteFile` (asks first; not the last file).
  These are `Workspace` operations, handled before a request's file is loaded;
  they are not on the undo history. New file names: letters, digits, space,
  `_`, `-`, `.`, ending with `.dia`.
- Interception (right-click an inherited connection, spec §3.7):
  `DiagramEdits.intercept` appends `intercept X with T, S;` (X the
  connection's target) or `intercept source X with T, S;` (X its source); T a
  free input, S an output. The compiler matches X by its fully qualified name
  and checks the types. As in the old editor (InterceptTool), the client
  enters a mode: click a block input port, then a block output port (green
  ports fit, yellow ones do not; Esc cancels; `InterceptMode.tsx`). All
  connections are selectable, so inherited ones can be highlighted (and get
  their menu; React Flow gives unselectable edges `pointer-events: none`);
  Delete and the × only remove `deletable` ones.
- Client: lists of types and files are shown sorted by name (`names.ts`:
  case-insensitive, numbers by value); the server sends them in file order.
  A type's super types keep their written order, since it matters.
- Client: right-click menus (`ContextMenu.tsx`) for nodes, connections and
  the canvas; double-click or F2 opens Rename/Change value (`PromptForm.tsx`).
- Edits are checked by reparsing before anything is written; an edit that adds
  syntax errors or more semantic errors is refused. Files with syntax errors
  are not edited. Writes go through a temp file and an atomic move.
- Undo/redo lives in the browser tab (`client/src/history.ts`), per file; the
  server stays stateless. Every edit's answer carries `change: {before, after}`
  (the file's text); undo sends `restore` with `text = before`,
  `expected = after` (redo the other way round), and the server writes it only
  if the file still equals `expected` (`Workspace.replace`); otherwise it
  refuses and the client clears that file's history.
- Text view (toolbar "Diagram | Text", `?view=text`; `TextView.tsx`, loaded
  lazily with CodeMirror 6, pinned `@codemirror/*`; highlighting in
  `bloqqiLanguage.ts` from the scanner's keywords; the contents of
  `annotation(...)` blocks are grey, and the blocks are folded whenever a text
  is loaded, `annotationFolding.ts`): the whole file, saved
  explicitly (⌘S/Ctrl+S or Save). Every diagram message carries the file's
  `text`. Typing sends `checkText` (debounced; answered with `problems`,
  nothing written; RequestKind `check`, not counted as pending). Save sends
  `setText {text, expected}` (expected = the text the editor is based on):
  written only if the file is unchanged, but *not* checked, since a text may
  be saved with errors (the diagram is then read-only); it is one undo step.
  A new text from the server replaces the editor's if it has no unsaved
  changes, otherwise a banner offers Reload / Keep mine. While there are
  unsaved changes, File ▾, New ▾ and Undo/Redo are disabled, and leaving
  (Diagram, another file) asks Save / Discard / Cancel (`UnsavedTextForm`).
- Local and single-user for now. Compiler access is serialised by one global
  lock (see Gotchas).

## Conventions
- Pin all dependency versions. For ELK APIs, check the pinned jars in `lib/`
  (`org.eclipse.elk.*-0.12.1.jar`, fetched by `ant deps`) rather than relying
  on memory, e.g.
  `javap -cp "lib/*" org.eclipse.elk.alg.layered.options.LayeredOptions`.
  The same goes for the Jetty jars.
- Every diagram operation gets a test: apply it to an example file, then
  check that the new text reparses and the AST is as expected.

## Commands
All commands run from `/workspace/editor`. Requires JDK 21 or later, Ant, Node 20+.
After cloning: `git submodule update --init` (or clone with `--recursive`).
The compiler is the submodule `bloqqi-compiler`; the build runs
only its `gen` target and compiles it, like the server, with `release=21` into
`build/compiler`. To update the compiler: `git -C bloqqi-compiler checkout <commit>`,
run `ant test e2e`, then commit the submodule pointer.
- Build: `ant build` (downloads pinned jars into `lib/`, builds compiler, server
  and client; `npm ci` runs only if `client/node_modules` is missing)
- Test (server, JUnit): `ant test`
- Test (browser, Playwright): `ant e2e` (builds first; starts the server on
  port 8091 with a fresh copy of the examples in `build/e2e-workspace`, plus
  `Drag.dia`, which only the drag tests edit). First time only: `cd client && npx playwright install chromium`
- Single JUnit class: `ant test-compile` then
  `java -cp "build/server/classes:build/server/test-classes:build/compiler/classes:lib/*:bloqqi-compiler/tools/beaver-rt.jar:bloqqi-compiler/tools/gson-2.6.2.jar" -Dbloqqi.examples=bloqqi-compiler/examples org.junit.runner.JUnitCore org.bloqqi.web.DiagramBuilderTest`
- Docker: `docker build -t bloqqi-editor .`, then `mkdir -p workspace` and
  `docker run --rm -p 127.0.0.1:8080:8080 -v "$PWD/workspace:/workspace"
  --user "$(id -u):$(id -g)" bloqqi-editor` (README). The `Dockerfile` builds the server with Ant and the
  client with npm in separate stages (base images and Ant pinned); the runtime
  is a JRE with the classes, `lib/*.jar` and `client/dist`. There is no Docker
  in this dev environment; the image was tried on the user's machine
  (2026-11-24) and works.
- Run: `ant run` → http://localhost:8080/?file=PLib.dia&type=PTrack
  (options: `-Dport=...`, `-Dworkspace=<dir of .dia files>`). The default
  workspace is `workspace/` (git-ignored, kept by `ant clean`), created on first
  run as a copy of the compiler's `examples/`. Delete it to start over.
- Client dev server with hot reload: `ant run` in one terminal, then
  `cd client && npm run dev` (proxies `/ws` to port 8080)
- Clean: `ant clean` (keeps `lib/` and `node_modules/`)

## Gotchas
- **The compiler is not thread-safe, even across unrelated ASTs.** The generated
  `ASTNode` keeps attribute-evaluation state in a static field (`ASTNode.state`).
  Hold `BloqqiLoader.COMPILER_LOCK` around all parsing and attribute evaluation;
  `WebServerTest.concurrentClientsAreServed` catches regressions.
- **Build the compiler only through our `build.xml`**, which runs just its
  `gen` target (and deletes the old `src/generated/` first) and compiles the
  sources itself; its own `build`/`jar` targets are not needed (before
  `5c8e055` they failed on JDK 21). Never edit files under the compiler
  checkout; `gen` writes only to its git-ignored `src/generated/`.
- **Parse through `BloqqiLoader`, not `BloqqiParser` directly.** Beaver recovers
  from syntax errors and only prints them to stderr; a "successful" parse can
  silently drop code (`SimpleAddDiagram.dia` parses as empty). `BloqqiLoader`
  records recoveries as syntax errors.
- **The editor writes the files it shows.** Never point a server (or a test
  that edits) at the compiler's `examples/`; use `workspace/`, a temp copy
  (`WebServerTest`), or `build/e2e-workspace`.
- **AST pitfalls for text edits.** Use `getStartLine()/getStartColumn()` (and
  `End…`), not the packed `getStart()/getEnd()`; `List.getIndexOfChild` can
  return an index for a node that is not its child (compare `getParent()`);
  the compiler copies statements from `wiring` declarations into the types
  that use them, *with their original positions*, so check that a statement's
  position lies inside the type before editing it (`DiagramBuilder.isDeclaredIn`).
- **Saving by rename is not atomic on every file system.** On Docker
  Desktop's folders shared from macOS (e.g. `/workspace` here), another process
  can find the file missing while `Workspace.write` renames the temporary file
  onto it (measured: ~20% of concurrent reads). `Workspace` reads therefore
  retry briefly, and the browser tests read `Drag.dia` through `readDrag()`.
  Also, the JDK's `WatchService` polls on macOS, so changes there take seconds.
- **Broken examples:** five files in `examples/` don't load cleanly and are
  excluded from `ExamplesLoadTest`; the list and reasons are in
  `server/src/test/java/org/bloqqi/web/Examples.java`.
- **ELK outside Eclipse:** call `LayoutMetaDataService` registration first (see
  `Layout.ElkSetup`), and `org.eclipse.xtext.xbase.lib` must be on the classpath
  even though ELK's POMs don't declare it.
- **Jetty WebSocket listeners must be public classes** (Jetty calls them through
  method handles); otherwise the handshake fails.
- **JSON uses the compiler's Gson 2.6.2** (it's bundled with the compiler, so
  don't add another version). It has no record support, so messages are built
  explicitly in `Protocol`; nulls are serialised explicitly.
- **Ant's `javac` only compares timestamps**, so the `server` and
  `test-compile` targets always recompile from scratch to avoid stale classes.
