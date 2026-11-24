import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from '@playwright/test';

type Page = import('@playwright/test').Page;

/** Opens a page and waits until the diagram has been fitted (the view stops moving). */
async function goto(page: Page, url: string) {
  await page.goto(url);
  await fitted(page);
}

const fitted = (page: Page) => expect(page.locator('.canvas[data-fitted="true"]')).toBeVisible();

const node = (page: Page, id: string) => page.locator(`.react-flow__node[data-id="${id}"]`);
const blocks = (page: Page) => page.locator('.react-flow__node-block');
const handle = (page: Page, nodeId: string, handleId: string) =>
  node(page, nodeId).locator(`.react-flow__handle[data-handleid="${handleId}"]`);
const edge = (page: Page, id: string) => page.locator(`.react-flow__edge[data-id="${id}"]`);

test('shows one node per block, including inherited blocks', async ({ page }) => {
  await goto(page, '/?file=PLib.dia&type=PTrack');

  await expect(blocks(page)).toHaveCount(3);
  await expect(node(page, 'P::e')).toHaveText('e');
  await expect(node(page, 'P::PPart')).toHaveText('PPart');
  // Simple-named block (SelReal_1) shows its type name, as in the old editor.
  await expect(node(page, 'SelReal_1')).toHaveText('SelReal');

  await expect(node(page, 'P::e').locator('.block')).toHaveClass(/block--inherited/);
  await expect(node(page, 'SelReal_1').locator('.block')).not.toHaveClass(/block--inherited/);

  await expect(page.getByTestId('file-select')).toHaveValue('PLib.dia');
  await expect(page.getByTestId('type-select')).toHaveValue('PTrack');
  await expect(page.getByTestId('problems')).toHaveCount(0);
});

test('switches file and lays out a file without annotations', async ({ page }) => {
  await goto(page, '/?file=PLib.dia');
  await expect(node(page, 'e')).toBeVisible();

  await page.getByTestId('file-select').selectOption('TwoCounters.dia');
  await fitted(page);

  await expect(blocks(page)).toHaveCount(4);
  for (const id of ['acc1', 'acc2', 'print1', 'print2']) {
    await expect(node(page, id)).toHaveText(id);
  }
  // Auto layout (ELK) runs left to right along acc -> print.
  const acc1 = await node(page, 'acc1').boundingBox();
  const print1 = await node(page, 'print1').boundingBox();
  expect(acc1!.x).toBeLessThan(print1!.x);
  await expect(page).toHaveURL(/file=TwoCounters\.dia&type=Main/);
});

test('switches diagram type within a file', async ({ page }) => {
  await goto(page, '/?file=InlineExample.dia&type=Tank');
  await expect(node(page, 'loop$master$controller')).toHaveText('controller');
  await expect(node(page, 'loop$master$controller').locator('.block')).toHaveClass(/block--inlined/);

  await page.getByTestId('type-select').selectOption('Controller');
  await expect(blocks(page)).toHaveCount(1);
  await expect(node(page, 'sub')).toHaveText('sub');
});

test('the page has an icon', async ({ page }) => {
  await page.goto('/');
  const href = await page.locator('link[rel="icon"]').getAttribute('href');
  expect(href).toBe('/favicon.svg');
  const response = await page.request.get(href!);
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toContain('image/svg+xml');
});

test('reports problems for a file that does not parse', async ({ page }) => {
  await page.goto('/?file=Example-Pregulator.dia'); // no nodes, so never fitted
  await expect(page.getByText('Example-Pregulator.dia could not be parsed.')).toBeVisible();
  await expect(page.getByTestId('problems')).toBeVisible();
  await expect(page.locator('.react-flow__node')).toHaveCount(0);
});

test('shows parameters, variables, ports and connections', async ({ page }) => {
  await goto(page, '/?file=TwoCounters.dia&type=Acc');

  await expect(node(page, 'in')).toHaveText('in');
  await expect(node(page, 'in').locator('.parameter')).toHaveClass(/parameter--in/);
  await expect(node(page, 'out').locator('.parameter')).toHaveClass(/parameter--out/);
  await expect(node(page, 'state')).toHaveText('var state');

  // Ports: two inputs and one output on the Add block, with name: type tooltips.
  const add = node(page, 'add');
  await expect(add.locator('.port--in')).toHaveCount(2);
  await expect(add.locator('.port--out')).toHaveCount(1);
  await expect(add.locator('.port--in').first()).toHaveAttribute('title', 'in1: Int');

  await expect(page.locator('.react-flow__edge')).toHaveCount(4);
  // (Not toBeVisible: a straight horizontal line has an empty bounding box.)
  const inToAdd = edge(page, 'in.out->add.in:in1');
  await expect(inToAdd.locator('path.flow')).toHaveCount(1);

  // The connection ends at the in1 port.
  const port = await add.locator('.port--in').first().boundingBox();
  const path = await inToAdd.locator('path.flow').boundingBox();
  expect(Math.abs(path!.x + path!.width - port!.x)).toBeLessThan(2);
});

test('shows literals and interceptions', async ({ page }) => {
  await goto(page, '/?file=TwoCounters.dia&type=Main');
  await expect(node(page, '#literal:acc1.in:in')).toHaveText('1');
  await expect(edge(page, '#literal:acc1.in:in.out->acc1.in:in')).toHaveCount(1);
  const literal = await node(page, '#literal:acc1.in:in').boundingBox();
  const acc1 = await node(page, 'acc1').boundingBox();
  expect(literal!.x + literal!.width).toBeLessThan(acc1!.x);

  await page.getByTestId('file-select').selectOption('PLib.dia');
  await page.getByTestId('type-select').selectOption('PTrack');
  await fitted(page);
  await expect(page.locator('.react-flow__edge')).toHaveCount(8);
  await expect(edge(page, 'P::e.out:out->P::PPart.in:in1').locator('path.flow')).toHaveClass(/flow--inherited/);
  // intercept Out with SelReal_1.in2, SelReal_1.out: each half is split into
  // a dashed (inherited) and a solid part with a marker in the middle.
  const first = edge(page, 'P::PPart.out:out->SelReal_1.in:in2');
  await expect(first.locator('path.flow')).toHaveCount(2);
  await expect(first.locator('path.flow--inherited')).toHaveCount(1);
  await expect(first.locator('.flow__marker')).toHaveCount(1);
});

test('reconnects and reopens the diagram when the connection drops', async ({ page }) => {
  // Route the first connection through Playwright so the test can drop it;
  // later connections go straight to the server.
  let connections = 0;
  let dropFirst: (() => Promise<void>) | undefined;
  await page.routeWebSocket('/ws', (ws) => {
    connections++;
    const server = ws.connectToServer();
    if (connections === 1) dropFirst = () => ws.close({ code: 1001, reason: 'test' });
    void server;
  });

  await goto(page, '/?file=TwoCounters.dia&type=Acc');
  await expect(page.getByTestId('status')).toHaveText('connected');
  await expect(node(page, 'add')).toBeVisible();

  await dropFirst!();
  await expect(page.getByTestId('status')).toHaveText('connected');
  expect(connections).toBe(2);
  // Reopened the same diagram over the new connection.
  await expect(page.getByTestId('type-select')).toHaveValue('Acc');
  await expect(node(page, 'add')).toBeVisible();
});

/**
 * A file's text in the e2e workspace. Retries briefly when the file seems to
 * be missing: the server saves by renaming a temporary file onto it, which is
 * not atomic for other processes on some shared folders (e.g. Docker Desktop
 * on macOS).
 */
