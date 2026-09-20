import { Suspense, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation, useMatches, useNavigate } from 'react-router';
import { onSessionExpired } from '@/features/auth/session/sessionEvents';
import { safeNext } from '@/lib/safeNext';
import { useSessionStatus } from '@/session/useMe';
import { survivesSignOut } from './routeHandle';
import { SignOutPendingScreen } from './shell/SignOutPendingScreen';

/**
 * Fix round 1, item 3 (a11y) — split out from RootLayout itself, and wrapped in its OWN local
 * `<Suspense fallback={null}>` where it's used below, for a subtle but real reason: `useTranslation()`
 * suspends if its namespace isn't loaded yet (R71/R47(a)), and RootLayout is the router's ROOT
 * element — nothing wraps IT in a Suspense boundary (Providers.tsx deliberately avoids that, so a
 * public route's own local skeleton isn't blanked by one top-level fallback). Calling
 * `useTranslation()` directly in RootLayout's own render would suspend RootLayout itself with no
 * boundary to catch it, blanking the ENTIRE app on a cold load that reaches here before i18n is
 * ready — exactly the failure mode R71(a)'s test exists to catch (it caught this one immediately).
 * Isolating the hook in its own child, under its own nearby Suspense, keeps that risk local to this
 * one small region instead of the whole page.
 */
function SigningOutAnnouncer({ active }: { active: boolean }) {
  const { t } = useTranslation();
  return (
    <div role="status" className="sr-only">
      {active ? t('signOutPending.body') : ''}
    </div>
  );
}

export function RootLayout() {
  const status = useSessionStatus();
  const navigate = useNavigate();
  const location = useLocation();
  const matches = useMatches();
  const locationRef = useRef(location);
  const exempt = matches.some((m) => survivesSignOut(m.handle));
  // Final fix wave, item 1 (Important — the reviewer's own error, not a defect introduced by I8):
  // R23/F0-10 exempt this route from RootLayout's global gates so a sign-out here never loses the
  // invite link; the 'signing-out' swap below already respects `exempt`, but this listener didn't,
  // and I8's new `emitSessionExpired()` call on remote logout gave it a real, non-forged trigger — a
  // colleague signed in on /invite/:token, another tab on the same shared counter phone completes an
  // ordinary sign-out, the broadcast reaches `me`-truthy `subscribeToAuthChannel`, and the resulting
  // event used to yank this tab to /login regardless. A ref, same pattern as `locationRef` above:
  // `exempt` is derived from `matches`, which only changes across a real navigation, so recomputing
  // it inside the listener via a stale closure would be wrong for the OPPOSITE reason `locationRef`
  // exists — read the CURRENT route's exemption, not the one captured when the effect last ran.
  const exemptRef = useRef(exempt);

  useEffect(() => {
    locationRef.current = location;
  }, [location]);

  useEffect(() => {
    exemptRef.current = exempt;
  }, [exempt]);

  // The HTTP layer never calls the router (spec §4.4): it raises this event, and the router listens, so
  // F2 can swap navigation for a re-login dialog that preserves an in-progress quotation.
  useEffect(
    () =>
      onSessionExpired(() => {
        // See the `exemptRef` comment above: a route that owns its own signing-out/anonymous
        // rendering (spec §5.1's sign-out-and-accept) must be left alone here — it falls through to
        // its own in-page anonymous state instead of being redirected away from the link it's on.
        if (exemptRef.current) return;
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
  //
  // R23 (Task 12 controller ruling): a route may exempt itself from this gate via its route
  // config (`handle: { survivesSignOut: true }`, see routeHandle.ts and router.tsx's `invite/:token`
  // entry — fix round 1, item 5, replacing an earlier hand-maintained pathname string match). Spec
  // §5.1's "sign out and accept" button runs the §4.4 logout and then shows the anonymous state ON
  // THE SAME PAGE — swapping in SignOutPendingScreen here would unmount that page mid-flow instead,
  // discarding its local state (InvitePage's `maybeAccepted`, its `acceptLost` ref) the moment
  // `status` flips to 'signing-out', and again when it flips back. Such a route owns its own
  // signing-out rendering (see InvitePage.tsx's `status === 'signing-out'` branch) so its Outlet is
  // never swapped out here. (`exempt` itself is computed once, above, and shared with the
  // `onSessionExpired` listener via `exemptRef`.)
  const blocked = status === 'signing-out' && !exempt;
  return (
    <>
      {/* Fix round 1, item 3: permanently mounted for the rest of the app session once boot has
          resolved — RootLayout itself never unmounts across navigations, so from here on this
          region PRE-EXISTS every 'signing-out' transition and the announcement is always a
          MUTATION of an already-mounted node, never a fresh insertion born together with its own
          content (the anti-pattern SignOutPendingScreen used to repeat on its own, and the one
          InvitePage.tsx's own hoisted region — see its comment — was rebuilt to avoid). Empty for
          an exempt route: that route's own page owns the announcement instead (see InvitePage.tsx),
          so this region must not double it.
          Gated on `status !== 'booting'`: 'signing-out' is unreachable before boot resolves (you
          can't sign out before you're signed in), so nothing is lost by not mounting this yet, and
          P7's contract — the React root stays COMPLETELY empty while booting, so the static
          index.html splash shows through — would otherwise break the moment this file loads. */}
      {status !== 'booting' && (
        <Suspense fallback={null}>
          <SigningOutAnnouncer active={blocked} />
        </Suspense>
      )}
      <Suspense fallback={null}>{blocked ? <SignOutPendingScreen /> : <Outlet />}</Suspense>
    </>
  );
}
