import { TriangleAlertIcon } from 'lucide-react';
import { useRouteError } from 'react-router';

import { Button } from '@/components/ui/button';
import { labels } from '@/i18n/messages';

/** Last-resort screen: an unexpected error during rendering. */
export function RouteError() {
  const error = useRouteError();
  if (import.meta.env.DEV) console.error(error);
  return (
    <div role="alert" className="mx-auto flex max-w-md flex-col items-center gap-4 py-24 text-center">
      <TriangleAlertIcon aria-hidden className="size-10 text-status-warning" />
      <h1 className="text-xl font-semibold">{labels.crash.title}</h1>
      <Button onClick={() => window.location.reload()}>{labels.crash.reload}</Button>
    </div>
  );
}
