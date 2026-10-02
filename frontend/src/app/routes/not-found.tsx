import { Link } from 'react-router';

import { Button } from '@/components/ui/button';
import { labels } from '@/i18n/messages';

export function NotFoundRoute() {
  return (
    <div className="flex flex-col items-center gap-4 py-24 text-center">
      <h1 className="text-xl font-semibold">{labels.notFound.title}</h1>
      <Button render={<Link to="/" />}>{labels.notFound.back}</Button>
    </div>
  );
}
