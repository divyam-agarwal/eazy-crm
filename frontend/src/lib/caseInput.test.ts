import type { ChangeEvent } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { forceCase } from './caseInput';

function changeEvent(input: HTMLInputElement): ChangeEvent<HTMLInputElement> {
  return { target: input } as unknown as ChangeEvent<HTMLInputElement>;
}

// R7: this task (not Task 11, which only imports it) owns caseInput.ts, and the plan's
// one-sentence test description ("typing into the middle of 27aaa keeps the caret where it was,
// and the value is uppercased") is replaced here with a real body against a real <input>.
describe('forceCase', () => {
  it('uppercases the value and keeps the caret where it was', () => {
    const input = document.createElement('input');
    input.value = '27aaa';
    input.setSelectionRange(4, 4); // caret between the third and fourth "a"

    forceCase(changeEvent(input), 'upper');

    expect(input.value).toBe('27AAA');
    expect(input.selectionStart).toBe(4);
    expect(input.selectionEnd).toBe(4);
  });

  it('lowercases the value and keeps a non-collapsed selection', () => {
    const input = document.createElement('input');
    input.value = 'ABCDE';
    input.setSelectionRange(1, 3);

    forceCase(changeEvent(input), 'lower');

    expect(input.value).toBe('abcde');
    expect(input.selectionStart).toBe(1);
    expect(input.selectionEnd).toBe(3);
  });

  it('does nothing when the value already matches the target case', () => {
    const input = document.createElement('input');
    input.value = '27AAA';
    input.setSelectionRange(2, 2);
    const setSelectionRangeSpy = vi.spyOn(input, 'setSelectionRange');

    forceCase(changeEvent(input), 'upper');

    // Proves the early return: forceCase must not touch .value (which would itself move the
    // caret to the end in a real browser) or call setSelectionRange when there's nothing to fix.
    expect(setSelectionRangeSpy).not.toHaveBeenCalled();
  });
});
