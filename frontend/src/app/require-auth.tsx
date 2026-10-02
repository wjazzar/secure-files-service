import { Navigate, Outlet, useLocation } from 'react-router';

import { useAuth } from '@/lib/auth/use-auth';

/**
 * Route guard. Without a session, sends the user to the login page,
 * remembering where they were going.
 *
 * This is a comfort, not a protection: the API refuses every request without
 * a valid token anyway.
 */
export function RequireAuth() {
  const { status } = useAuth();
  const location = useLocation();

  if (status !== 'authenticated') {
    const target = `${location.pathname}${location.search}`;
    const search = target === '/' ? '' : `?redirect=${encodeURIComponent(target)}`;
    return <Navigate to={`/login${search}`} replace />;
  }
  return <Outlet />;
}
