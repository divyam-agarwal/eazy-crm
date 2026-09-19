import { useEffect, useRef, type ReactNode } from 'react';

/** Spec §5.3: focus moves to the page <h1> after navigation, so screen readers start at the page. */
export function PageHeading({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    ref.current?.focus();
  }, []);
  return (
    <h1 ref={ref} tabIndex={-1} className="text-2xl font-semibold outline-none">
      {children}
    </h1>
  );
}
