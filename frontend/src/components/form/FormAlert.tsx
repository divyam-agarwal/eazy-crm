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
 */
export function FormAlert({ message }: { message: string | null }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!message) return;
    ref.current?.focus();
    ref.current?.scrollIntoView({ block: 'center', behavior: 'auto' });
  }, [message]);

  return (
    <div
      ref={ref}
      role="alert"
      tabIndex={message ? -1 : undefined}
      className={cn(
        // A11y-2: `text-destructive` on `bg-destructive/10` computes to ~4.39:1 (browsers report
        // close to the ~3.99:1 the plan cites), under the 4.5:1 AA minimum for 14px text. Darkened
        // the text (`--destructive-strong`, measured ~7.53:1 against this same tint) rather than
        // dropping the tint — see the task-7 report for the measurement.
        message &&
          'border-destructive/50 bg-destructive/10 text-destructive-strong rounded-md border p-3 text-sm outline-none',
      )}
    >
      {message}
    </div>
  );
}
