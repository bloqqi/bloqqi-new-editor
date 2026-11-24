# Bloqqi web editor

A browser-based graphical editor for [Bloqqi](https://github.com/bloqqi/bloqqi-compiler),
a block-diagram language for control systems. It replaces the old Eclipse/GEF
editor.

The `.dia` source file is the single source of truth. A Java server parses it
with the Bloqqi compiler and sends the diagram to the browser. Every edit in
the diagram is a small change to the text, so comments and formatting are
kept, and edits that would introduce errors are refused.

In the browser you can:

- view diagram types with inheritance, interceptions and inlined blocks
- move nodes, or let the editor lay them out automatically
- connect and disconnect ports, and intercept inherited connections in
  subtypes
- add and delete blocks, literals, variables and parameters
- rename anything, with all uses in the file updated
- create, rename and delete diagram types and external functions, and change
  super types
- create, rename and delete files
- switch to the text view to read and edit the whole file, with problems
  shown as you type
- undo and redo every edit

Changes made to the file elsewhere, such as in a text editor or another tab,
appear immediately.

## Requirements

- JDK 21 or later
- [Apache Ant](https://ant.apache.org/)
- Node.js 20 or later
- Internet access for the first build, which downloads pinned Java libraries
  from Maven Central and npm packages

## Install

The Bloqqi compiler is a git submodule, so clone with `--recursive`:

```sh
git clone --recursive https://github.com/niklasfors/bloqqi-new-editor.git
cd bloqqi-new-editor
ant build
```

If you cloned without `--recursive`, run `git submodule update --init` first.

## Run

```sh
ant run
```

Then open <http://localhost:8080/?file=PLib.dia&type=PTrack>.

On its first run, the editor copies the compiler's example files into
`workspace/` and edits that copy. To use your own files or another port:

```sh
ant run -Dworkspace=/path/to/dia/files -Dport=9000
```

The editor shows and edits the `*.dia` files directly in that directory, and
writes changes to them as you edit.

## Develop

- `ant test` runs the server tests (JUnit).
- `ant e2e` runs the browser tests (Playwright). Before the first run, install
  the browser with `cd client && npx playwright install chromium`.
- For a client dev server with hot reload, run `ant run` in one terminal and
  `cd client && npm run dev` in another.
- `ant clean` removes build outputs.

`docs/editor-spec.md` describes the old editor and the design decisions.
`CLAUDE.md` has notes on the architecture and its pitfalls.
