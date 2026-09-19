/**
 * Fix round 1, item 4 (performance lens): `RouteErrorBoundary` and `UnreachableScreen` are both
 * statically reachable from the entry chunk (via `router.tsx`'s non-lazy `errorElement`s and
 * `RequireSession`'s non-lazy import) and, before this change, both pulled in the shadcn `Button`
 * — `cn` + `class-variance-authority` + `radix-ui/react-slot`, ~6.7 KB gzip — on every single page
 * load, including the 404 and boot-retry screens most users never see. Neither button needs a
 * `variant`/`size`/`asChild`, so this is the plain default-variant, default-size look as a literal
 * class string, with no runtime variant machinery. `AppShell`'s "Sign out" button DOES use
 * `variant="outline" size="sm"` and stays on the real `Button` — it's a separate, already-lazy chunk,
 * so keeping it there costs the entry nothing.
 */
export const BASIC_BUTTON_CLASS =
  'inline-flex h-9 shrink-0 items-center justify-center gap-2 rounded-md bg-primary px-4 py-2 text-sm ' +
  'font-medium whitespace-nowrap text-primary-foreground outline-none transition-all hover:bg-primary/90 ' +
  'focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none ' +
  'disabled:opacity-50';
