import { useQuery } from '@tanstack/react-query';
import { cn } from 'cn';
import { ArrowRightIcon, FlaskConicalIcon } from 'lucide-react';

import { mockLabels } from '@/i18n/mock-messages';
import { auth } from '@/lib/auth/auth';
import { initials } from '@/lib/initials';

import type { useSignIn } from '../hooks/use-sign-in';

/**
 * Mock mode only: the demo accounts. Each one owns a private file space;
 * one click signs in.
 */
export function DemoAccounts({ signIn }: { signIn: ReturnType<typeof useSignIn> }) {
  const loadAccounts = auth.demoAccounts;
  const accounts = useQuery({
    queryKey: ['demo-accounts'],
    queryFn: () => loadAccounts?.() ?? Promise.resolve([]),
    enabled: loadAccounts !== undefined,
    staleTime: Infinity,
  });
  if (!accounts.data?.length) return null;

  const pendingUser = signIn.isPending ? signIn.variables.username : null;

  return (
    <section aria-labelledby="demo-accounts-title" className="login-demo space-y-3">
      <div className="flex items-center gap-3">
        <span aria-hidden className="h-px flex-1 bg-border" />
        <h2
          id="demo-accounts-title"
          className="login-demo-title flex items-center gap-1.5 font-semibold text-muted-foreground uppercase"
        >
          <FlaskConicalIcon aria-hidden className="size-3.5" />
          {mockLabels.login.demoTitle}
        </h2>
        <span aria-hidden className="h-px flex-1 bg-border" />
      </div>
      <p className="login-demo-hint text-center text-muted-foreground">{mockLabels.login.demoHint}</p>
      <ul className="login-demo-grid">
        {accounts.data.map((account) => (
          <li key={account.username}>
            <button
              type="button"
              disabled={signIn.isPending}
              onClick={() => signIn.mutate({ username: account.username, password: account.password })}
              aria-label={mockLabels.login.demoSignIn(account.displayName)}
              className={cn(
                'login-demo-account disabled:cursor-wait disabled:opacity-60',
                pendingUser === account.username && 'login-demo-account-pending',
              )}
            >
              <span aria-hidden className="login-demo-avatar">
                {initials(account.displayName)}
              </span>
              <span className="login-demo-name">{account.displayName}</span>
              <span className="login-demo-username">{account.username}</span>
              <ArrowRightIcon aria-hidden className="login-demo-arrow" />
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