function readWorkspaceFile(name: string): string {
  const path = resolve('..', 'build', 'e2e-workspace', name);
  for (let attempt = 1; ; attempt++) {
    try {
      return readFileSync(path, 'utf8');
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code !== 'ENOENT' || attempt >= 50) throw e;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 20);
    }
  }
}

/** Drag.dia's text (see readWorkspaceFile). */
const dragFile = resolve('..', 'build', 'e2e-workspace', 'Drag.dia');
const readDrag = () => readWorkspaceFile('Drag.dia');

/** Drags a node by (dx, dy) screen pixels with the mouse. */
async function drag(page: Page, id: string, dx: number, dy: number) {
  const box = (await node(page, id).boundingBox())!;
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + dx / 2, y + dy / 2, { steps: 5 });
  await page.mouse.move(x + dx, y + dy, { steps: 5 });
  await page.mouse.up();
}

// Drag.dia is a copy of TwoCounters.dia that only these tests edit (see the
// e2e-workspace target in build.xml).
test.describe.serial('moving nodes', () => {

  test('dragging a block stores its position in the file', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await expect(node(page, 'add')).toBeVisible();
    expect(readDrag()).not.toContain('annotation');
    const outBefore = (await node(page, 'out').boundingBox())!;

    await drag(page, 'add', 40, 120);

    // The server writes the whole layout of Acc (it was auto-laid-out) to the file...
    await expect.poll(() => readDrag()).toMatch(/annotation\(\n {2}add = \(/);
    const text = readDrag();
    for (const id of ['in', 'out', 'state']) expect(text).toContain(`  ${id} = (`);
    // ...and the other nodes stay where they were.
    expect((await node(page, 'out').boundingBox())!.x).toBeCloseTo(outBefore.x, 0);

    // After a reload the block is still below the out-parameter.
    await page.reload();
    await fitted(page);
    await expect(node(page, 'add')).toBeVisible();
    const add = (await node(page, 'add').boundingBox())!;
    const out = (await node(page, 'out').boundingBox())!;
    expect(add.y).toBeGreaterThan(out.y + out.height);
  });

  test('literals follow the block they feed', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    const literal = node(page, '#literal:acc1.in:in');
    await expect(literal).toHaveText('1');
    const gap = async () =>
      (await node(page, 'acc1').boundingBox())!.x - (await literal.boundingBox())!.x;
    const gapBefore = await gap();

    await drag(page, 'acc1', 150, 80);

    await expect.poll(() => readDrag()).toContain('  acc1 = (');
    expect(await gap()).toBeCloseTo(gapBefore, 0);
    // Literals are not stored.
    expect(readDrag()).not.toContain('literal');
  });

  test('nodes stop at the top and left edge of the diagram', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await expect(node(page, 'in')).toBeVisible();
    // Drag to the canvas' top-left corner (the mouse can't leave the window).
    const pane = (await page.locator('.react-flow__pane').boundingBox())!;
    const start = (await node(page, 'in').boundingBox())!;
    await drag(page, 'in', pane.x + 2 - (start.x + start.width / 2), pane.y + 2 - (start.y + start.height / 2));

    await expect.poll(() => readDrag()).toContain('  in = (0, 0, ');
    await expect(page.getByRole('alert')).toHaveCount(0);

    // The node sits in the corner of the shaded boundary.
    const area = (await page.getByTestId('allowed-area').boundingBox())!;
    const box = (await node(page, 'in').boundingBox())!;
    expect(Math.abs(box.x - area.x)).toBeLessThan(2);
    expect(Math.abs(box.y - area.y)).toBeLessThan(2);
  });

  test('a block fed by a literal stops where its literal reaches the edge', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    const pane = (await page.locator('.react-flow__pane').boundingBox())!;
    const start = (await node(page, 'acc2').boundingBox())!;
    await drag(page, 'acc2', pane.x + 2 - (start.x + start.width / 2), 0);

    await expect.poll(() => readDrag()).toMatch(/ {2}acc2 = \(\d+, /);
    const x = Number(/ {2}acc2 = \((\d+), /.exec(readDrag())![1]);
    expect(x).toBeGreaterThan(0);
    // The literal touches the edge of the allowed area, not beyond it.
    const area = (await page.getByTestId('allowed-area').boundingBox())!;
    const literal = (await node(page, '#literal:acc2.in:in').boundingBox())!;
    expect(literal.x).toBeGreaterThanOrEqual(area.x - 1);
    expect(literal.x).toBeLessThan(area.x + 3);
  });

  test('literals cannot be dragged', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    const literal = node(page, '#literal:acc2.in:in');
    await expect(literal).toBeVisible();
    const before = readDrag();
    const offset = async () =>
      (await node(page, 'acc2').boundingBox())!.x - (await literal.boundingBox())!.x;
    const offsetBefore = await offset();
    // (Dragging a literal pans the canvas instead.)
    await drag(page, '#literal:acc2.in:in', 100, 100);
    await page.waitForTimeout(300);
    expect(await offset()).toBeCloseTo(offsetBefore, 0);
    expect(readDrag()).toBe(before);
  });

  // Connections (still Drag.dia, so these run after the moves).

  test('selecting a connection and pressing Delete removes its statement', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    expect(readDrag()).toContain('  connect(in, add.in1);\n');
    await expect(page.locator('.react-flow__edge')).toHaveCount(4);

    await edge(page, 'in.out->add.in:in1').click();
    await expect(edge(page, 'in.out->add.in:in1')).toHaveClass(/selected/);
    await page.keyboard.press('Delete');

    await expect(page.locator('.react-flow__edge')).toHaveCount(3);
    expect(readDrag()).not.toContain('connect(in, add.in1);');
  });

  test('dragging from a handle to a free port connects them', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const from = handle(page, 'in', 'out');
    const to = handle(page, 'add', 'in:in1');

    // Start the drag: only the free Int input (in1) and the targets that accept
    // an Int (out, state's in is taken) light up.
    const a = (await node(page, 'in').boundingBox())!;
    await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2); // hover shows the handle
    const start = (await from.boundingBox())!;
    await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2);
    await page.mouse.down();
    await page.mouse.move(start.x + 40, start.y + 30, { steps: 5 });
    await expect(to).toHaveClass(/handle--valid/);
    await expect(handle(page, 'add', 'in:in2')).not.toHaveClass(/handle--valid/);
    const end = (await to.boundingBox())!;
    await page.mouse.move(end.x + end.width / 2, end.y + end.height / 2, { steps: 5 });
    await page.mouse.up();

    await expect(page.locator('.react-flow__edge')).toHaveCount(4);
    await expect(edge(page, 'in.out->add.in:in1')).toHaveCount(1);
    expect(readDrag()).toContain('  connect(add.out, state);\n  connect(in, add.in1);\n}');
  });

  test('a port that already has a connection cannot get another', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readDrag();
    const start = (await handle(page, 'add', 'out:out').boundingBox())!;
    const end = (await handle(page, 'add', 'in:in2').boundingBox())!;
    await page.mouse.move(start.x + 3, start.y + 3);
    await page.mouse.down();
    await page.mouse.move(end.x + 3, end.y + 3, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(300);
    await expect(page.locator('.react-flow__edge')).toHaveCount(4);
    expect(readDrag()).toBe(before);
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('selecting a literal and pressing Delete removes its connection', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    expect(readDrag()).toContain('connect(1, acc1.in);');
    await node(page, '#literal:acc1.in:in').click();
    await page.keyboard.press('Delete');
    await expect(node(page, '#literal:acc1.in:in')).toHaveCount(0);
    expect(readDrag()).not.toContain('connect(1, acc1.in);');
  });

  test('the × on a selected connection deletes it', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const button = page.getByRole('button', { name: 'Delete connection', exact: true });
    await expect(button).toHaveCount(0);

    // Click the connection just right of state's output (in Acc it overlaps
    // add.out -> state elsewhere, so the centre of its box is ambiguous).
    const start = (await handle(page, 'state', 'out').boundingBox())!;
    await page.mouse.click(start.x + start.width / 2 + 6, start.y + start.height / 2);
    await expect(edge(page, 'state.out->add.in:in2')).toHaveClass(/selected/);
    await expect(button).toHaveCount(1);
    await button.click();

    await expect(edge(page, 'state.out->add.in:in2')).toHaveCount(0);
    expect(readDrag()).not.toContain('connect(state, add.in2);');
    await expect(button).toHaveCount(0);
  });

  test('the × on a selected literal deletes its connection', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    const literal = node(page, '#literal:acc2.in:in');
    await literal.click();
    await page.getByRole('button', { name: 'Delete literal', exact: true }).click();
    await expect(literal).toHaveCount(0);
    expect(readDrag()).not.toContain('connect(2, acc2.in);');
  });

  // Undo/redo (still Drag.dia; the last test changes the file from outside).

  test('undo and redo a move with the toolbar buttons', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const undo = page.getByTestId('undo');
    const redo = page.getByTestId('redo');
    await expect(undo).toBeDisabled(); // a new tab has no history
    await expect(redo).toBeDisabled();

    const before = readDrag();
    await drag(page, 'add', 30, 40);
    await expect.poll(() => readDrag()).not.toBe(before);
    const moved = readDrag();
    await expect(undo).toBeEnabled();

    await undo.click();
    await expect.poll(() => readDrag()).toBe(before);
    await expect(undo).toBeDisabled();
    await expect(redo).toBeEnabled();

    await redo.click();
    await expect.poll(() => readDrag()).toBe(moved);
    await expect(redo).toBeDisabled();
    await expect(undo).toBeEnabled();
  });

  test('undo and redo a deleted connection with the keyboard', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readDrag();
    expect(before).toContain('connect(add.out, out);');
    // Click just before out's input: near add's output, the connections to
    // out and to state overlap.
    const target = (await handle(page, 'out', 'in').boundingBox())!;
    await page.mouse.click(target.x - 8, target.y + target.height / 2);
    await expect(edge(page, 'add.out:out->out.in')).toHaveClass(/selected/);
    await page.keyboard.press('Delete');
    await expect(edge(page, 'add.out:out->out.in')).toHaveCount(0);

    await page.keyboard.press('Control+z');
    await expect(edge(page, 'add.out:out->out.in')).toHaveCount(1);
    expect(readDrag()).toBe(before);

    await page.keyboard.press('Control+Shift+z');
    await expect(edge(page, 'add.out:out->out.in')).toHaveCount(0);
    await page.keyboard.press('Control+z');
    await expect(edge(page, 'add.out:out->out.in')).toHaveCount(1);
  });

  test('a change made elsewhere reloads the diagram and clears the undo history', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readDrag();
    await drag(page, 'add', 20, 20);
    await expect.poll(() => readDrag()).not.toBe(before);
    await expect(page.getByTestId('undo')).toBeEnabled();

    // Someone edits the file in a text editor.
    const external = readDrag() + '// edited elsewhere\n';
    writeFileSync(dragFile, external);

    // The diagram is reloaded, and the history (which no longer matches) is gone.
    // (Clicking Undo before the reload arrives is refused by the server: see WebServerTest.)
    await expect(page.getByTestId('notice')).toContainText('has been reloaded');
    await expect(page.getByTestId('undo')).toBeDisabled();
    expect(readDrag()).toBe(external);
  });

  // Blocks (still Drag.dia).

  test('dragging a type from the palette adds a block; its × deletes it', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await page.getByText('Standard library').click(); // collapsed, as in the old editor
    await page
      .getByTestId('palette-Add')
      .dragTo(page.locator('.react-flow__pane'), { targetPosition: { x: 320, y: 260 } });

    await expect(node(page, 'Add_1')).toBeVisible();
    await expect(node(page, 'Add_1')).toHaveText('Add'); // simple name: shows the type
    expect(readDrag()).toContain('  add: Add;\n  Add_1;\n');
    expect(readDrag()).toMatch(/ {2}Add_1 = \(\d+, \d+, 60, 30\)/);

    await node(page, 'Add_1').click();
    await page.getByRole('button', { name: 'Delete block', exact: true }).click();
    await expect(node(page, 'Add_1')).toHaveCount(0);
    expect(readDrag()).not.toContain('Add_1');

    await page.keyboard.press('Control+z');
    await expect(node(page, 'Add_1')).toBeVisible();
  });

  test('Delete removes a block with its connections, and undo brings them back', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readDrag();
    await node(page, 'add').click();
    await page.keyboard.press('Delete');

    await expect(node(page, 'add')).toHaveCount(0);
    const text = readDrag();
    expect(text).not.toContain('add: Add;');
    expect(text).not.toContain('add.');

    await page.keyboard.press('Control+z');
    await expect(node(page, 'add')).toBeVisible();
    expect(readDrag()).toBe(before);
  });

  // Literals, variables and parameters (still Drag.dia; earlier tests deleted
  // Main's literals, so acc1.in and acc2.in are free).

  test('dropping a literal on a free input port and typing a value connects it', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    const form = page.getByTestId('add-form');

    // Dropped away from any port: a hint instead of a form.
    await page.getByTestId('tool-literal').dragTo(page.locator('.react-flow__pane'), { targetPosition: { x: 500, y: 300 } });
    await expect(page.getByRole('alert')).toContainText('Drop the literal on a free input port');
    await expect(form).toHaveCount(0);

    // A value that is not a literal is refused in the form, which stays open.
    await page.getByTestId('tool-literal').dragTo(handle(page, 'acc2', 'in:in'));
    await expect(form).toBeVisible();
    await form.getByLabel('Value').fill('abc');
    await form.getByLabel('Value').press('Enter');
    await expect(form.getByRole('alert')).toContainText('Not a literal');
    await form.getByLabel('Value').fill('7');
    await form.getByLabel('Value').press('Enter');

    await expect(form).toHaveCount(0);
    await expect(node(page, '#literal:acc2.in:in')).toHaveText('7');
    expect(readDrag()).toContain('connect(7, acc2.in);');
  });

  test('dropping a variable opens a form for its kind, name and type', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const form = page.getByTestId('add-form');
    await page.getByTestId('tool-variable').dragTo(page.locator('.react-flow__pane'), { targetPosition: { x: 450, y: 320 } });
    await expect(form.getByLabel('Name')).toHaveValue('v'); // a suggestion

    // A name that is taken is refused; Escape cancels without changing anything.
    const before = readDrag();
    await form.getByLabel('Name').fill('state');
    await form.getByLabel('Name').press('Enter');
    await expect(form.getByRole('alert')).toContainText('already used');
    await form.getByLabel('Name').press('Escape');
    await expect(form).toHaveCount(0);
    expect(readDrag()).toBe(before);

    await page.getByTestId('tool-variable').dragTo(page.locator('.react-flow__pane'), { targetPosition: { x: 450, y: 320 } });
    await form.getByLabel('Kind').selectOption('input');
    await form.getByLabel('Name').fill('level');
    await form.getByLabel('Type').selectOption('Real');
    await form.getByRole('button', { name: 'Add' }).click();

    await expect(node(page, 'level')).toHaveText('input level');
    expect(readDrag()).toContain('  var state: Int;\n  input level: Real;\n');
  });

  test('an input parameter is added to the header, and can be deleted and undeleted', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const form = page.getByTestId('add-form');
    await page.getByTestId('tool-inParameter').dragTo(page.locator('.react-flow__pane'), { targetPosition: { x: 60, y: 330 } });
    await form.getByLabel('Name').fill('reset');
    await form.getByLabel('Type').selectOption('Bool');
    await form.getByLabel('Name').press('Enter');

    await expect(node(page, 'reset').locator('.parameter')).toHaveClass(/parameter--in/);
    expect(readDrag()).toContain('diagramtype Acc(in: Int, reset: Bool => out: Int) {');

    await node(page, 'reset').click();
    await page.getByRole('button', { name: 'Delete parameter', exact: true }).click();
    await expect(node(page, 'reset')).toHaveCount(0);
    expect(readDrag()).toContain('diagramtype Acc(in: Int => out: Int) {');

    await page.keyboard.press('Control+z');
    await expect(node(page, 'reset')).toBeVisible();
    expect(readDrag()).toContain('diagramtype Acc(in: Int, reset: Bool => out: Int) {');
  });

  // Rename, the right-click menu and auto layout (still Drag.dia).

  test('double-clicking a block renames it and its uses', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const prompt = page.getByTestId('prompt-form');
    await node(page, 'add').dblclick();
    await expect(prompt.getByLabel('Name')).toHaveValue('add');

    // A name that is taken is refused in the form.
    await prompt.getByLabel('Name').fill('state');
    await prompt.getByLabel('Name').press('Enter');
    await expect(prompt.getByRole('alert')).toContainText('already used');

    await prompt.getByLabel('Name').fill('sum');
    await prompt.getByLabel('Name').press('Enter');
    await expect(prompt).toHaveCount(0);
    await expect(node(page, 'sum')).toHaveText('sum');
    const text = readDrag();
    expect(text).toContain('  sum: Add;');
    expect(text).toContain('connect(in, sum.in1);');
    expect(text).toContain('  sum = (');
    expect(text).not.toMatch(/\badd\b/);
  });

  test('the right-click menu renames a variable', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await node(page, 'level').click({ button: 'right' });
    await expect(page.getByTestId('context-menu')).toBeVisible();
    await page.getByRole('menuitem', { name: /Rename/ }).click();
    await page.getByTestId('prompt-form').getByLabel('Name').fill('height');
    await page.getByTestId('prompt-form').getByLabel('Name').press('Enter');
    await expect(node(page, 'height')).toHaveText('input height');
    expect(readDrag()).toContain('  input height: Real;');
  });

  test('F2 renames the selected node', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await node(page, 'state').click();
    await page.keyboard.press('F2');
    await expect(page.getByTestId('prompt-form').getByLabel('Name')).toHaveValue('state');
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('prompt-form')).toHaveCount(0);
  });

  test("the right-click menu changes a literal's value", async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    await node(page, '#literal:acc2.in:in').click({ button: 'right' });
    await page.getByRole('menuitem', { name: /Change value/ }).click();
    const value = page.getByTestId('prompt-form').getByLabel('Value');
    await expect(value).toHaveValue('7');
    await value.fill('9');
    await value.press('Enter');
    await expect(node(page, '#literal:acc2.in:in')).toHaveText('9');
    expect(readDrag()).toContain('connect(9, acc2.in);');
  });

  test("Open shows a block's type", async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    await node(page, 'acc1').click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Open Acc' }).click();
    await expect(page.getByTestId('type-select')).toHaveValue('Acc');
    await expect(node(page, 'sum')).toBeVisible();
  });

  test('Auto layout lays out the type again, and can be undone', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readDrag();
    const pane = (await page.locator('.react-flow__pane').boundingBox())!;
    await page.mouse.click(pane.x + pane.width - 40, pane.y + pane.height - 40, { button: 'right' });
    await page.getByRole('menuitem', { name: 'Auto layout' }).click();
    await expect.poll(() => readDrag()).not.toBe(before);
    await page.keyboard.press('Control+z');
    await expect.poll(() => readDrag()).toBe(before);
  });

  // New diagram types and external functions (still Drag.dia).

  test('New ▸ Diagram type adds an empty type and shows it; undo removes it', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await page.getByTestId('new').click();
    await page.getByRole('menuitem', { name: 'Diagram type…' }).click();
    const prompt = page.getByTestId('prompt-form');

    // A taken name is refused in the form.
    await prompt.getByLabel('Name').fill('Acc');
    await prompt.getByLabel('Name').press('Enter');
    await expect(prompt.getByRole('alert')).toContainText('already a type called Acc');

    await prompt.getByLabel('Name').fill('Counter');
    await prompt.getByLabel('Name').press('Enter');
    await expect(prompt).toHaveCount(0);
    await expect(page.getByTestId('type-select')).toHaveValue('Counter');
    await expect(page).toHaveURL(/type=Counter/);
    await expect(page.locator('.react-flow__node')).toHaveCount(0);
    await expect(page.getByTestId('palette-Acc')).toBeVisible(); // its blocks can be added
    expect(readDrag()).toMatch(/\ndiagramtype Counter \{\n\}\n$/);

    await page.keyboard.press('Control+z');
    await expect(page.getByTestId('type-select').locator('option', { hasText: 'Counter' })).toHaveCount(0);
    expect(readDrag()).not.toContain('Counter');
  });

  test('New ▸ External function adds a function with parameters; the view stays', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    await page.getByTestId('new').click();
    await page.getByRole('menuitem', { name: 'External function…' }).click();
    const form = page.getByTestId('external-function-form');
    await form.getByLabel('Name').fill('Log');
    await form.getByRole('button', { name: '+ Add input' }).click();
    await form.getByRole('button', { name: '+ Add input' }).click();
    await form.getByLabel('Input 2 name').fill('level');
    await form.getByLabel('Input 2 type').selectOption('Real');
    await form.getByLabel('Output').check();
    await form.getByLabel('Output name').fill('ok');
    await form.getByLabel('Output type').selectOption('Bool');
    await form.getByRole('button', { name: 'Create' }).click();

    await expect(form).toHaveCount(0);
    await expect(page.getByTestId('type-select')).toHaveValue('Main');
    await expect(page.getByTestId('palette-Log')).toContainText('external');
    expect(readDrag()).toContain('external Print(in: Int);\nexternal Log(in1: Int, level: Real => ok: Bool);\n');
  });

  // Type management (still Drag.dia).

  async function paneMenu(page: Page) {
    const pane = (await page.locator('.react-flow__pane').boundingBox())!;
    await page.mouse.click(pane.x + pane.width - 40, pane.y + pane.height - 40, { button: 'right' });
  }

  test('renaming the shown type renames its uses, and the view follows', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await paneMenu(page);
    await page.getByRole('menuitem', { name: 'Rename Acc…' }).click();
    const name = page.getByTestId('prompt-form').getByLabel('Name');
    await expect(name).toHaveValue('Acc');
    await name.fill('Accumulator');
    await name.press('Enter');

    await expect(page.getByTestId('type-select')).toHaveValue('Accumulator');
    await expect(page).toHaveURL(/type=Accumulator/);
    const text = readDrag();
    expect(text).toContain('diagramtype Accumulator(');
    expect(text).toContain('acc1: Accumulator;');

    await page.keyboard.press('Control+z');
    await expect.poll(() => readDrag()).toContain('acc1: Acc;');
  });

  test('a new subtype from the palette; its super types can be changed and reordered', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    await page.getByTestId('palette-Acc').click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'New subtype of Acc…' }).click();
    const name = page.getByTestId('prompt-form').getByLabel('Name');
    await name.fill('Acc2');
    await name.press('Enter');

    await expect(page.getByTestId('type-select')).toHaveValue('Acc2');
    await expect(node(page, 'Acc::sum')).toBeVisible(); // inherited
    expect(readDrag()).toContain('diagramtype Acc2 extends Acc {\n}\n');

    // The super types are an ordered list: add Main (at the end), move it first.
    await paneMenu(page);
    await page.getByRole('menuitem', { name: 'Super types…' }).click();
    const form = page.getByTestId('super-types-form');
    const list = form.getByTestId('super-types').locator('li');
    await expect(list).toHaveText(['Acc↑↓×']);
    await expect(form.getByRole('button', { name: 'Apply' })).toBeDisabled(); // nothing changed yet
    await form.getByLabel('Super type to add').selectOption('Main');
    await form.getByRole('button', { name: 'Add' }).click();
    await expect(list).toHaveText(['Acc↑↓×', 'Main↑↓×']);
    await form.getByRole('button', { name: 'Move Main up' }).click();
    await expect(list).toHaveText(['Main↑↓×', 'Acc↑↓×']);
    await form.getByRole('button', { name: 'Apply' }).click();
    await expect(form).toHaveCount(0);
    await expect.poll(() => readDrag()).toContain('diagramtype Acc2 extends Main, Acc {\n}\n');

    // Remove both.
    await paneMenu(page);
    await page.getByRole('menuitem', { name: 'Super types…' }).click();
    await expect(list).toHaveText(['Main↑↓×', 'Acc↑↓×']);
    await form.getByRole('button', { name: 'Remove Main' }).click();
    await form.getByRole('button', { name: 'Remove Acc' }).click();
    await expect(form).toContainText('No super types');
    await form.getByRole('button', { name: 'Apply' }).click();
    await expect(form).toHaveCount(0);
    await expect(page.locator('.react-flow__node')).toHaveCount(0);
    expect(readDrag()).toContain('diagramtype Acc2 {\n}\n');
  });

  test('types in use cannot be deleted; deleting the shown type shows another', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc2');
    await page.getByTestId('palette-Acc').click({ button: 'right' });
    await page.getByRole('menuitem', { name: 'Delete Acc' }).click();
    await expect(page.getByRole('alert')).toContainText('Cannot delete Acc: it is used in Main');

    await paneMenu(page);
    await page.getByRole('menuitem', { name: 'Delete Acc2' }).click();
    await expect(page.getByTestId('type-select')).toHaveValue('Main');
    await expect(page.getByTestId('type-select').locator('option', { hasText: 'Acc2' })).toHaveCount(0);
    expect(readDrag()).not.toContain('Acc2');
  });

  // Changes on disk (still Drag.dia).

  test('an edit in one tab shows up in another tab', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const other = await page.context().newPage();
    await goto(other, '/?file=Drag.dia&type=Acc');
    const before = (await node(other, 'sum').boundingBox())!;

    await drag(page, 'sum', 0, 60);

    await expect.poll(async () => (await node(other, 'sum').boundingBox())!.y).toBeGreaterThan(before.y + 20);
    await expect(other.getByTestId('notice')).toContainText('Drag.dia was changed outside this tab');
    await expect(page.getByTestId('notice')).toHaveCount(0); // its own edit is not echoed
    await other.close();
  });

  test('a change made in a text editor shows up without reloading', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await expect(node(page, 'state')).toBeVisible();
    writeFileSync(dragFile, readDrag().replace(/\bstate\b/g, 'total'));

    await expect(node(page, 'total')).toHaveText('var total');
    await expect(node(page, 'state')).toHaveCount(0);
    await expect(page.getByTestId('notice')).toContainText('has been reloaded');
    await expect(page.getByTestId('undo')).toBeDisabled();
  });
});

