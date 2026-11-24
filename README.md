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
- see the C code the Bloqqi compiler generates for the file (C view)
- undo and redo every edit

Changes made to the file elsewhere, such as in a text editor or another tab,
appear immediately.

[docs/feature-comparison.md](docs/feature-comparison.md) compares the
features with those of the old editor, including what is still missing.

## Requirements

(Or only Docker: see [Run with Docker](#run-with-docker).)

- JDK 21 or later
- [Apache Ant](https://ant.apache.org/)
- Node.js 20 or later
- Internet access for the first build, which downloads pinned Java libraries
  from Maven Central and npm packages

## Install

The Bloqqi compiler is a git submodule, so clone with `--recursive`:

```sh
git clone --recursive https://github.com/bloqqi/bloqqi-new-editor.git
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

## Run with Docker

Only git and Docker are needed. Build the image from a clone made with
`--recursive`:

```sh
git clone --recursive https://github.com/bloqqi/bloqqi-new-editor.git
cd bloqqi-new-editor
docker build -t bloqqi-editor .
```

The editor in the container works on the `.dia` files in its `/workspace`
folder, so mount a folder there. Here it is the clone's `workspace/` folder
(the one `ant run` uses; git ignores it). Create it first, so that it belongs
to you rather than to Docker:

```sh
mkdir -p workspace
docker run --rm -p 127.0.0.1:8080:8080 \
  -v "$PWD/workspace:/workspace" --user "$(id -u):$(id -g)" bloqqi-editor
```

Then open <http://localhost:8080/> on the host. The editor reads and writes
the files in that folder directly; an empty folder is fine (File ▾ → New
file). Any other folder works too: `-v /path/to/dia/files:/workspace`.
`--user` makes the editor write files as you, not as the container's user.
The editor has no login, so `127.0.0.1:` makes it reachable only from the
host itself. Use `-p 127.0.0.1:9000:8080` for another port.

To start with the compiler's example files, copy them into the folder first:

```sh
cp bloqqi-compiler/examples/*.dia workspace/
```

The image contains the built editor, so to update it, rebuild it and start a
new container (the files in `workspace/` are not affected):

```sh
git pull && git submodule update --init
docker build -t bloqqi-editor .
```

## Develop

- `ant test` runs the unit tests: the server's (JUnit) and the client's (Vitest).
- `ant lint` checks the client's code (ESLint, and Prettier's formatting);
  `cd client && npm run format` formats it.
- `ant e2e` runs the browser tests (Playwright). Before the first run, install
  the browser with `cd client && npx playwright install chromium`.
- For a client dev server with hot reload, run `ant run` in one terminal and
  `cd client && npm run dev` in another.
- `ant clean` removes build outputs.

`docs/editor-spec.md` describes the old editor and the design decisions;
`docs/feature-comparison.md` compares its features with the new editor's, and
`docs/next-steps.md` lists what could come next.
`CLAUDE.md` has notes on the architecture and its pitfalls.

## License

The Bloqqi web editor is covered by the modified BSD License, as the Bloqqi
compiler is. For the full license text see the LICENSE file.
