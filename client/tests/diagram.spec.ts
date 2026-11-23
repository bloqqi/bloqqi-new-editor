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
  const dragFile = resolve('..', 'build', 'e2e-workspace', 'Drag.dia');

  test('dragging a block stores its position in the file', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await expect(node(page, 'add')).toBeVisible();
    expect(readFileSync(dragFile, 'utf8')).not.toContain('annotation');
    const outBefore = (await node(page, 'out').boundingBox())!;

    await drag(page, 'add', 40, 120);

    // The server writes the whole layout of Acc (it was auto-laid-out) to the file...
    await expect.poll(() => readFileSync(dragFile, 'utf8')).toMatch(/annotation\(\n {2}add = \(/);
    const text = readFileSync(dragFile, 'utf8');
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

    await expect.poll(() => readFileSync(dragFile, 'utf8')).toContain('  acc1 = (');
    expect(await gap()).toBeCloseTo(gapBefore, 0);
    // Literals are not stored.
    expect(readFileSync(dragFile, 'utf8')).not.toContain('literal');
  });

  test('nodes stop at the top and left edge of the diagram', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    await expect(node(page, 'in')).toBeVisible();
    // Drag to the canvas' top-left corner (the mouse can't leave the window).
    const pane = (await page.locator('.react-flow__pane').boundingBox())!;
    const start = (await node(page, 'in').boundingBox())!;
    await drag(page, 'in', pane.x + 2 - (start.x + start.width / 2), pane.y + 2 - (start.y + start.height / 2));

    await expect.poll(() => readFileSync(dragFile, 'utf8')).toContain('  in = (0, 0, ');
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

    await expect.poll(() => readFileSync(dragFile, 'utf8')).toMatch(/ {2}acc2 = \(\d+, /);
    const x = Number(/ {2}acc2 = \((\d+), /.exec(readFileSync(dragFile, 'utf8'))![1]);
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
    const before = readFileSync(dragFile, 'utf8');
    const offset = async () =>
      (await node(page, 'acc2').boundingBox())!.x - (await literal.boundingBox())!.x;
    const offsetBefore = await offset();
    // (Dragging a literal pans the canvas instead.)
    await drag(page, '#literal:acc2.in:in', 100, 100);
    await page.waitForTimeout(300);
    expect(await offset()).toBeCloseTo(offsetBefore, 0);
    expect(readFileSync(dragFile, 'utf8')).toBe(before);
  });

  // Connections (still Drag.dia, so these run after the moves).

  test('selecting a connection and pressing Delete removes its statement', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    expect(readFileSync(dragFile, 'utf8')).toContain('  connect(in, add.in1);\n');
    await expect(page.locator('.react-flow__edge')).toHaveCount(4);

    await edge(page, 'in.out->add.in:in1').click();
    await expect(edge(page, 'in.out->add.in:in1')).toHaveClass(/selected/);
    await page.keyboard.press('Delete');

    await expect(page.locator('.react-flow__edge')).toHaveCount(3);
    expect(readFileSync(dragFile, 'utf8')).not.toContain('connect(in, add.in1);');
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
    expect(readFileSync(dragFile, 'utf8')).toContain('  connect(add.out, state);\n  connect(in, add.in1);\n}');
  });

  test('a port that already has a connection cannot get another', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readFileSync(dragFile, 'utf8');
    const start = (await handle(page, 'add', 'out:out').boundingBox())!;
    const end = (await handle(page, 'add', 'in:in2').boundingBox())!;
    await page.mouse.move(start.x + 3, start.y + 3);
    await page.mouse.down();
    await page.mouse.move(end.x + 3, end.y + 3, { steps: 10 });
    await page.mouse.up();
    await page.waitForTimeout(300);
    await expect(page.locator('.react-flow__edge')).toHaveCount(4);
    expect(readFileSync(dragFile, 'utf8')).toBe(before);
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('selecting a literal and pressing Delete removes its connection', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    expect(readFileSync(dragFile, 'utf8')).toContain('connect(1, acc1.in);');
    await node(page, '#literal:acc1.in:in').click();
    await page.keyboard.press('Delete');
    await expect(node(page, '#literal:acc1.in:in')).toHaveCount(0);
    expect(readFileSync(dragFile, 'utf8')).not.toContain('connect(1, acc1.in);');
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
    expect(readFileSync(dragFile, 'utf8')).not.toContain('connect(state, add.in2);');
    await expect(button).toHaveCount(0);
  });

  test('the × on a selected literal deletes its connection', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Main');
    const literal = node(page, '#literal:acc2.in:in');
    await literal.click();
    await page.getByRole('button', { name: 'Delete literal', exact: true }).click();
    await expect(literal).toHaveCount(0);
    expect(readFileSync(dragFile, 'utf8')).not.toContain('connect(2, acc2.in);');
  });

  // Undo/redo (still Drag.dia; the last test changes the file from outside).

  test('undo and redo a move with the toolbar buttons', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const undo = page.getByTestId('undo');
    const redo = page.getByTestId('redo');
    await expect(undo).toBeDisabled(); // a new tab has no history
    await expect(redo).toBeDisabled();

    const before = readFileSync(dragFile, 'utf8');
    await drag(page, 'add', 30, 40);
    await expect.poll(() => readFileSync(dragFile, 'utf8')).not.toBe(before);
    const moved = readFileSync(dragFile, 'utf8');
    await expect(undo).toBeEnabled();

    await undo.click();
    await expect.poll(() => readFileSync(dragFile, 'utf8')).toBe(before);
    await expect(undo).toBeDisabled();
    await expect(redo).toBeEnabled();

    await redo.click();
    await expect.poll(() => readFileSync(dragFile, 'utf8')).toBe(moved);
    await expect(redo).toBeDisabled();
    await expect(undo).toBeEnabled();
  });

  test('undo and redo a deleted connection with the keyboard', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readFileSync(dragFile, 'utf8');
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
    expect(readFileSync(dragFile, 'utf8')).toBe(before);

    await page.keyboard.press('Control+Shift+z');
    await expect(edge(page, 'add.out:out->out.in')).toHaveCount(0);
    await page.keyboard.press('Control+z');
    await expect(edge(page, 'add.out:out->out.in')).toHaveCount(1);
  });

  test('undo is refused if the file was changed elsewhere', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readFileSync(dragFile, 'utf8');
    await drag(page, 'add', 20, 20);
    await expect.poll(() => readFileSync(dragFile, 'utf8')).not.toBe(before);

    // Someone edits the file in a text editor.
    const external = readFileSync(dragFile, 'utf8') + '// edited elsewhere\n';
    writeFileSync(dragFile, external);

    await page.getByTestId('undo').click();
    await expect(page.getByRole('alert')).toContainText('has been changed since');
    await expect(page.getByRole('alert')).toContainText('undo history of Drag.dia has been cleared');
    await expect(page.getByTestId('undo')).toBeDisabled();
    expect(readFileSync(dragFile, 'utf8')).toBe(external);
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
    expect(readFileSync(dragFile, 'utf8')).toContain('  add: Add;\n  Add_1;\n');
    expect(readFileSync(dragFile, 'utf8')).toMatch(/ {2}Add_1 = \(\d+, \d+, 60, 30\)/);

    await node(page, 'Add_1').click();
    await page.getByRole('button', { name: 'Delete block', exact: true }).click();
    await expect(node(page, 'Add_1')).toHaveCount(0);
    expect(readFileSync(dragFile, 'utf8')).not.toContain('Add_1');

    await page.keyboard.press('Control+z');
    await expect(node(page, 'Add_1')).toBeVisible();
  });

  test('Delete removes a block with its connections, and undo brings them back', async ({ page }) => {
    await goto(page, '/?file=Drag.dia&type=Acc');
    const before = readFileSync(dragFile, 'utf8');
    await node(page, 'add').click();
    await page.keyboard.press('Delete');

    await expect(node(page, 'add')).toHaveCount(0);
    const text = readFileSync(dragFile, 'utf8');
    expect(text).not.toContain('add: Add;');
    expect(text).not.toContain('add.');

    await page.keyboard.press('Control+z');
    await expect(node(page, 'add')).toBeVisible();
    expect(readFileSync(dragFile, 'utf8')).toBe(before);
  });
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

test('inherited blocks have no delete button', async ({ page }) => {
  await goto(page, '/?file=PLib.dia&type=PTrack');
  await node(page, 'P::e').click();
  await expect(page.getByRole('button', { name: 'Delete block', exact: true })).toHaveCount(0);
  await node(page, 'SelReal_1').click();
  await expect(page.getByRole('button', { name: 'Delete block', exact: true })).toHaveCount(1);
});

test('inherited connections cannot be selected', async ({ page }) => {
  await goto(page, '/?file=PLib.dia&type=PTrack');
  const inherited = edge(page, 'P::e.out:out->P::PPart.in:in1');
  await expect(inherited).not.toHaveClass(/selectable/);
  // Clicking it selects nothing, so no × appears.
  await inherited.click({ force: true });
  await expect(page.getByRole('button', { name: 'Delete connection', exact: true })).toHaveCount(0);
  await expect(edge(page, 'Track.out->SelReal_1.in:sel')).toHaveClass(/selectable/);
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
