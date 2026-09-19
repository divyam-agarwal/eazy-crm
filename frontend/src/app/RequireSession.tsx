import { Navigate, Outlet, useLocation } from 'react-router';
import { sessionControls } from '@/features/auth/session/start';
import { safeNext } from '@/lib/safeNext';
import { useSessionStatus } from '@/session/useMe';
import { UnreachableScreen } from './shell/UnreachableScreen';

export function RequireSession() {
  const status = useSessionStatus();
  const location = useLocation();
  switch (status) {
    case 'booting':
    case 'signing-out':
      // booting: render nothing, so #root stays empty and the index.html splash shows (plan P7).
      // signing-out: RootLayout renders the blocking screen instead of this outlet.
      return null;
    case 'unreachable':
      return <UnreachableScreen onRetry={() => sessionControls().boot.retryNow()} />;
    case 'anonymous': {
      const next = safeNext(location.pathname + location.search) ?? '/';
      return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />;
    }
    case 'authenticated':
      return <Outlet />;
  }
}
