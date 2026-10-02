import { createBrowserRouter } from 'react-router';

import { RouteError } from '@/components/errors/route-error';
import { AppLayout } from '@/components/layout/app-layout';

import { RequireAuth } from './require-auth';

export function createAppRouter() {
  return createBrowserRouter([
    {
      path: '/login',
      errorElement: <RouteError />,
      lazy: () => import('./routes/login').then((module) => ({ Component: module.LoginRoute })),
    },
    {
      element: <RequireAuth />,
      errorElement: <RouteError />,
      children: [
        {
          element: <AppLayout />,
          children: [
            {
              path: '/',
              lazy: () => import('./routes/files').then((module) => ({ Component: module.FilesRoute })),
              children: [
                {
                  path: 'files/:fileId',
                  lazy: () => import('./routes/file-detail').then((module) => ({ Component: module.FileDetailRoute })),
                },
              ],
            },
            {
              path: '*',
              lazy: () => import('./routes/not-found').then((module) => ({ Component: module.NotFoundRoute })),
            },
          ],
        },
      ],
    },
  ]);
}
