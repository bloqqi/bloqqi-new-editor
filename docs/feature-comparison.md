# Feature comparison: old (Eclipse) and new (web) editor

The old editor is described in [editor-spec.md](editor-spec.md); the new
one in [README.md](../README.md) and [CLAUDE.md](../CLAUDE.md). State as of
2026-11-24; keep this table up to date when features are added.

✅ supported · ➖ partly · ❌ not supported

## Viewing

| Feature | Old (Eclipse) | New (web) |
|---|---|---|
| Diagram types with inheritance, interceptions, inlined blocks | ✅ | ✅ |
| Choosing the shown type | Outline tree | Toolbar drop-down (sorted) |
| Opening anonymous types (blocks with features) | ✅ via outline | ✅ "Open Main$pid" on the block; read-only, laid out afresh |
| Open Declaration (block → its type) | ✅ (no way back) | ✅ with Back (toolbar ← and the browser's Back/Forward) |
| Zoom, fit | ✅ zoom combo | ✅ zoom/fit buttons |
| "Show types" on blocks and variables | ✅ preference | ➖ tooltip only |
| Connection router (straight/Manhattan) | ➖ hidden preference | ❌ always orthogonal |
| Print | ✅ | ❌ |
| Text view of the file | ❌ | ✅ with live checking; layout blocks greyed and folded |
| Generated C code | ❌ | ✅ C view, as the compiler's `--c=minimal` / `--c`, for `Main` |

## Editing the diagram

| Feature | Old | New |
|---|---|---|
| Move nodes | ✅ | ✅ kept inside the drawing area |
| Resize nodes | ✅ | ❌ |
| Auto layout | ✅ KLay | ✅ ELK |
| Create connection | ✅ tool; invalid ones only coloured red | ✅ drag; invalid ones refused |
| Delete (blocks, connections, literals, parameters, variables) | ✅ | ✅ also via × button |
| Select inherited connections | ❌ | ✅ highlight only |
| Literals | ✅ click port, dialog | ✅ drag onto port, form |
| Variables | ✅ state/input/output | ✅ var/input/output |
| Parameters (add/delete) | ✅ | ✅ |
| Reorder parameters | ✅ properties wizard | ✅ "Parameters…" form |
| Blocks from palette | ✅ | ✅ |
| Name a block on drop | ✅ dialog (preference) | ❌ always `Type_N`; rename afterwards |
| Rename (block, parameter, variable) | ✅ | ✅ with use recount |
| Intercept inherited connection (also at the source) | ✅ | ✅ same click interaction |
| Inline / Collapse a block | ✅ | ❌ |
| Connect ports inside inlined blocks | ✅ (rewrites the block) | ❌ refused |
| Feature selection (`features`) | ✅ wizard, also asked on drop | ✅ "Features…" form, also for plain blocks; also asked on drop |
| Specialisation from recommendations | ✅ wizards | ❌ not carried over (decided) |
| Extract subtype as recommendation | ✅ | ❌ not carried over (decided) |

## Types and files

| Feature | Old | New |
|---|---|---|
| New diagram type | ✅ | ✅ opens it |
| New external function | ❌ | ✅ |
| New subtype | ➖ only via "Specialize diagram…" | ✅ |
| Rename type | ✅ not undoable | ✅ undoable |
| Delete type | ➖ leaves dangling references | ✅ refused while used |
| Super types (ordered) | ✅ properties wizard | ✅ "Super types…" form |
| Create / rename / delete files | ➖ via Eclipse | ✅ File ▾ |

## Behaviour

| Feature | Old | New |
|---|---|---|
| Undo / redo | ✅ | ✅ per file, in the browser tab |
| Edits that add errors | ➖ allowed | ✅ refused |
| Problems | ➖ markers, updated on open and save | ✅ live list; inline in the text view |
| Errors shown on the canvas | ➖ cycles and bad connections only | ➖ the same |
| Changes from other editors or tabs | ❌ | ✅ pushed; conflicts detected |
| Files with syntax errors | ➖ | ✅ read-only diagram, editable text |
| Formatting and comments kept on save | ❌ file is pretty-printed | ✅ only the changed text is replaced |
| Runs in | Eclipse | Browser; `ant run` or Docker |

## Main gaps in the new editor

Inline/collapse, editing anonymous types (they are shown read-only), and some
smaller items: naming a block on drop, resizing nodes, and printing. (The `recommendation` mechanism is deliberately not carried over.)