test('connections touch parameter and variable boxes', async ({ page }) => {
  await goto(page, '/?file=TwoCounters.dia&type=Acc');
  // Each connection's first and last point, and the boxes of its two nodes, on screen.
  const ends = await page.evaluate(() =>
    [...document.querySelectorAll<SVGPathElement>('.react-flow__edge path.react-flow__edge-path')].map((path) => {
      const edge = path.closest('.react-flow__edge')!.getAttribute('data-id')!;
      const toScreen = (at: number) => {
        const p = path.getPointAtLength(at).matrixTransform(path.getScreenCTM()!);
        return { x: p.x, y: p.y };
      };
      return { edge, start: toScreen(0), end: toScreen(path.getTotalLength()) };
    }),
  );
  const box = async (id: string) => (await node(page, id).boundingBox())!;
  const near = (a: number, b: number) => expect(Math.abs(a - b)).toBeLessThan(1.5);
  // in -> add.in1 starts at in's right side; add.out -> out ends at out's left
  // side; state starts and ends on its box.
  const inBox = await box('in');
  const outBox = await box('out');
  const stateBox = await box('state');
  const byId = new Map(ends.map((e) => [e.edge, e]));
  near(byId.get('in.out->add.in:in1')!.start.x, inBox.x + inBox.width);
  near(byId.get('add.out:out->out.in')!.end.x, outBox.x);
  near(byId.get('state.out->add.in:in2')!.start.x, stateBox.x + stateBox.width);
  near(byId.get('add.out:out->state.in')!.end.x, stateBox.x);
});

