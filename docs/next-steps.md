# Next steps

Where the editor stands and what could come next, as of 2026-11-24. The
features compared with the old editor are in
[feature-comparison.md](feature-comparison.md); the architecture, conventions
and pitfalls in [CLAUDE.md](../CLAUDE.md).

## State

- Nearly all of the old editor's features, plus a text view, a C view, live
  updates from other tabs and text editors, edits checked by reparsing before
  they are written, Back navigation, and Docker.
- Tests: ~140 JUnit tests (`ant test-server`), 28 Vitest unit tests
  (`ant test-client`; both with `ant test`) and 66 Playwright browser tests
  (`ant e2e`, Chromium only). Client code passes `ant lint`.
- The `recommendation` mechanism of the old editor is deliberately not carried
  over.

## Features

1. **Inline / Collapse** (old editor: block menu). Toggle a block's `inline`
   modifier and place the inner blocks that appear (the old editor ran its
   layout on the block's type, offset from the block, for inner blocks without
   a stored position). The largest remaining gap. It would also allow
   **connecting ports inside inlined blocks**, which is refused today; the old
   editor rewrote the declared block to make such a port nameable
   (`Port.modifyToAccess`, spec §3.2).
2. **Editing anonymous types.** `T$b` (the type of block `b: B { ... }` in `T`)
   can be opened, read-only (`DiagramBuilder.findShown`). Editing means edits
   that work on the statements inside the block's braces, and somewhere to
   keep its layout (the grammar has no `annotation(...)` for it; today it is
   laid out afresh with ELK each time).
3. Smaller items:
   - name a block when it is dropped (the old editor's optional name dialog)
     instead of always `Type_N`;
   - resize nodes;
   - "Show types" on blocks and variables (today only in tooltips);
   - print;
   - connections to struct fields (`a.status.level`);
   - show problems on the elements they concern, not only in the list.

## Code quality

1. **A contract test for the protocol.** `server/.../Protocol.java` and
   `client/src/protocol.ts` are kept in sync by hand; only the browser tests
   notice drift. Plan (decided, not built):
   - One sample per message type, both directions (~25), in
     `protocol/samples/<type>.ts`: a TypeScript file whose body is plain JSON
     with every field filled in (also the optional and `null` ones):
     `export default { "type": "diagram", ... } satisfies DiagramMessage;`
   - TypeScript: `satisfies` makes `tsc` (so `ant build`) reject missing or
     unknown fields; a typed map from every `ServerMessage['type']` and
     `ClientMessage['type']` to its sample makes a missing sample a compile
     error.
   - Java, one JUnit test: read the JSON between `export default` and
     `satisfies`. Server messages: build the same message with `Protocol`
     and compare the JSON trees (keys, kinds of values, nulls). Requests: parse
     each with `Protocol.parseRequest`; the record type must match and its
     components must cover the sample's keys exactly. Completeness: every
     permitted `Request` and every message `Protocol` writes has a sample.
   - Cost: ~25 small sample files, ~100 lines of JUnit, ~40 lines of
     TypeScript. Rejected: generating `protocol.ts` from the Java records (a
     generator and build step), a shared JSON Schema (a third definition and
     new dependencies).
2. **Split the browser tests** (`client/tests/diagram.spec.ts`, ~1,600 lines)
   into files by feature. Mind the files that only one test edits
   (`Drag.dia`, `Intercept.dia`, `Params.dia`, `Features.dia`, `Text.dia`,
   `Remeasure.dia`, made by the `e2e-workspace` target in `build.xml`) and
   the serial groups.
3. Possibly later: if the server grows a lot, split its package deliberately
   into model (loading, `SourceText`, `Diagram`, `DiagramBuilder`, `Layout`),
   edits (`*Edits`) and server (socket, protocol, workspace), making public
   only what crosses a boundary. Not worth it at the current size (~25
   classes).

## Outside the editor's code

- **The compiler's `--c` output does not compile with gcc 14**: the driver
  calls `usleep` without the declaration (a feature macro such as
  `_DEFAULT_SOURCE`, or `<unistd.h>` with it). A fix in the compiler
  repository (`src/resource/c/CCodePost.c` / `CCodePre.c`); `--c=minimal`
  compiles.
- **Browser tests in WebKit** (Safari's engine) too: Playwright's WebKit is
  downloaded, but needs system libraries (`npx playwright install-deps
  webkit`) in the dev environment. Safari-only problems so far: text
  selection while dragging a connection (fixed, checked by hand).
- **Try it on real diagrams.** The tests use the compiler's examples; real
  files would show which features matter most.
- Known, accepted: `npm audit` reports moderate advisories in `minimatch`
  inside the TypeScript linter (development only).
