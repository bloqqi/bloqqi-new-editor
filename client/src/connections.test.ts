import { describe, expect, it } from 'vitest';
import { canConnect, handleInfo } from './connections';
import { endpointLabel } from './InterceptMode';
import { node, port } from './testFixtures';

const add = node('add', { inPorts: [port('in1'), port('in2', 'Int', false)], outPorts: [port('out')] });
const realBlock = node('r', { inPorts: [port('in', 'Real')], outPorts: [port('out', 'Real')] });
const param = node('x', { kind: 'inParameter', typeName: 'Int', outConnectable: true });
const out = node('y', { kind: 'outParameter', typeName: 'Int', inConnectable: true });

describe('handles', () => {
  it('are block ports (in:/out:) or the single in and out of other nodes', () => {
    expect(handleInfo(add, 'in:in1')).toEqual({ role: 'target', typeName: 'Int', connectable: true });
    expect(handleInfo(add, 'out:out')).toEqual({ role: 'source', typeName: 'Int', connectable: true });
    expect(handleInfo(param, 'out')).toEqual({ role: 'source', typeName: 'Int', connectable: true });
    expect(handleInfo(param, 'in')).toEqual({ role: 'target', typeName: 'Int', connectable: false });
    expect(handleInfo(add, 'in:nosuch')).toBeNull();
    expect(handleInfo(add, null)).toBeNull();
  });
});

describe('which connections are offered', () => {
  it('a free source to a free target of the same type', () => {
    expect(canConnect({ node: param, handle: 'out' }, { node: add, handle: 'in:in1' })).toBe(true);
    expect(canConnect({ node: add, handle: 'out:out' }, { node: out, handle: 'in' })).toBe(true);
    // Either way round.
    expect(canConnect({ node: add, handle: 'in:in1' }, { node: param, handle: 'out' })).toBe(true);
  });

  it('not to a taken port, another type, two sources, or the same node', () => {
    expect(canConnect({ node: param, handle: 'out' }, { node: add, handle: 'in:in2' })).toBe(false);
    expect(canConnect({ node: param, handle: 'out' }, { node: realBlock, handle: 'in:in' })).toBe(false);
    expect(canConnect({ node: param, handle: 'out' }, { node: add, handle: 'out:out' })).toBe(false);
    expect(canConnect({ node: add, handle: 'out:out' }, { node: add, handle: 'in:in1' })).toBe(false);
  });
});

describe('how an endpoint is written', () => {
  it('block.port, or a node name', () => {
    expect(endpointLabel('acc1', 'in:in')).toBe('acc1.in');
    expect(endpointLabel('SelReal_1', 'out:out')).toBe('SelReal_1.out');
    expect(endpointLabel('x', 'out')).toBe('x');
  });
});
