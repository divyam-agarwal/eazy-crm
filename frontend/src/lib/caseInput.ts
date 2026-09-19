import type { ChangeEvent } from 'react';

/**
 * Assigning to `event.target.value` moves the caret to the end. On a GSTIN pasted in lowercase
 * from WhatsApp, fixing one character in the middle then jumps the cursor on every keystroke.
 * Restore the selection the user had.
 */
export function forceCase(event: ChangeEvent<HTMLInputElement>, to: 'upper' | 'lower'): void {
  const next = to === 'upper' ? event.target.value.toUpperCase() : event.target.value.toLowerCase();
  if (next === event.target.value) return;
  const { selectionStart, selectionEnd } = event.target;
  event.target.value = next;
  if (selectionStart !== null && selectionEnd !== null) {
    event.target.setSelectionRange(selectionStart, selectionEnd);
  }
}
