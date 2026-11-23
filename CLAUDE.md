# Bloqqi web editor

## Architecture decisions
- The Bloqqi source text is the single source of truth. The diagram is
  computed from the JastAdd AST and attributes after every change.
- Diagram operations are applied as text edits to the source, followed by
  a reparse. No separate diagram model is stored.
- `/workspace/editor` is its own git repository (branch `main`). The compiler
  is the git submodule `bloqqi-compiler/` (github.com/bloqqi/bloqqi-compiler,
  pinned at `408e388`, the version the old editor used), used as a library;
  test files come from its `examples/`. Changing the compiler version is a
  deliberate commit of the submodule pointer.
- The old Eclipse editor is a separate, git-ignored clone in `bloqqi-editor/`
  (github.com/bloqqi/bloqqi-editor), kept only as a reference to check
  behaviour against; it is described in `docs/editor-spec.md`. It is not part
  of the build. Don't edit it.
- Server (`server/`): Java 21, Jetty 12 (core, no servlets). It serves the built
  client and a WebSocket at `/ws`. It has no state: every request reloads the
  file from the workspace directory.
- Client (`client/`): React + React Flow (`@xyflow/react`), built with Vite.
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
- Edits are checked by reparsing before anything is written; an edit that adds
  syntax errors or more semantic errors is refused. Files with syntax errors
  are not edited. Writes go through a temp file and an atomic move.
- Undo/redo lives in the browser tab (`client/src/history.ts`), per file; the
  server stays stateless. Every edit's answer carries `change: {before, after}`
  (the file's text); undo sends `restore` with `text = before`,
  `expected = after` (redo the other way round), and the server writes it only
  if the file still equals `expected` (`Workspace.replace`); otherwise it
  refuses and the client clears that file's history.
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
All commands run from `/workspace/editor`. Requires JDK 21, Ant, Node 20+.
After cloning: `git submodule update --init` (or clone with `--recursive`).
The compiler is the submodule `bloqqi-compiler`; the build runs
only its `gen` target and compiles it into `build/compiler` (with `release=8`,
since its own `ant jar` fails on JDK 21).
- Build: `ant build` (downloads pinned jars into `lib/`, builds compiler, server
  and client; `npm ci` runs only if `client/node_modules` is missing)
- Test (server, JUnit): `ant test`
- Test (browser, Playwright): `ant e2e` (builds first; starts the server on
  port 8091 with a fresh copy of the examples in `build/e2e-workspace`, plus
  `Drag.dia`, which only the drag tests edit). First time only: `cd client && npx playwright install chromium`
- Single JUnit class: `ant test-compile` then
  `java -cp "build/server/classes:build/server/test-classes:build/compiler/classes:lib/*:bloqqi-compiler/tools/beaver-rt.jar:bloqqi-compiler/tools/gson-2.6.2.jar" -Dbloqqi.examples=bloqqi-compiler/examples org.junit.runner.JUnitCore org.bloqqi.web.DiagramBuilderTest`
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
- **Don't run the compiler's own `ant build`/`ant jar`.** They fail on JDK 21
  (`java.lang.Compiler` was removed). Our `build.xml` runs only its `gen` target
  and compiles with `release=8`. Never edit files under the compiler checkout;
  `gen` writes only to its git-ignored `src/generated/`.
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
