import { Suspense, useEffect, useRef } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { onSessionExpired } from '@/features/auth/session/sessionEvents';
import { safeNext } from '@/lib/safeNext';
import { useSessionStatus } from '@/session/useMe';
import { SignOutPendingScreen } from './shell/SignOutPendingScreen';

export function RootLayout() {
  const status = useSessionStatus();
  const navigate = useNavigate();
  const location = useLocation();
  const locationRef = useRef(location);

  useEffect(() => {
    locationRef.current = location;
  }, [location]);

  // The HTTP layer never calls the router (spec §4.4): it raises this event, and the router listens, so
  // F2 can swap navigation for a re-login dialog that preserves an in-progress quotation.
  useEffect(
    () =>
      onSessionExpired(() => {
        const { pathname, search } = locationRef.current;
        const next = safeNext(pathname + search) ?? '/';
        // A11y-5: carry WHY. Without it the user is dropped on an empty login page mid-task and
        // cannot tell whether they mis-tapped something. LoginPage seeds its FormAlert from this.
        void navigate(`/login?next=${encodeURIComponent(next)}`, {
          replace: true,
          state: { reason: 'expired' },
        });
      }),
    [navigate],
  );

  // R47(a): this Suspense wraps only RootLayout's OWN content, not the whole <RouterProvider> (see
  // providers.tsx). A single top-level `<Suspense fallback={null}>` around the entire router would
  // catch a suspend from ANY descendant — including a public route Tasks 10-12 mount as a sibling
  // inside this very Outlet — and blank the whole app instead of showing that route's own skeleton.
  // A route that wants a skeleton nests its OWN, nearer Suspense boundary inside its element, which
  // then catches the suspend before it ever reaches this one. `fallback={null}` here is correct for
  // what actually lives directly under RootLayout without such a boundary of its own (the protected
  // shell while booting — P7 — and SignOutPendingScreen, both already gated behind a resolved
  // session so real i18n-suspense here is the rare case, not the common one).
  return (
    <Suspense fallback={null}>{status === 'signing-out' ? <SignOutPendingScreen /> : <Outlet />}</Suspense>
  );
}
