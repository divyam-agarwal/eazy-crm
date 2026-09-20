/**
 * I7 (final fix wave, same pattern as `basicButtonClass.ts`'s fix round 1 item 4): `router.tsx`
 * statically imports `RouteSkeleton` (it is the local `<Suspense fallback={...}>` for every public
 * route, so it must be ready before any route's lazy chunk resolves), and `RouteSkeleton` rendered
 * five `<Skeleton>`s from `components/ui/skeleton.tsx`, which imports `cn` (clsx + tailwind-merge) —
 * 12.97 KB gzipped — purely to merge a fixed base class string with a handful of static Tailwind
 * utility classes it is given at each call site, none of which conflict with the base class or with
 * each other. That merge logic earns its cost when classes are dynamic or might collide; a
 * placeholder box's size classes are neither. `RouteSkeleton` now composes the base class with a
 * plain template string instead, so the entry chunk (every route pays for it) no longer carries
 * `cn` merely to render an inert loading placeholder. The real shadcn `Skeleton` (and `cn`) stay
 * exactly as they are for every lazy-loaded screen that already imports them for real reasons.
 */
export const BASIC_SKELETON_CLASS = 'animate-pulse motion-reduce:animate-none rounded-md bg-accent';
