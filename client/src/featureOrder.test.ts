import { describe, expect, it } from 'vitest';
import { featureOrder } from './FeaturesForm';
import type { FeatureOption } from './protocol';

const option = (name: string, before: string[] = []): FeatureOption => ({
  name,
  typeName: name,
  selected: false,
  excludes: [],
  before,
});

// PLib.dia: features P { Derivative before Integral; Derivative before PTrack; ... }
const options = [option('Derivative', ['Integral', 'PTrack']), option('Integral'), option('PTrack')];

describe('the order of selected features', () => {
  it('follows the before rules', () => {
    expect(featureOrder(options, new Set(['Integral', 'Derivative']))).toEqual(['Derivative', 'Integral']);
    expect(featureOrder(options, new Set(['PTrack', 'Derivative']))).toEqual(['Derivative', 'PTrack']);
  });

  it('orders the others by name, and leaves out unselected ones', () => {
    expect(featureOrder(options, new Set(['PTrack', 'Integral']))).toEqual(['Integral', 'PTrack']);
    expect(featureOrder(options, new Set())).toEqual([]);
  });

  it('ignores rules about unselected features', () => {
    const chain = [option('C'), option('A', ['B']), option('B', ['C'])];
    // A before B before C, with B unselected: A and C have no rule between them.
    expect(featureOrder(chain, new Set(['C', 'A']))).toEqual(['A', 'C']);
  });

  it('still ends on a cycle (which the compiler reports as an error)', () => {
    const cycle = [option('X', ['Y']), option('Y', ['X'])];
    expect(featureOrder(cycle, new Set(['X', 'Y']))).toEqual(['X', 'Y']);
  });
});
