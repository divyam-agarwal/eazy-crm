/**
 * Task 12 review, fix round 1, item 5: a typed contract for route-level flags `RootLayout` reads
 * off the matched route config, declared where a route is defined instead of as a string match
 * (`location.pathname.startsWith('/invite/')`) `RootLayout` would otherwise have to remember to
 * extend by hand for every future route with the same requirement — with no compiler or lint
 * signal if it forgets.
 */
export interface RouteHandle {
  /**
   * `RootLayout`'s global `'signing-out'` gate swaps `<Outlet/>` for a single shared blocking
   * screen while a logout is pending. A route that owns its own in-page signing-out rendering
   * (see `InvitePage.tsx`'s `status === 'signing-out'` branch, and spec §5.1's "sign out and
   * accept" — R23) sets this on its route object so `RootLayout` leaves it mounted instead.
   */
  survivesSignOut?: boolean;
}

/** `RouteObject['handle']` is typed `unknown` by react-router; this is the one place that narrows it. */
export function survivesSignOut(handle: unknown): boolean {
  return typeof handle === 'object' && handle !== null && (handle as RouteHandle).survivesSignOut === true;
}