test('the palette lists the types a block can be added of', async ({ page }) => {
  await goto(page, '/?file=TwoCounters.dia&type=Acc');
  const palette = page.getByTestId('palette');
  // The file's other types: the external function Print and the diagram type Main (not Acc itself).
  await expect(palette.getByTestId('palette-Print')).toContainText('external');
  await expect(palette.getByTestId('palette-Main')).toBeVisible();
  await expect(palette.getByTestId('palette-Acc')).toHaveCount(0);
  // The standard library is collapsed.
  await expect(palette.getByTestId('palette-Add')).toBeHidden();
  await palette.getByText('Standard library').click();
  await expect(palette.getByTestId('palette-Add')).toBeVisible();
});

test('lists of types are sorted by name', async ({ page }) => {
  await goto(page, '/?file=PLib.dia&type=PTrack');
  const sorted = (names: string[]) => [...names].sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' }));
  // Declared P, IPart, DPart, Rid, PI, PD, PTrack, Main.
  const types = await page.getByTestId('type-select').locator('option').allTextContents();
  expect(types).toEqual(['DPart', 'IPart', 'Main', 'P', 'PD', 'PI', 'PTrack', 'Rid']);
  const palette = page.getByTestId('palette');
  await palette.getByText('Standard library').click();
  for (const group of await palette.locator('.palette__group').all()) {
    const names = await group.locator('.palette__name').allTextContents();
    expect(names.length).toBeGreaterThan(1);
    expect(names).toEqual(sorted(names));
  }
  const files = await page.getByTestId('file-select').locator('option').allTextContents();
  expect(files).toEqual(sorted(files));
});

