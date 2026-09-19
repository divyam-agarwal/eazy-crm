// @vitest-environment node
// Performance-8: a ledger filled once by hand is a document, not a gate — F1 can add a runtime
// dependency and nobody notices. This guard fails the build the first time a `dependencies` entry
// in package.json has no row in DEPENDENCIES.md.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import pkg from '../package.json' with { type: 'json' };

describe('dependency ledger', () => {
  it('has a row for every runtime dependency', () => {
    const ledger = readFileSync(new URL('../DEPENDENCIES.md', import.meta.url), 'utf8');
    const names = Object.keys(pkg.dependencies ?? {});
    expect(names.length).toBeGreaterThan(0); // non-vacuity
    expect(names.filter((name) => !new RegExp(`^\\|\\s*${name}\\s*\\|`, 'm').test(ledger))).toEqual(
      [],
    );
  });
});
