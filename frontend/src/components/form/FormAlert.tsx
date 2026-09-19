import { useEffect, useRef } from 'react';
import { cn } from '@/lib/utils';

/**
 * Persistent role="alert" region (spec §4.6): form-level failures are never toasts.
 *
 * <p>A11y-1: `role="alert"` reaches a screen reader, but a sighted phone user never sees a message
 * that renders above the fold of a two-screen form while their thumb is on a submit button at the
 * bottom. So the region also takes focus and scrolls itself into view whenever the message changes
 * to non-null. Focus additionally repairs what disabling the submit button breaks: a focused
 * element that becomes disabled can drop focus to <body>, stranding a keyboard user at the top of
 * the page. The region stays mounted (never conditionally inserted) so live-region announcements
 * are reliable.
 *
 * <p>Fix round 1 (Challenge #93): keying the announcement effect on `message` alone means a
 * *second* failure with the *same* text — patchy 4G, submit, network error, submit again, same
 * error — never re-runs the effect (React bails out of the state update, and the deps array is
 * unchanged either way), so the live region's DOM text never mutates and most screen readers
 * announce nothing on the retry. `attempt` is a caller-supplied counter (RHF's
 * `formState.submitCount` is the natural source) that changes on every submit regardless of
 * whether the resulting message text repeats, so the effect — and the focus/scroll it performs —
 * runs on every attempt, not just every distinct message.
 */
export function FormAlert({ message, attempt }: { message: string | null; attempt: number }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!message) return;
    ref.current?.focus();
    ref.current?.scrollIntoView({ block: 'center', behavior: 'auto' });
    // `attempt` is intentionally in the deps though unused in the body — see the doc comment above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [message, attempt]);

  return (
    <div
      ref={ref}
      role="alert"
      tabIndex={message ? -1 : undefined}
      className={cn(
        // A11y-2: `text-destructive` on `bg-destructive/10` composites to ~3.987:1 (matching the
        // browser-measured ~3.99:1 the plan cites), under the 4.5:1 AA minimum for 14px text.
        // Darkened the text (`--destructive-strong`, measured ~6.84:1 against this same tint)
        // rather than dropping the tint — see the task-7 report (and its fix-round-1 amendment) for
        // the measurement and a compositing-space bug the first pass got wrong.
        message &&
          'border-destructive/50 bg-destructive/10 text-destructive-strong rounded-md border p-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-destructive-strong/40',
      )}
    >
      {message}
    </div>
  );
}