test('the right-click menu greys out what cannot be changed here', async ({ page }) => {
  await goto(page, '/?file=PLib.dia&type=PTrack');
  await node(page, 'P::e').click({ button: 'right' });
  const menu = page.getByTestId('context-menu');
  await expect(menu.getByRole('menuitem', { name: /Rename/ })).toBeDisabled();
  await expect(menu.getByRole('menuitem', { name: /Rename/ })).toHaveAttribute('title', 'Inherited');
  await expect(menu.getByRole('menuitem', { name: /Delete/ })).toBeDisabled();
  // RSub is from the standard library: there is no diagram to open.
  await expect(menu.getByRole('menuitem', { name: 'Open RSub' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);

  await node(page, 'SelReal_1').click({ button: 'right' });
  await expect(menu.getByRole('menuitem', { name: /Rename/ })).toBeEnabled();
});

test('inherited blocks have no delete button', async ({ page }) => {
  await goto(page, '/?file=PLib.dia&type=PTrack');
  await node(page, 'P::e').click();
  await expect(page.getByRole('button', { name: 'Delete block', exact: true })).toHaveCount(0);
  await node(page, 'SelReal_1').click();
  await expect(page.getByRole('button', { name: 'Delete block', exact: true })).toHaveCount(1);
});

test('files can be created, renamed and deleted from the File menu', async ({ page }) => {
  const workspace = resolve('..', 'build', 'e2e-workspace');
  const exists = (name: string) => {
    try {
      readFileSync(resolve(workspace, name));
      return true;
    } catch {
      return false;
    }
  };
  await goto(page, '/?file=TwoCounters.dia&type=Main');

  // New file: ".dia" is added, and it opens with an empty first type.
  await page.getByTestId('file-menu').click();
  await page.getByRole('menuitem', { name: 'New file…' }).click();
  const form = page.getByTestId('new-file-form');
  await expect(form.getByLabel('First diagram type')).toHaveValue('Main');
  await form.getByLabel('Name').fill('TwoCounters');
  await form.getByLabel('Name').press('Enter');
  await expect(form.getByRole('alert')).toContainText('already a file called TwoCounters.dia');
  await form.getByLabel('Name').fill('Fresh');
  await form.getByLabel('Name').press('Enter');

  await expect(page.getByTestId('file-select')).toHaveValue('Fresh.dia');
  await expect(page.getByTestId('type-select')).toHaveValue('Main');
  await expect(page).toHaveURL(/file=Fresh\.dia/);
  expect(readFileSync(resolve(workspace, 'Fresh.dia'), 'utf8')).toBe('diagramtype Main {\n}\n');

  // Rename.
  await page.getByTestId('file-menu').click();
  await page.getByRole('menuitem', { name: 'Rename Fresh.dia…' }).click();
  await page.getByTestId('prompt-form').getByLabel('Name').fill('Fresher.dia');
  await page.getByTestId('prompt-form').getByLabel('Name').press('Enter');
  await expect(page.getByTestId('file-select')).toHaveValue('Fresher.dia');
  expect(exists('Fresh.dia')).toBe(false);
  expect(exists('Fresher.dia')).toBe(true);

  // Delete asks first; Cancel keeps the file.
  await page.getByTestId('file-menu').click();
  await page.getByRole('menuitem', { name: 'Delete Fresher.dia…' }).click();
  await page.getByTestId('confirm-form').getByRole('button', { name: 'Cancel' }).click();
  expect(exists('Fresher.dia')).toBe(true);
  await page.getByTestId('file-menu').click();
  await page.getByRole('menuitem', { name: 'Delete Fresher.dia…' }).click();
  await page.getByTestId('confirm-form').getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByTestId('file-select')).not.toHaveValue('Fresher.dia');
  await expect(page.getByTestId('file-select').locator('option', { hasText: 'Fresher.dia' })).toHaveCount(0);
  expect(exists('Fresher.dia')).toBe(false);
});

/**
 * Right-clicks a connection a little after its source handle, where only it
 * runs (not too close: ports have a larger invisible grab area).
 */
async function rightClickConnection(page: Page, sourceNode: string, sourceHandle: string) {
  const h = (await handle(page, sourceNode, sourceHandle).boundingBox())!;
  await page.mouse.click(h.x + h.width + 14, h.y + h.height / 2, { button: 'right' });
}

test("a type's own parameters can be reordered and removed; inherited ones come first", async ({ page }) => {
  // Params.dia is a copy of PLib.dia that only this test edits.
  const text = () => readWorkspaceFile('Params.dia');
  await goto(page, '/?file=Params.dia&type=PTrack');
  await node(page, 'Track').click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Parameters…' }).click();
  const form = page.getByTestId('parameters-form');
  const ins = form.getByTestId('in-parameters').locator('li');
  await expect(ins).toHaveText(['SP: Realfrom P', 'PV: Realfrom P', 'K: Realfrom P', 'Track: Bool↑↓×', 'TrackValue: Real↑↓×']);
  await expect(form.getByTestId('out-parameters').locator('li')).toHaveText(['Out: Realfrom P']);
  await expect(form.getByRole('button', { name: 'Apply' })).toBeDisabled();
  await form.getByRole('button', { name: 'Move TrackValue up' }).click();
  await expect(ins.nth(3)).toHaveText('TrackValue: Real↑↓×');
  await form.getByRole('button', { name: 'Apply' }).click();
  await expect(form).toHaveCount(0);
  await expect.poll(text).toContain('diagramtype PTrack(TrackValue: Real, Track: Bool) extends P {');

  // Removing one also deletes its connections.
  await paneMenuAt(page);
  await page.getByRole('menuitem', { name: 'Parameters…' }).click();
  await expect(ins).toHaveText(['SP: Realfrom P', 'PV: Realfrom P', 'K: Realfrom P', 'TrackValue: Real↑↓×', 'Track: Bool↑↓×']);
  await form.getByRole('button', { name: 'Remove Track', exact: true }).click();
  await form.getByRole('button', { name: 'Apply' }).click();
  await expect(form).toHaveCount(0);
  await expect(node(page, 'Track')).toHaveCount(0);
  await expect
    .poll(text)
    .toContain(
      'diagramtype PTrack(TrackValue: Real) extends P {\n  SelReal_1;\n' +
        '  intercept Out with SelReal_1.in2, SelReal_1.out;\n  connect(TrackValue, SelReal_1.in1);\n}',
    );

  // Undo restores both.
  await page.getByTestId('undo').click();
  await expect(node(page, 'Track')).toHaveCount(1);
  await page.getByTestId('undo').click();
  await expect.poll(text).toContain('diagramtype PTrack(Track: Bool, TrackValue: Real) extends P {');

  // A parameter used elsewhere cannot be removed: the reason is shown in the form.
  await page.getByTestId('type-select').selectOption('Rid');
  await node(page, 'in').click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Parameters…' }).click();
  await form.getByRole('button', { name: 'Remove in', exact: true }).click();
  await expect(form.getByTestId('in-parameters')).toHaveCount(0);
  await expect(form).toContainText('None');
  await form.getByRole('button', { name: 'Apply' }).click();
  await expect(form.getByRole('alert')).toContainText('Rid.in was not found');
  await form.getByRole('button', { name: 'Cancel' }).click();
  await expect(form).toHaveCount(0);
});

async function paneMenuAt(page: Page) {
  const pane = (await page.locator('.react-flow__pane').boundingBox())!;
  await page.mouse.click(pane.x + pane.width - 40, pane.y + pane.height - 40, { button: 'right' });
}

test('connections are drawn even if a node that stays on screen is not measured again', async ({ page }) => {
  // React Flow measures a node's handles when the browser reports its size.
  // Rarely, after the diagram was replaced, a node that stayed on screen (an
  // inherited parameter) was left unmeasured and its connections were missing
  // until a reload. Simulate that: after the first fit, the browser no longer
  // reports the sizes of P's nodes.
  await page.addInitScript(() => {
    const Native = window.ResizeObserver;
    window.ResizeObserver = class extends Native {
      constructor(callback: ResizeObserverCallback) {
        super((entries, observer) => {
          const drop = (window as { dropSizes?: boolean }).dropSizes;
          const kept = entries.filter((e) => !(drop && e.target.getAttribute('data-id')?.startsWith('P::')));
          if (kept.length > 0) callback(kept, observer);
        });
      }
    };
  });
  // Remeasure.dia is a copy of PLib.dia that only this test changes.
  const file = resolve('..', 'build', 'e2e-workspace', 'Remeasure.dia');
  await goto(page, '/?file=Remeasure.dia&type=PTrack');
  const edges = page.locator('.react-flow__edge');
  const count = await edges.count();
  expect(count).toBeGreaterThan(5);
  await page.evaluate(() => ((window as { dropSizes?: boolean }).dropSizes = true));

  // SelReal_1 is moved on disk: the diagram is sent again and replaces the nodes.
  const text = readWorkspaceFile('Remeasure.dia');
  writeFileSync(file, text.replace('SelReal_1 = (298, 10, 44, 46)', 'SelReal_1 = (298, 30, 44, 46)'));
  await expect(node(page, 'SelReal_1')).toHaveAttribute('style', /translate\(298px, 30px\)/);
  await expect(edges).toHaveCount(count);
  await page.waitForTimeout(300);
  await expect(edges).toHaveCount(count);
});

test('an inherited connection can be intercepted in a subtype', async ({ page }) => {
  // Intercept.dia is a copy of PLib.dia that only this test edits.
  const file = resolve('..', 'build', 'e2e-workspace', 'Intercept.dia');
  const text = () => readFileSync(file, 'utf8');
  await goto(page, '/?file=Intercept.dia&type=PTrack');

  // Remove PTrack's own interception: P's connection PPart.out -> Out is back.
  await edge(page, 'P::PPart.out:out->SelReal_1.in:in2').click({ force: true });
  await page.keyboard.press('Delete');
  const inherited = edge(page, 'P::PPart.out:out->P::Out.in');
  await expect(inherited).toHaveCount(1);
  expect(text()).not.toContain('intercept Out with SelReal_1.in2'); // (PI and PD intercept too)

  // A local connection cannot be intercepted (real right-clicks).
  await rightClickConnection(page, 'Track', 'out');
  await expect(page.getByRole('menuitem', { name: 'Intercept', exact: true })).toBeDisabled();
  await expect(page.getByRole('menuitem', { name: 'Intercept', exact: true })).toHaveAttribute(
    'title',
    'Only inherited connections can be intercepted',
  );
  await page.keyboard.press('Escape');

  // Escape stops intercepting.
  await rightClickConnection(page, 'P::PPart', 'out:out');
  await page.getByRole('menuitem', { name: 'Intercept', exact: true }).click();
  const hint = page.getByTestId('intercept-hint');
  await expect(hint).toContainText('Intercept P::PPart.out → P::Out: click the input port');
  await page.keyboard.press('Escape');
  await expect(hint).toHaveCount(0);

  // As in the old editor: click an input port (green ones fit), then an output port.
  await rightClickConnection(page, 'P::PPart', 'out:out');
  await page.getByRole('menuitem', { name: 'Intercept', exact: true }).click();
  const in2 = handle(page, 'SelReal_1', 'in:in2');
  await expect(in2).toHaveClass(/handle--valid/);
  await expect(handle(page, 'SelReal_1', 'in:sel')).toHaveClass(/handle--unfit/); // a Bool, and taken
  await in2.click();
  await expect(hint).toContainText('now click the output port it comes back out of (SelReal_1.in2 → …)');
  const out = handle(page, 'SelReal_1', 'out:out');
  await expect(out).toHaveClass(/handle--valid/);
  await out.click();
  await expect(hint).toHaveCount(0);

  await expect(edge(page, 'P::PPart.out:out->SelReal_1.in:in2')).toHaveCount(1);
  await expect(edge(page, 'SelReal_1.out:out->P::Out.in')).toHaveCount(1);
  await expect(inherited).toHaveCount(0);
  expect(text()).toContain('intercept Out with SelReal_1.in2, SelReal_1.out;');
});

test('inherited connections can be selected (highlighted) but not deleted', async ({ page }) => {
  await goto(page, '/?file=PLib.dia&type=PTrack');
  const before = readWorkspaceFile('PLib.dia');
  const inherited = edge(page, 'P::e.out:out->P::PPart.in:in1');
  await inherited.click({ force: true });
  await expect(inherited).toHaveClass(/selected/);
  await expect(inherited.locator('.flow__halo')).toHaveCount(1);
  // No ×, and Delete does nothing.
  await expect(page.getByRole('button', { name: 'Delete connection', exact: true })).toHaveCount(0);
  await page.keyboard.press('Delete');
  await page.waitForTimeout(300);
  await expect(inherited).toHaveCount(1);
  await expect(page.getByTestId('undo')).toBeDisabled();
  expect(readWorkspaceFile('PLib.dia')).toBe(before);

  // A local connection gets the ×.
  const local = edge(page, 'Track.out->SelReal_1.in:sel');
  await local.click({ force: true });
  await expect(local).toHaveClass(/selected/);
  await expect(inherited).not.toHaveClass(/selected/);
  await expect(page.getByRole('button', { name: 'Delete connection', exact: true })).toHaveCount(1);
});

test('files with syntax errors are read-only', async ({ page }) => {
  // BloqqiMovie.dia only parses through error recovery.
  await goto(page, '/?file=BloqqiMovie.dia');
  await expect(page.getByTestId('read-only')).toContainText('syntax errors');
  const first = page.locator('.react-flow__node-block').first();
  await expect(first).toBeVisible();
  await expect(first).not.toHaveClass(/draggable/);
});

test('the canvas only shows the area where nodes can go, with the same edge everywhere', async ({ page }) => {
  // The shaded strip outside the area is 16 screen pixels, whatever the zoom.
  const strip = async () => {
    const pane = (await page.locator('.react-flow__pane').boundingBox())!;
    const origin = (await page.getByTestId('allowed-area').boundingBox())!;
    return [Math.round(origin.x - pane.x), Math.round(origin.y - pane.y)];
  };

  // Fitted views of differently sized diagrams (different zoom levels).
  for (const url of ['/?file=TwoCounters.dia&type=Acc', '/?file=vlhcc-large.dia&type=Tank', '/?file=PLib.dia&type=IPart']) {
    await goto(page, url);
    expect(await strip(), url).toEqual([16, 16]);
  }

  // Panning far into the negative area (drag the empty canvas down-right) stops at the strip.
  const pane = (await page.locator('.react-flow__pane').boundingBox())!;
  await page.mouse.move(pane.x + pane.width - 20, pane.y + pane.height - 20);
  await page.mouse.down();
  await page.mouse.move(pane.x + pane.width + 2000, pane.y + pane.height + 2000, { steps: 10 });
  await page.mouse.up();
  expect(await strip()).toEqual([16, 16]);

  // So does the "fit view" button.
  await page.getByRole('button', { name: 'fit view' }).click();
  expect(await strip()).toEqual([16, 16]);
});

// Text.dia is a copy of TwoCounters.dia that only these tests edit.
test.describe.serial('text view', () => {
  const textFile = resolve('..', 'build', 'e2e-workspace', 'Text.dia');
  const readText = () => readWorkspaceFile('Text.dia');
  const editor = (page: Page) => page.locator('.cm-content');
  /** Replaces the editor's whole text, as if typed. */
  async function setEditorText(page: Page, text: string) {
    await editor(page).click();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText(text);
  }
  /** Opens Text.dia in the text view. */
  async function gotoText(page: Page) {
    await page.goto('/?file=Text.dia&type=Acc&view=text');
    await expect(editor(page)).toContainText('diagramtype Acc');
  }
  const editorText = (page: Page) =>
    page.evaluate(() =>
      [...document.querySelectorAll('.cm-content .cm-line')].map((l) => l.textContent).join('\n'),
    );

  test('shows the whole file; typing is checked, saving writes it and updates the diagram', async ({ page }) => {
    const original = readText();
    await goto(page, '/?file=Text.dia&type=Acc');
    await page.getByTestId('view-text').click();
    await expect(page).toHaveURL(/view=text/);
    await expect(editor(page)).toContainText('diagramtype Main');
    await expect(editor(page)).toContainText('diagramtype Acc');
    await expect(page.getByTestId('text-state')).toHaveText('Saved');
    await expect(page.getByTestId('text-problems')).toHaveText('No problems');

    // A syntax error is reported while typing; nothing is written.
    const broken = original.replace('connect(in, add.in1);', 'connect(in, add.in1)');
    await setEditorText(page, broken);
    await expect(page.getByTestId('text-state')).toHaveText('Unsaved changes');
    await expect(page.getByTestId('text-problems')).toContainText('Syntax error');
    await expect(page.locator('.cm-lintRange-error')).not.toHaveCount(0);
    await expect(page.getByTestId('new')).toBeDisabled(); // save or revert first
    expect(readText()).toBe(original);

    // Fixed and saved (Ctrl/⌘+S): the file is written, the diagram follows.
    const renamed = original.replace(/\bstate\b/g, 'total');
    await setEditorText(page, renamed);
    await expect(page.getByTestId('text-problems')).toHaveText('No problems');
    await page.keyboard.press('ControlOrMeta+s');
    await expect(page.getByTestId('text-state')).toHaveText('Saved');
    await expect.poll(readText).toBe(renamed);

    await page.getByTestId('view-diagram').click();
    await expect(page).not.toHaveURL(/view=text/);
    await expect(node(page, 'total')).toHaveText('var total');

    // The save is one step of the file's undo history; the text view follows.
    await page.getByTestId('undo').click();
    await expect(node(page, 'state')).toHaveText('var state');
    await expect.poll(readText).toBe(original);
    await page.getByTestId('view-text').click();
    await expect(editor(page)).toContainText('var state: Int;');
    await expect(page.getByTestId('text-state')).toHaveText('Saved');
  });

  test("annotation blocks' contents are greyed out; highlighting resumes after them", async ({ page }) => {
    await page.goto('/?file=PLib.dia&type=PTrack&view=text');
    await expect(editor(page)).toContainText('diagramtype PTrack');
    /** The colour of `text` in the first line that starts with `line`. */
    const color = (line: string, text: string) =>
      page.evaluate(
        ([line, text]) => {
          const l = [...document.querySelectorAll('.cm-line')].find((e) => e.textContent!.trim().startsWith(line))!;
          const walker = document.createTreeWalker(l, NodeFilter.SHOW_TEXT);
          for (let n = walker.nextNode(); n; n = walker.nextNode()) {
            if (n.textContent!.includes(text)) return getComputedStyle(n.parentElement!).color;
          }
          return null;
        },
        [line, text],
      );
    const grey = 'rgb(154, 154, 160)';
    const keyword = 'rgb(119, 0, 136)'; // CodeMirror's default style: #708
    // (The editor highlights in the background, so wait for the colours.)
    await expect.poll(() => color('diagramtype PTrack', 'diagramtype')).toBe(keyword);
    // The keyword is coloured like the others; what is in the block is grey.
    await expect.poll(() => color('annotation(', 'annotation')).toBe(keyword);
    await expect.poll(() => color('annotation(', '(')).toBe(grey);
    await expect.poll(() => color('P::K', 'P::K')).toBe(grey);
    await expect.poll(() => color('P::K', '20')).toBe(grey); // not a green number
    await expect.poll(() => color('features P', 'features')).toBe(keyword); // after the block
  });

  test('a file with errors can be saved; its diagram is then read-only', async ({ page }) => {
    const original = readText();
    await gotoText(page);
    await setEditorText(page, original.replace('connect(in, add.in1);', 'connect(in, add.in1)'));
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByTestId('text-state')).toHaveText('Saved');
    await page.getByTestId('view-diagram').click();
    await expect(page.getByTestId('read-only')).toBeVisible();
    await expect(page.getByTestId('problems')).toContainText('Syntax error');
    // Back to the original text.
    await page.getByTestId('view-text').click();
    await setEditorText(page, original);
    await page.keyboard.press('ControlOrMeta+s');
    await expect.poll(readText).toBe(original);
  });

  test('leaving with unsaved changes asks first', async ({ page }) => {
    const original = readText();
    await gotoText(page);
    await setEditorText(page, original + '// a comment\n');
    await page.getByTestId('view-diagram').click();
    const form = page.getByTestId('unsaved-text-form');
    await expect(form).toBeVisible();
    await form.getByRole('button', { name: 'Cancel' }).click();
    await expect(form).toHaveCount(0);
    await expect(page.getByTestId('text-view')).toBeVisible();
    await expect(editor(page)).toContainText('// a comment');

    // Another file: Discard.
    await page.getByTestId('file-select').selectOption('TwoCounters.dia');
    await form.getByRole('button', { name: 'Discard' }).click();
    await expect(page.getByTestId('file-select')).toHaveValue('TwoCounters.dia');
    await expect(editor(page)).not.toContainText('// a comment');
    expect(readText()).toBe(original);

    // Save, then leave.
    await page.getByTestId('file-select').selectOption('Text.dia');
    await expect(editor(page)).toContainText('diagramtype Acc');
    await setEditorText(page, original + '// saved\n');
    await page.getByTestId('view-diagram').click();
    await form.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByTestId('text-view')).toHaveCount(0);
    await expect.poll(readText).toBe(original + '// saved\n');
    await page.getByTestId('view-text').click();
    await setEditorText(page, original);
    await page.keyboard.press('ControlOrMeta+s');
    await expect.poll(readText).toBe(original);
  });

  test('a change made elsewhere: reloaded, or offered while there are unsaved changes', async ({ page }) => {
    const original = readText();
    await gotoText(page);
    await expect(editor(page)).toContainText('diagramtype Acc');

    // No unsaved changes: the editor takes the new text.
    writeFileSync(textFile, original + '// outside 1\n');
    await expect(editor(page)).toContainText('// outside 1');
    await expect(page.getByTestId('text-state')).toHaveText('Saved');

    // Unsaved changes: a banner offers to reload.
    await setEditorText(page, original + '// mine\n');
    writeFileSync(textFile, original + '// outside 2\n');
    const conflict = page.getByTestId('text-conflict');
    await expect(conflict).toBeVisible();
    expect(await editorText(page)).toContain('// mine');
    await conflict.getByRole('button', { name: 'Reload (discard mine)' }).click();
    await expect(conflict).toHaveCount(0);
    await expect(editor(page)).toContainText('// outside 2');
    await expect(editor(page)).not.toContainText('// mine');

    // Or keep them: saving then overwrites the other change.
    await setEditorText(page, original);
    writeFileSync(textFile, original + '// outside 3\n');
    await conflict.getByRole('button', { name: 'Keep mine' }).click();
    await page.keyboard.press('ControlOrMeta+s');
    await expect.poll(readText).toBe(original);
    await expect(page.getByTestId('text-state')).toHaveText('Saved');
  });
});
